import { useEffect, useRef } from 'react';
import { create } from 'zustand';
import {
  createPendingChange,
  dropByItem as removeByItem,
  dropEntry as removeEntry,
  enqueueChange,
  readQueue,
  writeQueue,
  type PendingStatusChange,
} from '../lib/statusQueue';
import type { ItineraryStatus } from '../types/trip';

/**
 * 离线待同步队列的 store（设计文档 §7.3 第 4 条 / §10.3）。
 *
 * 队列的状态机很小：entries（待同步）+ syncing（正在发）+ lastResults（逐项结果）。
 * 关键约束：
 * - 所有会改动 entries 的入口都会先 hydrate，再「读最新状态 → 计算 → 覆盖 + 持久化」，
 *   避免「还没挂载 hook 就点了完成」导致把 localStorage 里的旧队列写没；
 * - flush 期间用快照遍历、按 requestId 逐条出队：发送期间用户仍可撤销或产生同 item 的新变更；
 * - 任何发送失败都不会 reject flush，失败只体现在逐项结果与条目的 lastError 上。
 */

export interface SyncResult {
  requestId: string;
  itemId: number;
  title: string;
  status: ItineraryStatus;
  ok: boolean;
  error?: string;
}

/** 发送一条待同步变更：成功即视为服务端已接受（返回值只用于日志，不参与判断） */
export type SendPending = (entry: PendingStatusChange) => Promise<unknown>;

interface StatusQueueState {
  entries: PendingStatusChange[];
  syncing: boolean;
  /** 最近一次 flush 的逐项结果（供 UI 显示「2 项已同步 / 1 项失败」） */
  lastResults: SyncResult[];
  hydrated: boolean;
  hydrate: () => void;
  enqueue: (
    input: Parameters<typeof createPendingChange>[0] & { requestId?: string },
  ) => PendingStatusChange;
  drop: (requestId: string) => void;
  dropByItem: (itemId: number) => void;
  clear: () => void;
  clearResults: () => void;
  /** 顺序发送；不传 send 时用默认发送器（updateItemStatus，动态 import 避免循环依赖） */
  flush: (send?: SendPending) => Promise<SyncResult[]>;
}

/** axios / 网络层表示「请求没到服务端」的 code：遇到它们说明网络仍不可用 */
const NETWORK_ERROR_CODES = new Set(['ERR_NETWORK', 'ECONNABORTED', 'ETIMEDOUT', 'ERR_CANCELED']);

/** 失败原因文案：Error / axios 错误 / 字符串 / 未知值都给出可读文本（不返回空串） */
function errorMessage(error: unknown): string {
  if (typeof error === 'string' && error.trim() !== '') return error.trim();
  const candidate = error as { message?: unknown } | null;
  if (candidate && typeof candidate.message === 'string' && candidate.message.trim() !== '') {
    return candidate.message.trim();
  }
  return '同步失败';
}

/** 错误对象上我们关心的字段（ApiError / axios 原始错误 / 自定义发送器抛出的错误） */
interface ErrorLike {
  code?: unknown;
  status?: unknown;
  message?: unknown;
  response?: { status?: unknown } | null;
}

/** 读 HTTP 状态码：ApiError 的 status 优先，其次 axios 原始错误的 response.status */
function readHttpStatus(error: ErrorLike): number | undefined {
  if (typeof error.status === 'number') return error.status;
  const nested = error.response?.status;
  return typeof nested === 'number' ? nested : undefined;
}

/**
 * 网络类错误判定（决定「中止后续」还是「跳过这一条继续」）：
 * - code 属于网络/超时（axios 没拿到响应）→ 网络类；
 * - 否则看 HTTP 状态码：1-599 的整数说明服务端确实回应了（业务拒绝，例如 400/404/409），
 *   不算网络问题，继续处理后面的条目；
 * - 拿不到合法状态码（undefined / 0 / NaN / 非整数）→ 按网络不可用处理，中止。
 */
function isNetworkFailure(error: unknown): boolean {
  if (!error || typeof error !== 'object') return true;
  const candidate = error as ErrorLike;
  const code = candidate.code;
  if (typeof code === 'string' && NETWORK_ERROR_CODES.has(code.toUpperCase())) return true;
  const status = readHttpStatus(candidate);
  return !(typeof status === 'number' && Number.isInteger(status) && status >= 1 && status <= 599);
}

/** 默认发送器：动态 import，store 与 api 层不形成静态循环依赖 */
const sendViaApi: SendPending = (entry) =>
  import('../api/trip').then((module) => module.updateItemStatus(entry.itemId, entry.status));

/** 队列变更后立即落盘（storage 不可用时静默降级为「本次会话内存队列」） */
function persist(entries: PendingStatusChange[]): void {
  writeQueue(entries);
}

export const useStatusQueueStore = create<StatusQueueState>()((set, get) => ({
  entries: [],
  syncing: false,
  lastResults: [],
  hydrated: false,

  /**
   * 从 localStorage 读入。幂等：已 hydrate 直接返回，不会覆盖内存中的最新状态。
   * hydrate 之前内存里可能已经有条目（首屏还没挂载 hook 就点了「完成」），
   * 这些条目按入队顺序覆盖同 itemId 的存储条目，避免被读盘结果抹掉。
   */
  hydrate: () => {
    if (get().hydrated) return;
    const stored = readQueue();
    const merged = get().entries.reduce((list, entry) => enqueueChange(list, entry), stored);
    set({ entries: merged, hydrated: true });
    // 顺手把清洗后的队列写回，让手改坏的存储自愈
    persist(merged);
  },

  enqueue: (input) => {
    get().hydrate();
    const created = createPendingChange(input);
    const change = input.requestId ? { ...created, requestId: input.requestId } : created;
    const entries = enqueueChange(get().entries, change);
    set({ entries });
    persist(entries);
    return change;
  },

  drop: (requestId) => {
    get().hydrate();
    const entries = removeEntry(get().entries, requestId);
    set({ entries });
    persist(entries);
  },

  dropByItem: (itemId) => {
    get().hydrate();
    const entries = removeByItem(get().entries, itemId);
    set({ entries });
    persist(entries);
  },

  /** 清空队列（只清待同步条目；上次同步的逐项结果另由 clearResults 清） */
  clear: () => {
    get().hydrate();
    set({ entries: [] });
    persist([]);
  },

  clearResults: () => set({ lastResults: [] }),

  flush: async (send) => {
    // 并发保护：上一轮还没结束就再调一次，直接返回空结果，不重复发送
    if (get().syncing) return [];
    get().hydrate();
    set({ syncing: true });

    const sendFn = send ?? sendViaApi;
    // 快照遍历：发送期间 entries 可能被撤销 / 追加，写回时按最新状态计算
    const snapshot = get().entries;
    const results: SyncResult[] = [];

    try {
      for (const entry of snapshot) {
        // 发送前确认该条仍在队列（期间可能被撤销，或被同 item 的新变更替换掉）
        if (!get().entries.some((current) => current.requestId === entry.requestId)) continue;
        const base = {
          requestId: entry.requestId,
          itemId: entry.itemId,
          title: entry.title,
          status: entry.status,
        };
        try {
          await sendFn(entry);
          results.push({ ...base, ok: true });
          set({ entries: removeEntry(get().entries, entry.requestId) });
        } catch (error) {
          const message = errorMessage(error);
          results.push({ ...base, ok: false, error: message });
          // 失败条目保留在队列里并记下原因，下次 flush 会重试
          set({
            entries: get().entries.map((current) =>
              current.requestId === entry.requestId ? { ...current, lastError: message } : current,
            ),
          });
          // 网络仍不可用：后面的条目原样保留（不产出结果、不标记失败），等下次在线再同步
          if (isNetworkFailure(error)) break;
        }
      }
    } finally {
      persist(get().entries);
      set({ syncing: false, lastResults: results });
    }

    return results;
  },
}));

/** 浏览器在线判定：拿不到 navigator（SSR / 单测）时按在线处理 */
function readOnline(): boolean {
  if (typeof navigator === 'undefined') return true;
  return navigator.onLine !== false;
}

/**
 * 挂载即 hydrate + flush（在线时），并监听 window online 事件自动补同步。
 * `auto: false` 完全关闭自动行为（测试或需要手动控制同步时）；`send` 可注入。
 */
export function usePendingSync(options: { send?: SendPending; auto?: boolean } = {}): void {
  const { auto = true, send } = options;
  const sendRef = useRef<SendPending | undefined>(send);

  // 用 ref 镜像最新发送器：父组件每次渲染传新函数时不会重挂监听、也不会重复 flush
  useEffect(() => {
    sendRef.current = send;
  }, [send]);

  useEffect(() => {
    if (!auto) return;
    useStatusQueueStore.getState().hydrate();
    const flushNow = () => {
      void useStatusQueueStore.getState().flush(sendRef.current);
    };
    // 挂载时先补一次：离线期间产生的条目可能在这次进入页面前就已经恢复网络
    if (readOnline()) flushNow();
    if (typeof window === 'undefined') return;
    window.addEventListener('online', flushNow);
    return () => {
      window.removeEventListener('online', flushNow);
    };
  }, [auto]);
}
