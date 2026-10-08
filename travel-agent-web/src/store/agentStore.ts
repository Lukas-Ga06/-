import { create } from 'zustand';
import { cancelPlan, confirmPlan, streamAgentChat } from '../api/agent';
import type { AgentFocus, AgentResult, ChatMessage, SelectedElement } from '../types/agent';

export interface SendPayload {
  message: string;
  tripId?: number | null;
  currentDay?: number | null;
  selectedElement?: SelectedElement | null;
  mapCenter?: { lat: number; lng: number } | null;
}

interface AgentState {
  messages: ChatMessage[];
  streaming: boolean;
  stage: string | null;
  /** NEED_CONFIRMATION 状态的方案，用于渲染 Diff 确认弹窗 */
  pendingConfirm: AgentResult | null;
  lastFocus: AgentFocus | null;
  /** 规划页确认后新建的行程 id，用于跳转 */
  committedTripId: number | null;
  send: (payload: SendPayload) => void;
  confirm: () => Promise<AgentResult | null>;
  cancel: () => Promise<void>;
  /** 刷新后用 planId 取回的方案重新挂起确认弹窗 */
  restorePending: (result: AgentResult) => void;
  reset: () => void;
  clearCommitted: () => void;
}

let closeFn: (() => void) | null = null;
let msgSeq = 0;
/** 流式请求序号：票据获取是异步的，用序号丢弃"已被 reset/新请求取代"的回调 */
let streamSeq = 0;

function nextId(): string {
  msgSeq += 1;
  return `m-${Date.now()}-${msgSeq}`;
}

function pushMessage(list: ChatMessage[], msg: ChatMessage): ChatMessage[] {
  return [...list, msg];
}

const STAGE_TEXT: Record<string, (data: any) => string> = {
  start: () => '已连接 Agent…',
  intent: (d) => `意图识别：${d?.intent ?? '…'}`,
  planning: () => '正在规划行程…',
  tool_call: (d) =>
    d?.tool === 'search_city_online'
      ? '联网查询目的地的景点/酒店/餐厅数据…'
      : `调用工具 ${d?.tool ?? ''}…`,
  tool_result: (d) => `工具 ${d?.tool ?? ''} 执行完成`,
  rag_result: () => '检索旅游知识库…',
  plan_update: () => '生成方案对比…',
  confirmation: () => '等待你的确认…',
};

export const useAgentStore = create<AgentState>()((set, get) => ({
  messages: [],
  streaming: false,
  stage: null,
  pendingConfirm: null,
  lastFocus: null,
  committedTripId: null,

  send: (payload) => {
    if (get().streaming || !payload.message.trim()) {
      return;
    }
    closeFn?.();
    closeFn = null;
    const seq = ++streamSeq;
    const userMsg: ChatMessage = {
      id: nextId(),
      role: 'user',
      content: payload.message,
      time: new Date().toISOString(),
    };
    set({
      messages: pushMessage(get().messages, userMsg),
      streaming: true,
      stage: '连接 Agent…',
      pendingConfirm: null,
    });

    streamAgentChat(payload, {
        onEvent: (event, data) => {
          const text = STAGE_TEXT[event]?.(data);
          if (text) set({ stage: text });
          if (event === 'focus' && data) {
            set({ lastFocus: data as AgentFocus });
          }
        },
        onComplete: (result) => {
          const assistantMsg: ChatMessage = {
            id: nextId(),
            role: 'assistant',
            content: result.message || '（无返回内容）',
            time: new Date().toISOString(),
          };
          set({
            messages: pushMessage(get().messages, assistantMsg),
            streaming: false,
            stage: null,
            pendingConfirm: result.status === 'NEED_CONFIRMATION' ? result : null,
            lastFocus: result.focus ?? get().lastFocus,
            committedTripId:
              result.status === 'COMPLETED' && result.tripId && !payload.tripId ? result.tripId : get().committedTripId,
          });
        },
        onError: (errorText) => {
          if (seq !== streamSeq) return;
          const errMsg: ChatMessage = {
            id: nextId(),
            role: 'assistant',
            content: `⚠ ${errorText}`,
            time: new Date().toISOString(),
          };
          set({ messages: pushMessage(get().messages, errMsg), streaming: false, stage: null });
        },
      })
      .then((close) => {
        // reset / 新请求已经发生：直接关掉这条迟到的连接
        if (seq !== streamSeq) {
          close();
          return;
        }
        closeFn = close;
      })
      .catch((e: any) => {
        if (seq !== streamSeq) return;
        const errMsg: ChatMessage = {
          id: nextId(),
          role: 'assistant',
          content: `⚠ ${e?.message || '无法建立与 Agent 的连接'}`,
          time: new Date().toISOString(),
        };
        set({ messages: pushMessage(get().messages, errMsg), streaming: false, stage: null });
      });
  },

  confirm: async () => {
    const pending = get().pendingConfirm;
    const planId = pending?.confirmation?.planId || pending?.planId;
    if (!planId) {
      return null;
    }
    set({ streaming: true, stage: '正在提交修改…' });
    try {
      const result = await confirmPlan(planId);
      const assistantMsg: ChatMessage = {
        id: nextId(),
        role: 'assistant',
        content: result.message || '已提交。',
        time: new Date().toISOString(),
      };
      set({
        messages: pushMessage(get().messages, assistantMsg),
        pendingConfirm: null,
        streaming: false,
        stage: null,
        lastFocus: result.focus ?? get().lastFocus,
        committedTripId:
          result.status === 'COMPLETED' && result.tripId ? result.tripId : get().committedTripId,
      });
      return result;
    } catch (e: any) {
      const errMsg: ChatMessage = {
        id: nextId(),
        role: 'assistant',
        content: `⚠ 提交失败：${e.message}`,
        time: new Date().toISOString(),
      };
      set({ messages: pushMessage(get().messages, errMsg), streaming: false, stage: null });
      return null;
    }
  },

  cancel: async () => {
    const pending = get().pendingConfirm;
    const planId = pending?.confirmation?.planId || pending?.planId;
    set({ pendingConfirm: null });
    if (!planId) return;
    try {
      const result = await cancelPlan(planId);
      const assistantMsg: ChatMessage = {
        id: nextId(),
        role: 'assistant',
        content: result.message || '已取消。',
        time: new Date().toISOString(),
      };
      set({ messages: pushMessage(get().messages, assistantMsg) });
    } catch {
      // 忽略取消失败
    }
  },

  restorePending: (result) => set({ pendingConfirm: result, lastFocus: result.focus ?? null }),

  reset: () => {
    streamSeq += 1;
    closeFn?.();
    closeFn = null;
    set({ messages: [], streaming: false, stage: null, pendingConfirm: null, lastFocus: null, committedTripId: null });
  },

  clearCommitted: () => set({ committedTripId: null }),
}));
