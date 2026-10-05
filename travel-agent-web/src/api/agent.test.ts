import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

// 用普通函数而不是 vi.fn：vitest 的 restoreMocks 会在每个用例前重置 mock 实现
vi.mock('./ticket', () => ({
  getAccessTicket: () => Promise.resolve('ticket-123'),
}));

import { streamAgentChat } from './agent';

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
  });

  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it('查询参数里带的是短期票据，不再是登录 JWT', async () => {
    await streamAgentChat(
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
    expect(url.searchParams.get('ticket')).toBe('ticket-123');
    expect(url.searchParams.get('token')).toBeNull();
  });

  it('普通事件交给 onEvent，complete 事件收尾并关闭连接', async () => {
    const onEvent = vi.fn();
    const onComplete = vi.fn();
    await streamAgentChat({ message: '你好' }, { onEvent, onComplete });

    const es = FakeEventSource.last();
    es.emit('intent', { intent: 'PLAN_TRIP' });
    expect(onEvent).toHaveBeenCalledWith('intent', { intent: 'PLAN_TRIP' });

    es.emit('complete', { status: 'COMPLETED', message: '好的' });
    expect(onComplete).toHaveBeenCalledWith({ status: 'COMPLETED', message: '好的' });
    expect(es.readyState).toBe(FakeEventSource.CLOSED);
  });

  it('error 事件把后端的错误信息交给 onError', async () => {
    const onError = vi.fn();
    await streamAgentChat({ message: '你好' }, { onError });

    FakeEventSource.last().emit('error', { message: '规划失败' });
    expect(onError).toHaveBeenCalledWith('规划失败');
  });

  it('返回的关闭函数会断开连接', async () => {
    const close = await streamAgentChat({ message: '你好' }, {});
    close();
    expect(FakeEventSource.last().readyState).toBe(FakeEventSource.CLOSED);
  });
});
