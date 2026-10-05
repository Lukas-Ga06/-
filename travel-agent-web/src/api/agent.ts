import { request } from './client';
import { getAccessTicket } from './ticket';
import type { AgentResult, SelectedElement } from '../types/agent';

export interface AgentStreamParams {
  message: string;
  tripId?: number | null;
  currentDay?: number | null;
  selectedElement?: SelectedElement | null;
  mapCenter?: { lat: number; lng: number } | null;
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
  const ticket = await getAccessTicket();
  const qs = new URLSearchParams();
  qs.set('message', params.message);
  if (params.tripId) qs.set('tripId', String(params.tripId));
  if (params.currentDay) qs.set('currentDay', String(params.currentDay));
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

export function confirmPlan(planId: string): Promise<AgentResult> {
  return request<AgentResult>({ url: '/agent/confirm', method: 'post', data: { planId } });
}

export function cancelPlan(planId: string): Promise<AgentResult> {
  return request<AgentResult>({ url: '/agent/cancel', method: 'post', data: { planId } });
}

export function undoTrip(tripId: number): Promise<AgentResult> {
  return request<AgentResult>({ url: '/agent/undo', method: 'post', data: { tripId } });
}
