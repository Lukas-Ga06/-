import { request } from './client';
import { useAuthStore } from '../store/authStore';
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
 * SSE 流式对话。EventSource 无法自定义 Header，token 走查询参数。
 * 返回关闭函数。
 */
export function streamAgentChat(params: AgentStreamParams, handlers: AgentStreamHandlers): () => void {
  const token = useAuthStore.getState().token;
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
  if (token) qs.set('token', token);

  const es = new EventSource(`/api/agent/stream?${qs.toString()}`);

  EVENT_NAMES.forEach((name) => {
    es.addEventListener(name, (e: MessageEvent) => {
      let data: any = null;
      try {
        data = e.data ? JSON.parse(e.data) : null;
      } catch {
        data = e.data;
      }
      if (name === 'complete') {
        es.close();
        handlers.onComplete?.(data as AgentResult);
      } else if (name === 'error') {
        handlers.onError?.(data?.message || 'Agent 执行出错');
      } else {
        handlers.onEvent?.(name, data);
      }
    });
  });

  es.onerror = () => {
    if (es.readyState === EventSource.CLOSED) {
      handlers.onError?.('与 Agent 的连接已中断');
    }
    es.close();
  };

  return () => es.close();
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
