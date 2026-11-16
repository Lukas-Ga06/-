import { request } from './client';
import { getAccessTicket } from './ticket';
import type { AgentResult, SelectedElement } from '../types/agent';

export interface AgentStreamParams {
  message: string;
  tripId?: number | null;
  currentDay?: number | null;
  selectedElement?: SelectedElement | null;
  mapCenter?: { lat: number; lng: number } | null;
  /** 会话记忆标识（UUID）：后端据此回放历史做指代消解 */
  conversationId?: string | null;
}

export interface AgentStreamHandlers {
  onEvent?: (event: string, data: any) => void;
  onComplete?: (result: AgentResult) => void;
  onError?: (message: string) => void;
}

const EVENT_NAMES = [
  'start', 'intent', 'planning', 'tool_call', 'tool_result',
  'rag_result', 'plan_update', 'focus', 'confirmation', 'complete', 'error',
];

/**
 * SSE 流式对话。EventSource 无法自定义 Header，凭证走查询参数 ——
 * 用的是短期只读票据（api/ticket.ts），不是登录 JWT。
 * 返回关闭函数（票据获取是异步的，因此本函数返回 Promise）。
 */
export async function streamAgentChat(
  params: AgentStreamParams,
  handlers: AgentStreamHandlers,
): Promise<() => void> {
  // 票据按用途签发：SSE 必须用 agent-stream，照片原图用 media-read，不能互相挪用
  const ticket = await getAccessTicket('agent-stream');
  const qs = new URLSearchParams();
  qs.set('message', params.message);
  if (params.tripId) qs.set('tripId', String(params.tripId));
  if (params.currentDay) qs.set('currentDay', String(params.currentDay));
  if (params.conversationId) qs.set('conversationId', params.conversationId);
  if (params.selectedElement?.id) {
    qs.set('selType', params.selectedElement.type || 'itinerary_item');
    qs.set('selId', String(params.selectedElement.id));
    if (params.selectedElement.title) qs.set('selTitle', params.selectedElement.title);
  }
  if (params.mapCenter) {
    qs.set('lat', String(params.mapCenter.lat));
    qs.set('lng', String(params.mapCenter.lng));
  }
  qs.set('ticket', ticket);

  const es = new EventSource(`/api/agent/stream?${qs.toString()}`);
  let finished = false;

  /** 终止一次流：保证 onComplete / onError 只回调一次，并关闭连接 */
  const finish = (callback?: () => void) => {
    if (finished) {
      return;
    }
    finished = true;
    es.close();
    callback?.();
  };

  EVENT_NAMES.forEach((name) => {
    es.addEventListener(name, (e: MessageEvent) => {
      let data: any = null;
      try {
        data = e.data ? JSON.parse(e.data) : null;
      } catch {
        data = e.data;
      }
      if (name === 'complete') {
        finish(() => handlers.onComplete?.(data as AgentResult));
      } else if (name === 'error') {
        // 服务端主动报错：Agent 随后还会发一条 complete，这里只提示、不终止
        handlers.onError?.(data?.message || 'Agent 执行出错');
      } else {
        handlers.onEvent?.(name, data);
      }
    });
  });

  /**
   * 连接层错误：服务端 180s 超时、网络中断等情况下浏览器只会触发 onerror。
   * 旧实现只在 readyState === CLOSED 时才回调，导致既不 complete 也不 error，
   * 调用方的 streaming 状态永远停在 true（输入框被永久禁用）。
   */
  es.onerror = () => {
    finish(() => handlers.onError?.('与 Agent 的连接已中断，请重试'));
  };

  return () => finish();
}

/**
 * POST 版 SSE 流：结构化规划表单（含参考攻略正文）专用。
 * EventSource 只能 GET、消息走 URL，长文本（攻略正文可上万字）放不下；
 * fetch POST 走请求体 + Cookie 认证，用 ReadableStream 手工解析 SSE 帧。
 * 返回关闭函数（语义与 streamAgentChat 一致）。
 */
export async function streamAgentChatPost(
  params: AgentStreamParams,
  handlers: AgentStreamHandlers,
): Promise<() => void> {
  const controller = new AbortController();
  let finished = false;
  const finish = (callback?: () => void) => {
    if (finished) {
      return;
    }
    finished = true;
    controller.abort();
    callback?.();
  };

  (async () => {
    try {
      const resp = await fetch('/api/agent/stream', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        credentials: 'same-origin',
        body: JSON.stringify({
          message: params.message,
          tripId: params.tripId ?? null,
          currentDay: params.currentDay ?? null,
          conversationId: params.conversationId ?? null,
          selectedElement: params.selectedElement ?? null,
          mapCenter: params.mapCenter ?? null,
        }),
        signal: controller.signal,
      });
      if (!resp.ok || !resp.body) {
        const text = await resp.text().catch(() => '');
        let msg = `请求失败（${resp.status}）`;
        try {
          const parsed = JSON.parse(text);
          if (parsed?.message) msg = parsed.message;
        } catch {
          // 非 JSON 响应体，保留默认提示
        }
        finish(() => handlers.onError?.(msg));
        return;
      }
      const reader = resp.body.getReader();
      const decoder = new TextDecoder();
      let buffer = '';
      const handleBlock = (block: string) => {
        let event = 'message';
        const dataLines: string[] = [];
        for (const line of block.split('\n')) {
          if (line.startsWith('event:')) {
            event = line.slice(6).trim();
          } else if (line.startsWith('data:')) {
            dataLines.push(line.slice(5).trimStart());
          }
        }
        const raw = dataLines.join('\n');
        if (!raw && event === 'message') {
          return;
        }
        let data: any = null;
        if (raw) {
          try {
            data = JSON.parse(raw);
          } catch {
            data = raw;
          }
        }
        if (event === 'complete') {
          finish(() => handlers.onComplete?.(data as AgentResult));
        } else if (event === 'error') {
          // 服务端主动报错：Agent 随后还会发一条 complete，这里只提示、不终止
          handlers.onError?.(data?.message || 'Agent 执行出错');
        } else {
          handlers.onEvent?.(event, data);
        }
      };
      for (;;) {
        const { done, value } = await reader.read();
        if (done) break;
        buffer += decoder.decode(value, { stream: true });
        let idx: number;
        while ((idx = buffer.indexOf('\n\n')) >= 0) {
          const block = buffer.slice(0, idx).replace(/\r/g, '');
          buffer = buffer.slice(idx + 2);
          handleBlock(block);
        }
      }
      // 连接关闭却没收到 complete：与 GET 版 onerror 语义一致
      finish(() => handlers.onError?.('与 Agent 的连接已中断，请重试'));
    } catch (e: any) {
      if (e?.name === 'AbortError') {
        finish();
        return;
      }
      finish(() => handlers.onError?.(e?.message || '无法建立与 Agent 的连接'));
    }
  })();

  return () => finish();
}

export function confirmPlan(planId: string): Promise<AgentResult> {
  return request<AgentResult>({ url: '/agent/confirm', method: 'post', data: { planId } });
}

export function cancelPlan(planId: string): Promise<AgentResult> {
  return request<AgentResult>({ url: '/agent/cancel', method: 'post', data: { planId } });
}

export function undoTrip(tripId: number): Promise<AgentResult> {
  return request<AgentResult>({ url: '/agent/undo', method: 'post', data: { tripId } });
}

/** 取回待确认方案：刷新页面后凭 planId 恢复确认弹窗 */
export function getPendingPlan(planId: string): Promise<AgentResult> {
  return request<AgentResult>({ url: `/agent/plans/${encodeURIComponent(planId)}` });
}

/** 开新会话时清掉服务端该会话的历史（失败可忽略，历史 7 天后也会被定期清理） */
export function clearConversation(conversationId: string): Promise<void> {
  return request<void>({
    url: '/agent/conversation/clear',
    method: 'post',
    data: { conversationId },
  });
}
