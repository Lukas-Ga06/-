import { create } from 'zustand';
import { persist, createJSONStorage } from 'zustand/middleware';
import {
  cancelPlan,
  clearConversation,
  confirmPlan,
  streamAgentChat,
  streamAgentChatPost,
} from '../api/agent';
import type {
  AgentFocus,
  AgentResult,
  ChatMessage,
  ExecutionSummary,
  SelectedElement,
} from '../types/agent';
import { stageText, type ActivityEvent } from './stageText';
import { TRIP_CONFLICT_MESSAGE, isTripConflict } from '../lib/tripConflict';

export interface SendPayload {
  message: string;
  tripId?: number | null;
  currentDay?: number | null;
  selectedElement?: SelectedElement | null;
  mapCenter?: { lat: number; lng: number } | null;
  /** 会话标识：由 store 自动生成/复用，调用方无需传入 */
  conversationId?: string | null;
  /**
   * 传输通道：默认 GET（EventSource，消息走 URL，≤500 字）；
   * 'post' 走 fetch SSE（请求体，≤2 万字），结构化规划表单带攻略正文时必须用它。
   */
  transport?: 'get' | 'post';
  /** 聊天记录里展示给用户看的文案；长文本（如攻略正文）提交时用它换成简短摘要 */
  displayText?: string;
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
  /**
   * 会话作用域（阶段 C，§8.6）：`assistant` / `planner` / `trip:<id>`。
   * 不同作用域的消息、待确认方案与服务端会话标识互相隔离，切换回来能接着看原来的对话。
   */
  scope: string;
  /** 各作用域的服务端会话标识：同一作用域复用，跨作用域互不串记忆 */
  conversations: Record<string, string | null>;
  /**
   * 会话记忆标识：当前作用域的会话 id，sessionStorage 持久化，跨页面切换存活
   * （规划页 → 行程页同一对话）。reset() 只清界面消息、不动它；只有 newConversation() 才开新会话。
   */
  conversationId: string | null;
  /** 本轮运行的追踪标识（SSE run 事件下发，send 开始时清空） */
  currentRun: { runId: string } | null;
  /** 确认成功后的日程/预算/足迹同步执行结果；ChatPanel 据此渲染 ExecutionResultPanel */
  lastExecution: ExecutionSummary | null;
  /** Agent 过程事件（追加式时间线，窗口保留最近 40 条；send 开始时清空） */
  activity: ActivityEvent[];
  /**
   * 本轮的过程警告（服务端 error 事件）。语义是「有模块没过，但流还在跑」，
   * 因此既不终止流也不解锁输入；结果出来后与结论并列展示，避免无条件「成功」。
   */
  warnings: string[];
  send: (payload: SendPayload) => void;
  /** 切换会话作用域：保存当前作用域的对话，载入目标作用域的对话（阶段 C 会话隔离） */
  setScope: (scope: string) => void;
  /** 用户主动停止本轮生成：关闭连接、保留已获得的过程记录、立刻恢复输入 */
  abort: () => void;
  /**
   * 确认待确认方案。acknowledgeIssues=true 表示用户已显式接受未解决的高风险问题
   * （Batch 13）；缺省/false 时请求体与旧契约完全一致。
   */
  confirm: (acknowledgeIssues?: boolean) => Promise<AgentResult | null>;
  cancel: () => Promise<void>;
  /** 刷新后用 planId 取回的方案重新挂起确认弹窗 */
  restorePending: (result: AgentResult) => void;
  reset: () => void;
  /** 显式开新会话：清消息 + 换 conversationId（同时清掉服务端该会话历史） */
  newConversation: () => void;
  clearCommitted: () => void;
  /** 覆盖执行同步结果（重试成功后由 ChatPanel 回写，收到新 execution 前面板一直显示） */
  setLastExecution: (execution: ExecutionSummary | null) => void;
}

let closeFn: (() => void) | null = null;
let msgSeq = 0;
/** 流式请求序号：票据获取是异步的，用序号丢弃"已被 reset/新请求取代"的回调 */
let streamSeq = 0;

/** 默认作用域：/assistant 页面 */
export const DEFAULT_SCOPE = 'assistant';

interface ScopeSnapshot {
  messages: ChatMessage[];
  conversationId: string | null;
  pendingConfirm: AgentResult | null;
}

/**
 * 作用域对话快照（纯内存）：隔离不同页面的聊天上下文，避免「在行程 A 里说的话
 * 出现在行程 B 的助手面板里」。服务端历史仍按 conversationId 隔离，这里只是本地视图。
 * 不放 sessionStorage：刷新后界面本来就从空对话开始，服务端记忆由 conversations 恢复。
 */
const scopeCache = new Map<string, ScopeSnapshot>();

function nextId(): string {
  msgSeq += 1;
  return `m-${Date.now()}-${msgSeq}`;
}

function pushMessage(list: ChatMessage[], msg: ChatMessage): ChatMessage[] {
  return [...list, msg];
}

/** 过程时间线窗口：SSE 事件可能很密集，只保留最近 40 条 */
const ACTIVITY_WINDOW = 40;

function appendActivity(list: ActivityEvent[], event: string, data: any): ActivityEvent[] {
  const next: ActivityEvent[] = [...list, { event, data, time: Date.now() }];
  return next.length > ACTIVITY_WINDOW ? next.slice(next.length - ACTIVITY_WINDOW) : next;
}

function newConversationId(): string {
  if (typeof crypto !== 'undefined' && typeof crypto.randomUUID === 'function') {
    return crypto.randomUUID();
  }
  return `c-${Date.now()}-${Math.random().toString(36).slice(2, 10)}`;
}

/**
 * 幂等键（P0-01）：一条命令一个，随本次连接生命周期固定。
 * EventSource 断线自动重连会复用同一个 URL（也就复用同一个 requestId），
 * 服务端按 (userId, requestId) 去重，保证备忘/日程/账单不会因为重连被写两次。
 */
function newRequestId(): string {
  if (typeof crypto !== 'undefined' && typeof crypto.randomUUID === 'function') {
    return crypto.randomUUID();
  }
  return `r-${Date.now()}-${Math.random().toString(36).slice(2, 10)}`;
}

export const useAgentStore = create<AgentState>()(
  persist(
    (set, get) => ({
      messages: [],
      streaming: false,
      stage: null,
      pendingConfirm: null,
      lastFocus: null,
      committedTripId: null,
      scope: DEFAULT_SCOPE,
      conversations: {},
      conversationId: null,
      currentRun: null,
      lastExecution: null,
      activity: [],
      warnings: [],

      send: (payload) => {
        if (get().streaming || !payload.message.trim()) {
          return;
        }
        closeFn?.();
        closeFn = null;
        const seq = ++streamSeq;
        // 会话记忆：当前作用域首次发送时生成，之后一直复用（跨页面切换保持同一对话）
        const scope = get().scope;
        const conversationId = get().conversationId ?? newConversationId();
        if (conversationId !== get().conversationId) {
          set((s) => ({
            conversationId,
            conversations: { ...s.conversations, [scope]: conversationId },
          }));
        }
        const userMsg: ChatMessage = {
          id: nextId(),
          role: 'user',
          content: payload.displayText ?? payload.message,
          time: new Date().toISOString(),
        };
        set({
          messages: pushMessage(get().messages, userMsg),
          streaming: true,
          stage: '连接 Agent…',
          pendingConfirm: null,
          currentRun: null,
          activity: [],
          warnings: [],
        });

        const streamFn = payload.transport === 'post' ? streamAgentChatPost : streamAgentChat;
        /** 迟到的旧流回调一律丢弃：不让上一轮的结果串进当前会话（P0-02） */
        const stale = () => seq !== streamSeq;
        /** 本次命令的幂等键：整条连接（含自动重连）复用同一个值（P0-01） */
        const requestId = newRequestId();
        streamFn(
          { ...payload, conversationId, requestId },
          {
            onEvent: (event, data) => {
              if (stale()) return;
              const text = stageText(event, data);
              if (text) set({ stage: text });
              if (event === 'focus' && data) {
                set({ lastFocus: data as AgentFocus });
              }
              if (event === 'run' && data?.runId) {
                // 本轮运行的追踪标识（V2.2 运行记录的锚点）
                set({ currentRun: { runId: String(data.runId) } });
              }
              set((s) => ({ activity: appendActivity(s.activity, event, data) }));
            },
            onWarning: (warning) => {
              if (stale()) return;
              // 只登记，不结束本轮：真正的结论仍由随后的 complete 给出
              set((s) => ({
                warnings: s.warnings.includes(warning.message)
                  ? s.warnings
                  : [...s.warnings, warning.message],
                activity: appendActivity(s.activity, 'warning', warning),
              }));
            },
            onCancelled: (data) => {
              if (stale()) return;
              set((s) => ({
                streaming: false,
                stage: null,
                activity: appendActivity(s.activity, 'cancelled', data ?? {}),
              }));
            },
            onComplete: (result) => {
              if (stale()) return;
              const assistantMsg: ChatMessage = {
                id: nextId(),
                role: 'assistant',
                content: result.message || '（无返回内容）',
                time: new Date().toISOString(),
                // 方案的知识依据（P1）；旧后端/无引用时保持空数组，前端不编造
                citations: Array.isArray(result.citations) ? result.citations : [],
                // 结构化结果卡（阶段 C）：状态、影响范围、审核与来源由 ResultCard 渲染
                result,
              };
              set((s) => ({
                messages: pushMessage(s.messages, assistantMsg),
                streaming: false,
                stage: null,
                pendingConfirm: result.status === 'NEED_CONFIRMATION' ? result : null,
                lastFocus: result.focus ?? s.lastFocus,
                committedTripId:
                  result.status === 'COMPLETED' && result.tripId && !payload.tripId
                    ? result.tripId
                    : s.committedTripId,
                activity: appendActivity(s.activity, 'complete', { status: result.status }),
              }));
            },
            onError: (errorText) => {
              if (stale()) return;
              const errMsg: ChatMessage = {
                id: nextId(),
                role: 'assistant',
                content: `⚠ ${errorText}`,
                time: new Date().toISOString(),
                // 合成结果：让失败在结果卡里有明确的「失败 + 重试」语义，而不是一段红字
                result: { status: 'FAILED', message: errorText },
              };
              set((s) => ({
                messages: pushMessage(s.messages, errMsg),
                streaming: false,
                stage: null,
                activity: appendActivity(s.activity, 'error', { message: errorText }),
              }));
            },
          },
        )
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
            set((s) => ({
              messages: pushMessage(s.messages, errMsg),
              streaming: false,
              stage: null,
              activity: appendActivity(s.activity, 'error', {
                message: e?.message || '无法建立与 Agent 的连接',
              }),
            }));
          });
      },

      abort: () => {
        if (!get().streaming) {
          return;
        }
        // 先自增序号：即使旧连接此刻还有排队中的回调，也一律按「迟到事件」丢弃
        streamSeq += 1;
        closeFn?.();
        closeFn = null;
        const runId = get().currentRun?.runId ?? null;
        const stopMsg: ChatMessage = {
          id: nextId(),
          role: 'assistant',
          content: '⏹ 已停止生成。已获得的过程记录保留在运行详情里，你可以补充需求后重新发送。',
          time: new Date().toISOString(),
          // 合成结果：界面用「已停止」结果卡与普通回答区分（§10.1 cancelled 语义）
          result: {
            status: 'CANCELLED',
            message: '已停止生成。已获得的内容保留，补充需求后可以重新发送。',
          },
        };
        set((s) => ({
          messages: pushMessage(s.messages, stopMsg),
          streaming: false,
          stage: null,
          activity: appendActivity(s.activity, 'cancelled', { runId }),
        }));
      },

      setScope: (next) => {
        if (next === get().scope) {
          return;
        }
        const { scope, messages, conversationId, pendingConfirm, conversations } = get();
        // 先存当前作用域，再切：来回切换时能接着看原来的对话
        scopeCache.set(scope, { messages, conversationId, pendingConfirm });
        // 在途流必须丢弃：旧作用域的回调不能写进新作用域的对话
        streamSeq += 1;
        closeFn?.();
        closeFn = null;
        const cached = scopeCache.get(next);
        set({
          scope: next,
          messages: cached?.messages ?? [],
          pendingConfirm: cached?.pendingConfirm ?? null,
          conversationId: conversations[next] ?? null,
          streaming: false,
          stage: null,
          lastFocus: null,
          committedTripId: null,
          currentRun: null,
          activity: [],
          warnings: [],
          lastExecution: null,
        });
      },

      confirm: async (acknowledgeIssues?: boolean) => {
        const pending = get().pendingConfirm;
        const planId = pending?.confirmation?.planId || pending?.planId;
        if (!planId) {
          return null;
        }
        set({ streaming: true, stage: '正在提交修改…' });
        try {
          // 内容指纹绑定（V2.2 FR-08）：把待确认方案自带的 contentHash 原样回传，
          // 后端据此校验"确认的就是你看到的这份内容"；旧方案没有指纹时走旧校验。
          const contentHash = pending?.confirmation?.contentHash;
          // 只有用户勾选「接受风险」时才带上 acknowledgeIssues，旧后端请求体零变化。
          const result = acknowledgeIssues
            ? await confirmPlan(planId, contentHash, true)
            : await confirmPlan(planId, contentHash);
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
              result.status === 'COMPLETED' && result.tripId
                ? result.tripId
                : get().committedTripId,
            lastExecution: result.execution ?? null,
          });
          return result;
        } catch (e: any) {
          // 409 = 行程在别处被改过：本地这份方案的前提已失效，不能只提示「提交失败」，
          // 要明确告诉用户是冲突、以及需要刷新/重新生成后再确认（阶段 D）。
          const content = isTripConflict(e)
            ? `⚠ ${TRIP_CONFLICT_MESSAGE}，本次提交未生效；已为你保留方案，刷新行程后重新确认即可`
            : `⚠ 提交失败：${e.message}`;
          const errMsg: ChatMessage = {
            id: nextId(),
            role: 'assistant',
            content,
            time: new Date().toISOString(),
          };
          set({ messages: pushMessage(get().messages, errMsg), streaming: false, stage: null });
          return null;
        }
      },

      cancel: async () => {
        const pending = get().pendingConfirm;
        const planId = pending?.confirmation?.planId || pending?.planId;
        if (!planId) {
          set({ pendingConfirm: null });
          return;
        }
        set({ streaming: true, stage: '正在取消方案…' });
        try {
          const result = await cancelPlan(planId);
          const assistantMsg: ChatMessage = {
            id: nextId(),
            role: 'assistant',
            content: result.message || '已取消。',
            time: new Date().toISOString(),
          };
          // 只有服务端确认取消成功，才把方案从界面上摘掉（P1-07）
          set({
            pendingConfirm: null,
            streaming: false,
            stage: null,
            messages: pushMessage(get().messages, assistantMsg),
          });
        } catch (e: any) {
          // 取消失败：方案必须留在原地，planId 不能丢，否则用户再也点不到它
          const errMsg: ChatMessage = {
            id: nextId(),
            role: 'assistant',
            content: `⚠ 取消失败：${e?.message || '网络错误'}。方案仍在等待确认，可以再试一次。`,
            time: new Date().toISOString(),
          };
          set({
            pendingConfirm: pending,
            streaming: false,
            stage: null,
            messages: pushMessage(get().messages, errMsg),
          });
        }
      },

      restorePending: (result) => set({ pendingConfirm: result, lastFocus: result.focus ?? null }),

      reset: () => {
        streamSeq += 1;
        closeFn?.();
        closeFn = null;
        // 清掉当前作用域的本地快照，避免下次切回来又看到刚清空的旧消息
        scopeCache.delete(get().scope);
        set({
          messages: [],
          streaming: false,
          stage: null,
          pendingConfirm: null,
          lastFocus: null,
          committedTripId: null,
          currentRun: null,
          activity: [],
          warnings: [],
          lastExecution: null,
        });
      },

      newConversation: () => {
        const previous = get().conversationId;
        const scope = get().scope;
        const freshId = newConversationId();
        streamSeq += 1;
        closeFn?.();
        closeFn = null;
        scopeCache.delete(scope);
        set((s) => ({
          messages: [],
          streaming: false,
          stage: null,
          pendingConfirm: null,
          lastFocus: null,
          committedTripId: null,
          currentRun: null,
          activity: [],
          warnings: [],
          lastExecution: null,
          conversationId: freshId,
          conversations: { ...s.conversations, [scope]: freshId },
        }));
        if (previous) {
          // 服务端历史清理失败不影响本地开新会话（历史 7 天后也会被定期清理）
          Promise.resolve(clearConversation(previous)).catch(() => {});
        }
      },

      clearCommitted: () => set({ committedTripId: null }),

      setLastExecution: (execution) => set({ lastExecution: execution }),
    }),
    {
      name: 'travel-agent-conversation',
      // sessionStorage：跨页面切换（规划页 → 行程页）保持同一对话，关标签页即结束
      storage: createJSONStorage(() => sessionStorage),
      // 只持久化会话标识与作用域；消息是纯界面状态，不随标签页恢复
      partialize: (state) => ({ scope: state.scope, conversations: state.conversations }),
    },
  ),
);
