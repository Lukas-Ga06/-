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

  /** 派发一个没有 data 的事件：模拟 EventSource 的连接层失败 */
  emitRaw(name: string) {
    (this.listeners.get(name) ?? []).forEach((fn) => fn({} as MessageEvent));
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

  it('requestId 进 URL：EventSource 自动重连复用同一 URL，服务端据此只写一次', async () => {
    await streamAgentChat({ message: '记一下明天办签证', requestId: 'req-abc-123' }, {});

    const url = new URL(FakeEventSource.last().url, 'http://localhost');
    expect(url.searchParams.get('requestId')).toBe('req-abc-123');
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

  it('error 事件是过程警告：交给 onWarning，不终止本次流', async () => {
    const onWarning = vi.fn();
    const onError = vi.fn();
    const onComplete = vi.fn();
    await streamAgentChat({ message: '你好' }, { onWarning, onError, onComplete });

    const es = FakeEventSource.last();
    es.emit('error', { message: '规划失败', code: 'PLANNER_BUDGET' });
    expect(onWarning).toHaveBeenCalledWith({ message: '规划失败', code: 'PLANNER_BUDGET' });
    // 关键：警告不能当成终止错误，连接仍然打开，随后的 complete 还会到达
    expect(onError).not.toHaveBeenCalled();
    expect(es.readyState).not.toBe(FakeEventSource.CLOSED);

    es.emit('complete', { status: 'COMPLETED', message: '降级完成' });
    expect(onComplete).toHaveBeenCalledWith({ status: 'COMPLETED', message: '降级完成' });
  });

  it('没有 onWarning 通道时，error 事件降级成普通事件，而不是终止错误', async () => {
    const onEvent = vi.fn();
    const onError = vi.fn();
    await streamAgentChat({ message: '你好' }, { onEvent, onError });

    FakeEventSource.last().emit('error', { message: '规划失败' });
    expect(onEvent).toHaveBeenCalledWith('error', { message: '规划失败' });
    expect(onError).not.toHaveBeenCalled();
  });

  it('EventSource 的连接层 error（没有 data）不当作业务警告', async () => {
    const onWarning = vi.fn();
    const onError = vi.fn();
    await streamAgentChat({ message: '你好' }, { onWarning, onError });

    const es = FakeEventSource.last();
    // 原生 EventSource 的连接失败也是名为 error 的事件，但没有 data 字段
    es.emitRaw('error');
    expect(onWarning).not.toHaveBeenCalled();
    // 连接层失败由 onerror 走终止路径
    es.onerror?.();
    expect(onError).toHaveBeenCalled();
  });

  it('cancelled 事件终止本次流并回调 onCancelled', async () => {
    const onCancelled = vi.fn();
    const onComplete = vi.fn();
    await streamAgentChat({ message: '你好' }, { onCancelled, onComplete });

    const es = FakeEventSource.last();
    es.emit('cancelled', { runId: 'run-1' });
    expect(onCancelled).toHaveBeenCalledWith({ runId: 'run-1' });
    expect(es.readyState).toBe(FakeEventSource.CLOSED);
  });

  it('返回的关闭函数会断开连接', async () => {
    const close = await streamAgentChat({ message: '你好' }, {});
    close();
    expect(FakeEventSource.last().readyState).toBe(FakeEventSource.CLOSED);
  });

  it('任务图事件（delegate / recover / conflict）也在监听白名单内，不会被静默丢弃', async () => {
    const onEvent = vi.fn();
    await streamAgentChat({ message: '规划行程' }, { onEvent });

    const es = FakeEventSource.last();
    // EventSource 只能按登记过的事件名监听：漏登记的 SSE 事件浏览器直接丢弃，
    // 过程时间线就会缺这几类行（Batch 15 回归点）
    es.emit('delegate', {
      taskId: 't1',
      receiver: 'planner-agent',
      taskType: 'PLAN',
      dependsOn: [],
      attempt: 0,
    });
    es.emit('recover', {
      taskId: 't2',
      taskType: 'PREVIEW',
      replacesTaskId: 't1',
      attempt: 1,
      reason: '重派',
    });
    es.emit('conflict', {
      receiver: 'reflect-agent',
      taskType: 'REVIEW',
      missingDeps: ['t9'],
      reason: '前置未完成',
    });

    expect(onEvent).toHaveBeenCalledWith('delegate', expect.objectContaining({ taskType: 'PLAN' }));
    expect(onEvent).toHaveBeenCalledWith('recover', expect.objectContaining({ attempt: 1 }));
    expect(onEvent).toHaveBeenCalledWith(
      'conflict',
      expect.objectContaining({ taskType: 'REVIEW' }),
    );
  });
});
