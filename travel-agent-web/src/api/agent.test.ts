import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

// 用普通函数而不是 vi.fn：vitest 的 restoreMocks 会在每个用例前重置 mock 实现。
// ticketMock 用 vi.hoisted 暴露给测试：续传取票据失败的分支需要它按调用次数失败。
const ticketMock = vi.hoisted(() => ({
  calls: 0,
  /** 从第 N 次调用起失败（默认永不失败；1 = 首连就失败） */
  failFromCall: Number.POSITIVE_INFINITY,
}));

vi.mock('./ticket', () => ({
  getAccessTicket: () => {
    ticketMock.calls += 1;
    if (ticketMock.calls >= ticketMock.failFromCall) {
      return Promise.reject(new Error('ticket unavailable'));
    }
    return Promise.resolve('ticket-123');
  },
}));

import { buildResumeUrl, parseEventId, streamAgentChat, streamAgentChatPost } from './agent';
import { useAgentStore } from '../store/agentStore';

beforeEach(() => {
  ticketMock.calls = 0;
  ticketMock.failFromCall = Number.POSITIVE_INFINITY;
});

/** 最小 EventSource 替身：记录连接 URL，并允许测试手动触发事件 */
class FakeEventSource {
  static CLOSED = 2;
  static instances: FakeEventSource[] = [];

  static last(): FakeEventSource {
    return FakeEventSource.instances[FakeEventSource.instances.length - 1];
  }

  readonly url: string;
  readyState = 0;
  onerror: ((e?: Event) => void) | null = null;

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

  /** 派发一帧；lastEventId 对应服务端的 `id: <runId>:<seq>` 行 */
  emit(name: string, data: unknown, lastEventId = '') {
    const event = { data: JSON.stringify(data), lastEventId } as unknown as MessageEvent;
    (this.listeners.get(name) ?? []).forEach((fn) => fn(event));
  }

  /** 派发一个没有 data 的事件：模拟 EventSource 的连接层失败 */
  emitRaw(name: string) {
    (this.listeners.get(name) ?? []).forEach((fn) => fn({} as MessageEvent));
  }
}

/** 等续传链路跑完（票据 Promise → 建立续传 EventSource） */
async function flushAsync(): Promise<void> {
  await new Promise((resolve) => setTimeout(resolve, 0));
  await new Promise((resolve) => setTimeout(resolve, 0));
}

describe('parseEventId / buildResumeUrl', () => {
  it('解析 `<runId>:<seq>`（按最后一个冒号切分）', () => {
    expect(parseEventId('run-1:1')).toEqual({ runId: 'run-1', seq: 1 });
    expect(parseEventId('3f8a-2b7c:17')).toEqual({ runId: '3f8a-2b7c', seq: 17 });
  });

  it('缺省 / 非法 id 返回 null（旧后端的帧不参与续传）', () => {
    expect(parseEventId('')).toBeNull();
    expect(parseEventId('   ')).toBeNull();
    expect(parseEventId(undefined)).toBeNull();
    expect(parseEventId(null)).toBeNull();
    expect(parseEventId('run-1')).toBeNull();
    expect(parseEventId('run-1:')).toBeNull();
    expect(parseEventId(':3')).toBeNull();
    expect(parseEventId('run-1:0')).toBeNull();
    expect(parseEventId('run-1:abc')).toBeNull();
    expect(parseEventId('run-1:-2')).toBeNull();
  });

  it('续传地址带 lastEventId 与票据', () => {
    expect(buildResumeUrl('run-1', 7)).toBe('/api/agent/runs/run-1/events?lastEventId=7');
    expect(buildResumeUrl('run/2', 7, 't-1')).toBe(
      '/api/agent/runs/run%2F2/events?lastEventId=7&ticket=t-1',
    );
  });
});

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

  it('V21：scope 进查询参数（GET 主路径），值为当前会话作用域', async () => {
    await streamAgentChat({ message: '把第二天的酒店换掉', tripId: 12, scope: 'trip:12' }, {});

    const url = new URL(FakeEventSource.last().url, 'http://localhost');
    expect(url.searchParams.get('scope')).toBe('trip:12');
  });

  it('V21：没有 scope 时不往 URL 里写空参数（旧后端也不该收到 scope=）', async () => {
    await streamAgentChat({ message: '你好' }, {});

    const url = new URL(FakeEventSource.last().url, 'http://localhost');
    expect(url.searchParams.has('scope')).toBe(false);
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

  it('正常流：帧 id 记录 runId / lastSeq，complete 只回调一次且不发起续传', async () => {
    const onEvent = vi.fn();
    const onComplete = vi.fn();
    const onError = vi.fn();
    await streamAgentChat({ message: '你好' }, { onEvent, onComplete, onError });

    const es = FakeEventSource.last();
    es.emit('run', { runId: 'run-1' }, 'run-1:1');
    es.emit('intent', { intent: 'PLAN_TRIP' }, 'run-1:2');
    es.emit('complete', { status: 'COMPLETED', message: '好的' }, 'run-1:3');

    expect(onEvent).toHaveBeenNthCalledWith(1, 'run', { runId: 'run-1' });
    expect(onEvent).toHaveBeenNthCalledWith(2, 'intent', { intent: 'PLAN_TRIP' });
    expect(onComplete).toHaveBeenCalledTimes(1);
    expect(onComplete).toHaveBeenCalledWith({ status: 'COMPLETED', message: '好的' });
    expect(onError).not.toHaveBeenCalled();
    expect(es.readyState).toBe(FakeEventSource.CLOSED);
    expect(FakeEventSource.instances).toHaveLength(1);
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

  it('回归：带 data 的 error 帧派发给 onerror 时不能当成连接中断（否则 complete 被丢弃）', async () => {
    const onWarning = vi.fn();
    const onError = vi.fn();
    const onComplete = vi.fn();
    await streamAgentChat({ message: '你好' }, { onWarning, onError, onComplete });

    const es = FakeEventSource.last();
    es.emit('error', { message: '规划失败', code: 'PLANNER_BUDGET' });
    // 真实浏览器会把同一个 `event: error` 帧再派发给 onerror，且是带 data 的 MessageEvent。
    // 这里按真实顺序模拟：先监听器、再 onerror。
    es.onerror?.({ data: '{"message":"规划失败"}' } as unknown as Event);

    expect(onWarning).toHaveBeenCalledWith({ message: '规划失败', code: 'PLANNER_BUDGET' });
    expect(onError).not.toHaveBeenCalled();
    expect(es.readyState).not.toBe(FakeEventSource.CLOSED);

    // 服务端在 error 之后仍会下发 complete：它必须还能到达
    es.emit('complete', { status: 'FAILED', message: '规划失败' });
    expect(onComplete).toHaveBeenCalledWith({ status: 'FAILED', message: '规划失败' });
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

  describe('断线续传（V28）', () => {
    it('连接层中断后自动续传：补发事件不重复、complete 只回调一次、不触发 onError', async () => {
      const onEvent = vi.fn();
      const onComplete = vi.fn();
      const onError = vi.fn();
      await streamAgentChat({ message: '规划行程' }, { onEvent, onComplete, onError });

      const first = FakeEventSource.last();
      first.emit('run', { runId: 'run-1' }, 'run-1:1');
      first.emit('intent', { intent: 'PLAN_TRIP' }, 'run-1:2');
      first.emit('tool_call', { tool: 'search' }, 'run-1:3');

      first.onerror?.();
      // 原连接必须被显式关闭：否则浏览器原生自动重连会把同一个规划请求再打一遍
      expect(first.readyState).toBe(FakeEventSource.CLOSED);
      await flushAsync();

      expect(FakeEventSource.instances).toHaveLength(2);
      const resume = FakeEventSource.last();
      const url = new URL(resume.url, 'http://localhost');
      expect(url.pathname).toBe('/api/agent/runs/run-1/events');
      expect(url.searchParams.get('lastEventId')).toBe('3');
      expect(url.searchParams.get('ticket')).toBe('ticket-123');

      // 服务端补发：与游标重叠的 seq 必须丢弃，后续事件照常分发
      resume.emit('tool_call', { tool: 'search' }, 'run-1:3');
      resume.emit('plan_update', { day: 2 }, 'run-1:4');
      resume.emit('complete', { status: 'COMPLETED', message: '完成' }, 'run-1:5');

      expect(onEvent).toHaveBeenCalledTimes(4);
      expect(onComplete).toHaveBeenCalledTimes(1);
      expect(onComplete).toHaveBeenCalledWith({ status: 'COMPLETED', message: '完成' });
      expect(onError).not.toHaveBeenCalled();
      expect(resume.readyState).toBe(FakeEventSource.CLOSED);
    });

    it('续传中的业务 error 帧仍是过程警告，随后的 complete 仍能到达', async () => {
      const onWarning = vi.fn();
      const onComplete = vi.fn();
      const onError = vi.fn();
      await streamAgentChat({ message: '规划行程' }, { onWarning, onComplete, onError });

      FakeEventSource.last().emit('run', { runId: 'run-4' }, 'run-4:1');
      FakeEventSource.last().onerror?.();
      await flushAsync();

      const resume = FakeEventSource.last();
      resume.emit('error', { message: '某模块失败', code: 'MODULE_FAILED' }, 'run-4:2');
      expect(onWarning).toHaveBeenCalledWith({ message: '某模块失败', code: 'MODULE_FAILED' });
      expect(onError).not.toHaveBeenCalled();
      expect(resume.readyState).not.toBe(FakeEventSource.CLOSED);

      resume.emit('complete', { status: 'COMPLETED', message: '降级完成' }, 'run-4:3');
      expect(onComplete).toHaveBeenCalledTimes(1);
    });

    it('续传连接失败：onError 只回调一次，且不再发起第二次续传', async () => {
      const onComplete = vi.fn();
      const onError = vi.fn();
      await streamAgentChat({ message: '你好' }, { onComplete, onError });

      FakeEventSource.last().emit('run', { runId: 'run-2' }, 'run-2:1');
      FakeEventSource.last().onerror?.();
      await flushAsync();

      expect(FakeEventSource.instances).toHaveLength(2);
      const resume = FakeEventSource.last();
      resume.onerror?.();
      // 重复的 onerror（网络层可能连续触发）也不能重复回调
      resume.onerror?.();

      expect(onError).toHaveBeenCalledTimes(1);
      expect(onError.mock.calls[0][0]).toContain('续传');
      expect(onComplete).not.toHaveBeenCalled();
      expect(resume.readyState).toBe(FakeEventSource.CLOSED);
      expect(FakeEventSource.instances).toHaveLength(2);
    });

    it('续传取票据失败：onError 只回调一次，不建立续传连接', async () => {
      const onError = vi.fn();
      await streamAgentChat({ message: '你好' }, { onError });

      FakeEventSource.last().emit('run', { runId: 'run-6' }, 'run-6:1');
      // 下一次 getAccessTicket（续传取票据）失败
      ticketMock.failFromCall = ticketMock.calls + 1;
      FakeEventSource.last().onerror?.();
      await flushAsync();

      expect(onError).toHaveBeenCalledTimes(1);
      expect(onError.mock.calls[0][0]).toContain('续传');
      expect(FakeEventSource.instances).toHaveLength(1);
    });

    it('续传收到 resume_timeout：按终止处理，文案说明可稍后查看运行记录', async () => {
      const onError = vi.fn();
      const onComplete = vi.fn();
      await streamAgentChat({ message: '你好' }, { onError, onComplete });

      FakeEventSource.last().emit('run', { runId: 'run-3' }, 'run-3:1');
      FakeEventSource.last().onerror?.();
      await flushAsync();

      const resume = FakeEventSource.last();
      resume.emit('resume_timeout', { runId: 'run-3' });

      expect(onError).toHaveBeenCalledTimes(1);
      expect(onError.mock.calls[0][0]).toContain('运行超时');
      expect(onError.mock.calls[0][0]).toContain('运行记录');
      expect(onComplete).not.toHaveBeenCalled();
      expect(resume.readyState).toBe(FakeEventSource.CLOSED);
    });

    it('没有 runId 的旧行为不变：中断直接 onError，不发起续传', async () => {
      const onError = vi.fn();
      await streamAgentChat({ message: '你好' }, { onError });

      const es = FakeEventSource.last();
      es.emit('intent', { intent: 'SMALL_TALK' });
      es.onerror?.();

      expect(onError).toHaveBeenCalledTimes(1);
      expect(onError).toHaveBeenCalledWith('与 Agent 的连接已中断，请重试');
      expect(es.readyState).toBe(FakeEventSource.CLOSED);
      expect(FakeEventSource.instances).toHaveLength(1);
    });
  });
});

const encoder = new TextEncoder();

/** 造一个 SSE 响应体；end='error' 模拟读到一半连接被重置 */
function sseBody(chunks: string[], end: 'close' | 'error' = 'close'): ReadableStream<Uint8Array> {
  let index = 0;
  return new ReadableStream<Uint8Array>({
    pull(controller) {
      if (index < chunks.length) {
        controller.enqueue(encoder.encode(chunks[index++]));
        return;
      }
      if (end === 'error') {
        controller.error(new Error('network reset'));
      } else {
        controller.close();
      }
    },
  });
}

/** 用 ReadableStream 冒充 fetch 的 SSE 响应（不连网） */
function stubFetchStream(chunks: string[], end: 'close' | 'error' = 'close') {
  const fetchMock = vi.fn(
    async () =>
      new Response(sseBody(chunks, end), {
        status: 200,
        headers: { 'Content-Type': 'text/event-stream' },
      }),
  );
  vi.stubGlobal('fetch', fetchMock);
  return fetchMock;
}

describe('streamAgentChatPost', () => {
  beforeEach(() => {
    FakeEventSource.instances = [];
    vi.stubGlobal('EventSource', FakeEventSource);
  });

  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it('解析 id 行并正常收尾：complete 只回调一次、不发起续传', async () => {
    stubFetchStream([
      'id: run-11:1\nevent: run\ndata: {"runId":"run-11"}\n\n',
      'id: run-11:2\nevent: complete\ndata: {"status":"COMPLETED","message":"ok"}\n\n',
    ]);
    const onEvent = vi.fn();
    const onComplete = vi.fn();
    const onError = vi.fn();

    await streamAgentChatPost({ message: '生成行程' }, { onEvent, onComplete, onError });
    await flushAsync();

    expect(onEvent).toHaveBeenCalledWith('run', { runId: 'run-11' });
    expect(onComplete).toHaveBeenCalledTimes(1);
    expect(onComplete).toHaveBeenCalledWith({ status: 'COMPLETED', message: 'ok' });
    expect(onError).not.toHaveBeenCalled();
    expect(FakeEventSource.instances).toHaveLength(0);
  });

  it('读流中断后改用 GET resume 继续同一 run（事件不重复、complete 一次）', async () => {
    stubFetchStream(
      [
        'id: run-9:1\nevent: run\ndata: {"runId":"run-9"}\n\n',
        'id: run-9:2\nevent: intent\ndata: {"intent":"PLAN_TRIP"}\n\n',
      ],
      'error',
    );
    const onEvent = vi.fn();
    const onComplete = vi.fn();
    const onError = vi.fn();

    await streamAgentChatPost({ message: '生成行程' }, { onEvent, onComplete, onError });
    await flushAsync();

    expect(FakeEventSource.instances).toHaveLength(1);
    const resume = FakeEventSource.last();
    const url = new URL(resume.url, 'http://localhost');
    expect(url.pathname).toBe('/api/agent/runs/run-9/events');
    expect(url.searchParams.get('lastEventId')).toBe('2');
    expect(url.searchParams.get('ticket')).toBe('ticket-123');

    // 续传补发里重叠的 seq 丢弃
    resume.emit('intent', { intent: 'PLAN_TRIP' }, 'run-9:2');
    resume.emit('complete', { status: 'COMPLETED', message: '完成' }, 'run-9:3');

    expect(onEvent).toHaveBeenCalledWith('run', { runId: 'run-9' });
    expect(onEvent).toHaveBeenCalledWith('intent', { intent: 'PLAN_TRIP' });
    expect(onEvent).toHaveBeenCalledTimes(2);
    expect(onComplete).toHaveBeenCalledTimes(1);
    expect(onError).not.toHaveBeenCalled();
  });

  it('提前 EOF（没有终态事件）且已有 id：自动续传，不误报连接中断', async () => {
    stubFetchStream(['id: run-12:1\nevent: run\ndata: {"runId":"run-12"}\n\n']);
    const onComplete = vi.fn();
    const onError = vi.fn();

    await streamAgentChatPost({ message: '生成行程' }, { onComplete, onError });
    await flushAsync();

    const resume = FakeEventSource.last();
    const url = new URL(resume.url, 'http://localhost');
    expect(url.searchParams.get('lastEventId')).toBe('1');

    resume.emit('complete', { status: 'COMPLETED', message: '完成' }, 'run-12:2');
    expect(onComplete).toHaveBeenCalledTimes(1);
    expect(onError).not.toHaveBeenCalled();
  });

  it('没有 id 的旧后端断流时保持 onError 行为，不发起续传', async () => {
    stubFetchStream(['event: intent\ndata: {"intent":"PLAN_TRIP"}\n\n']);
    const onEvent = vi.fn();
    const onError = vi.fn();

    await streamAgentChatPost({ message: '生成行程' }, { onEvent, onError });
    await flushAsync();

    expect(onEvent).toHaveBeenCalledWith('intent', { intent: 'PLAN_TRIP' });
    expect(onError).toHaveBeenCalledTimes(1);
    expect(onError).toHaveBeenCalledWith('与 Agent 的连接已中断，请重试');
    expect(FakeEventSource.instances).toHaveLength(0);
  });
});

describe('与 agentStore 的集成：续传期间不提前解锁输入', () => {
  beforeEach(() => {
    FakeEventSource.instances = [];
    vi.stubGlobal('EventSource', FakeEventSource);
    useAgentStore.getState().reset();
  });

  afterEach(() => {
    useAgentStore.getState().reset();
    vi.unstubAllGlobals();
  });

  it('连接层中断 → 自动续传 → 收到 complete 才解锁 streaming', async () => {
    useAgentStore.getState().send({ message: '规划上海三日游' });
    expect(useAgentStore.getState().streaming).toBe(true);
    await flushAsync();

    const first = FakeEventSource.last();
    first.emit('run', { runId: 'run-8' }, 'run-8:1');
    first.onerror?.();
    await flushAsync();

    // 续传连接已建立但尚未终态：输入框必须保持禁用，也不能凭空追加错误消息
    expect(FakeEventSource.instances).toHaveLength(2);
    expect(useAgentStore.getState().streaming).toBe(true);
    expect(useAgentStore.getState().messages).toHaveLength(1);

    FakeEventSource.last().emit(
      'complete',
      { status: 'COMPLETED', message: '行程已生成' },
      'run-8:2',
    );
    expect(useAgentStore.getState().streaming).toBe(false);
    expect(useAgentStore.getState().messages).toHaveLength(2);
    expect(useAgentStore.getState().warnings).toHaveLength(0);
  });
});
