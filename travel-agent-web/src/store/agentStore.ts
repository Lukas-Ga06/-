import { create } from 'zustand';
import { persist, createJSONStorage } from 'zustand/middleware';
import { cancelPlan, clearConversation, confirmPlan, streamAgentChat, streamAgentChatPost } from '../api/agent';
import type { AgentFocus, AgentResult, ChatMessage, SelectedElement } from '../types/agent';

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
   * 会话记忆标识：sessionStorage 持久化，跨页面切换存活（规划页 → 行程页是同一对话）。
   * reset() 只清界面消息、不动它；只有 newConversation() 才开新会话。
   */
  conversationId: string | null;
  send: (payload: SendPayload) => void;
  confirm: () => Promise<AgentResult | null>;
  cancel: () => Promise<void>;
  /** 刷新后用 planId 取回的方案重新挂起确认弹窗 */
  restorePending: (result: AgentResult) => void;
  reset: () => void;
  /** 显式开新会话：清消息 + 换 conversationId（同时清掉服务端该会话历史） */
  newConversation: () => void;
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

function newConversationId(): string {
  if (typeof crypto !== 'undefined' && typeof crypto.randomUUID === 'function') {
    return crypto.randomUUID();
  }
  return `c-${Date.now()}-${Math.random().toString(36).slice(2, 10)}`;
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
  conversationId: null,

  send: (payload) => {
    if (get().streaming || !payload.message.trim()) {
      return;
    }
    closeFn?.();
    closeFn = null;
    const seq = ++streamSeq;
    // 会话记忆：首次发送时生成，之后一直复用（跨页面切换保持同一对话）
    const conversationId = get().conversationId ?? newConversationId();
    if (conversationId !== get().conversationId) {
      set({ conversationId });
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
    });

    const streamFn = payload.transport === 'post' ? streamAgentChatPost : streamAgentChat;
    streamFn({ ...payload, conversationId }, {
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

  newConversation: () => {
    const previous = get().conversationId;
    streamSeq += 1;
    closeFn?.();
    closeFn = null;
    set({
      messages: [],
      streaming: false,
      stage: null,
      pendingConfirm: null,
      lastFocus: null,
      committedTripId: null,
      conversationId: newConversationId(),
    });
    if (previous) {
      // 服务端历史清理失败不影响本地开新会话（历史 7 天后也会被定期清理）
      Promise.resolve(clearConversation(previous)).catch(() => {});
    }
  },

  clearCommitted: () => set({ committedTripId: null }),
}),
    {
      name: 'travel-agent-conversation',
      // sessionStorage：跨页面切换（规划页 → 行程页）保持同一对话，关标签页即结束
      storage: createJSONStorage(() => sessionStorage),
      // 只持久化会话标识；消息是纯界面状态，不随标签页恢复
      partialize: (state) => ({ conversationId: state.conversationId }),
    },
  ),
);
