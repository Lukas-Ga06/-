import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { streamAgentChat } from './agent';
import { useAuthStore } from '../store/authStore';
import type { UserInfo } from '../types/trip';

const DEMO_USER: UserInfo = { id: 1, username: 'demo', nickname: '演示用户' };

/** 最小 EventSource 替身：记录连接 URL，并允许测试手动触发事件 */
class FakeEventSource {
  static CLOSED = 2;
  static instances: FakeEventSource[] = [];

  static last(): FakeEventSource {
    return FakeEventSource.instances[FakeEventSource.instances.length - 1];
  }

  readonly url: string;
  readyState = 0;
  onerror: (() => void) | null = null;

  private listeners = new Map<string, ((e: MessageEvent) => void)[]>();

  constructor(url: string) {
    this.url = url;
    FakeEventSource.instances.push(this);
  }

  addEventListener(name: string, fn: (e: MessageEvent) => void) {
    const list = this.listeners.get(name) ?? [];
    list.push(fn);
    this.listeners.set(name, list);
  }

  close() {
    this.readyState = FakeEventSource.CLOSED;
  }

  emit(name: string, data: unknown) {
    const event = { data: JSON.stringify(data) } as MessageEvent;
    (this.listeners.get(name) ?? []).forEach((fn) => fn(event));
  }
}

describe('streamAgentChat', () => {
  beforeEach(() => {
    FakeEventSource.instances = [];
    vi.stubGlobal('EventSource', FakeEventSource);
    useAuthStore.getState().setAuth('token-123', DEMO_USER);
  });

  afterEach(() => {
    vi.unstubAllGlobals();
    useAuthStore.getState().logout();
  });

  it('把消息、行程与多模态上下文编码进查询参数，并带上 token', () => {
    streamAgentChat(
      {
        message: '明天下雨，把户外景点换掉',
        tripId: 7,
        currentDay: 2,
        selectedElement: { type: 'itinerary_item', id: 42, title: '外滩' },
        mapCenter: { lat: 31.23, lng: 121.47 },
      },
      {},
    );

    const url = new URL(FakeEventSource.last().url, 'http://localhost');
    expect(url.pathname).toBe('/api/agent/stream');
    expect(url.searchParams.get('message')).toBe('明天下雨，把户外景点换掉');
    expect(url.searchParams.get('tripId')).toBe('7');
    expect(url.searchParams.get('currentDay')).toBe('2');
    expect(url.searchParams.get('selId')).toBe('42');
    expect(url.searchParams.get('selTitle')).toBe('外滩');
    expect(url.searchParams.get('lat')).toBe('31.23');
    expect(url.searchParams.get('token')).toBe('token-123');
  });

  it('普通事件交给 onEvent，complete 事件收尾并关闭连接', () => {
    const onEvent = vi.fn();
    const onComplete = vi.fn();
    streamAgentChat({ message: '你好' }, { onEvent, onComplete });

    const es = FakeEventSource.last();
    es.emit('intent', { intent: 'PLAN_TRIP' });
    expect(onEvent).toHaveBeenCalledWith('intent', { intent: 'PLAN_TRIP' });

    es.emit('complete', { status: 'COMPLETED', message: '好的' });
    expect(onComplete).toHaveBeenCalledWith({ status: 'COMPLETED', message: '好的' });
    expect(es.readyState).toBe(FakeEventSource.CLOSED);
  });

  it('error 事件把后端的错误信息交给 onError', () => {
    const onError = vi.fn();
    streamAgentChat({ message: '你好' }, { onError });

    FakeEventSource.last().emit('error', { message: '规划失败' });
    expect(onError).toHaveBeenCalledWith('规划失败');
  });

  it('连接中断（没收到 complete）也会回调 onError 并关闭连接', () => {
    const onError = vi.fn();
    const onComplete = vi.fn();
    streamAgentChat({ message: '你好' }, { onError, onComplete });

    const es = FakeEventSource.last();
    es.readyState = 0; // CONNECTING：旧实现下这里不会回调，前端会一直停在“生成中”
    es.onerror?.();

    expect(onError).toHaveBeenCalledTimes(1);
    expect(onError.mock.calls[0][0]).toContain('连接已中断');
    expect(onComplete).not.toHaveBeenCalled();
    expect(es.readyState).toBe(FakeEventSource.CLOSED);
  });

  it('complete 之后连接报错不会重复回调', () => {
    const onError = vi.fn();
    streamAgentChat({ message: '你好' }, { onError });

    const es = FakeEventSource.last();
    es.emit('complete', { status: 'COMPLETED', message: '好的' });
    es.onerror?.();

    expect(onError).not.toHaveBeenCalled();
  });

  it('返回的关闭函数会断开连接', () => {
    const close = streamAgentChat({ message: '你好' }, {});
    close();
    expect(FakeEventSource.last().readyState).toBe(FakeEventSource.CLOSED);
  });
});
