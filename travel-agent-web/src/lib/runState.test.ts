import { describe, expect, it } from 'vitest';
import {
  RUN_STATE_META,
  toRunState,
  type RunState,
  type RunStateOrUnknown,
  type RunStateSource,
  type RunStateTone,
} from './runState';

const RUN_STATES: RunState[] = [
  'loading',
  'empty',
  'degraded',
  'partial',
  'failed',
  'cancelled',
  'complete',
];

const TONES: RunStateTone[] = ['ok', 'warn', 'danger', 'muted', 'info'];

const META_CASES: Array<[RunStateOrUnknown, string, RunStateTone]> = [
  ['loading', '进行中', 'info'],
  ['empty', '暂无内容', 'muted'],
  ['degraded', '已降级', 'warn'],
  ['partial', '部分成功', 'warn'],
  ['failed', '失败', 'danger'],
  ['cancelled', '已取消', 'muted'],
  ['complete', '完成', 'ok'],
  ['unknown', '状态未知', 'muted'],
];

/** 后端 AgentRun.java 的 6 个 status 常量（前端 AgentRunStatus 漏了 ABANDONED） */
const AGENT_RUN_CASES: Array<[string, RunState]> = [
  ['RUNNING', 'loading'],
  ['SUCCEEDED', 'complete'],
  ['PARTIAL_SUCCESS', 'partial'],
  ['FAILED', 'failed'],
  ['CANCELLED', 'cancelled'],
  ['ABANDONED', 'degraded'],
];

/** src/lib/resource.ts 的 6 个 ResourceState.status（文档曾写作 7 种，实际 6 种） */
const RESOURCE_CASES: Array<[string, RunState]> = [
  ['idle', 'empty'],
  ['loading', 'loading'],
  ['success', 'complete'],
  ['empty', 'empty'],
  ['error', 'failed'],
  ['offline-stale', 'degraded'],
];

/**
 * SSE 事件名：api/agent.ts 的 EVENT_NAMES 17 个 + 后端实际会发但 GET 未注册的
 * budget_degraded + 仅续传端点的 resume_timeout。
 */
const SSE_CASES: Array<[string, RunState]> = [
  ['run', 'loading'],
  ['start', 'loading'],
  ['intent', 'loading'],
  ['planning', 'loading'],
  ['replan', 'loading'],
  ['delegate', 'loading'],
  ['recover', 'loading'],
  ['conflict', 'degraded'],
  ['tool_call', 'loading'],
  ['tool_result', 'loading'],
  ['rag_result', 'loading'],
  ['plan_update', 'loading'],
  ['focus', 'loading'],
  ['confirmation', 'loading'],
  ['budget_degraded', 'degraded'],
  ['complete', 'complete'],
  ['cancelled', 'cancelled'],
  ['error', 'degraded'],
  ['resume_timeout', 'degraded'],
];

const UNKNOWN_CASES: Array<[RunStateSource, unknown, string]> = [
  // AgentResult.status 不是 AgentRun.status，混用必须落到 unknown 而不是猜一个成功态
  ['agent-run', 'NEED_CONFIRMATION', 'NEED_CONFIRMATION'],
  ['agent-run', 'succeeded', 'succeeded'],
  ['agent-run', '', ''],
  ['resource', 'pending', 'pending'],
  ['resource', 'SUCCESS', 'SUCCESS'],
  ['sse', 'message', 'message'],
  // 前端由 error 派生的活动事件名，不属于线上 SSE 事件名
  ['sse', 'warning', 'warning'],
  ['sse', 'resume-timeout', 'resume-timeout'],
];

const NON_STRING_RAW_CASES: Array<[unknown, string]> = [
  [null, 'null'],
  [undefined, 'undefined'],
  [42, '42'],
  [{}, '[object Object]'],
];

describe('RunState 七态与文案', () => {
  it('恰好七种状态且无重复', () => {
    expect(RUN_STATES).toHaveLength(7);
    expect(new Set(RUN_STATES).size).toBe(7);
  });

  it.each(RUN_STATES)('%s 有非空 label / hint 与合法 tone', (state) => {
    const meta = RUN_STATE_META[state];
    expect(meta.label.trim()).not.toBe('');
    expect(meta.hint.trim()).not.toBe('');
    expect(TONES).toContain(meta.tone);
  });

  it.each(META_CASES)('%s 的 label/tone 固定', (state, label, tone) => {
    expect(RUN_STATE_META[state]).toMatchObject({ label, tone });
  });

  it('unknown 有独立文案，不混入七态', () => {
    const meta = RUN_STATE_META.unknown;
    expect(meta.label).toBe('状态未知');
    expect(meta.hint.trim()).not.toBe('');
    expect(meta.tone).toBe('muted');
    expect(RUN_STATES as string[]).not.toContain('unknown');
  });
});

describe('后端 AgentRun.status → RunState', () => {
  it('覆盖后端全部 6 个取值', () => {
    expect(AGENT_RUN_CASES).toHaveLength(6);
  });

  it.each(AGENT_RUN_CASES)('%s → %s', (raw, expected) => {
    expect(toRunState('agent-run', raw)).toEqual({
      state: expected,
      source: 'agent-run',
      raw,
    });
  });

  it('ABANDONED 保留「结果未知」语义：降级而不是失败或成功', () => {
    const mapped = toRunState('agent-run', 'ABANDONED');
    expect(mapped.state).toBe('degraded');
    expect(mapped.state).not.toBe('failed');
    expect(mapped.state).not.toBe('complete');
  });
});

describe('前端 ResourceState.status → RunState', () => {
  it('覆盖 ResourceState 全部 6 个取值', () => {
    expect(RESOURCE_CASES).toHaveLength(6);
  });

  it.each(RESOURCE_CASES)('%s → %s', (raw, expected) => {
    expect(toRunState('resource', raw)).toEqual({
      state: expected,
      source: 'resource',
      raw,
    });
  });

  it('offline-stale 是「有缓存但可能过期」，映射 degraded 而不是 complete', () => {
    expect(toRunState('resource', 'offline-stale').state).toBe('degraded');
  });
});

describe('SSE 事件名 → RunState', () => {
  it('覆盖前端注册事件与后端额外事件共 19 个取值', () => {
    expect(SSE_CASES).toHaveLength(19);
  });

  it.each(SSE_CASES)('%s → %s', (raw, expected) => {
    expect(toRunState('sse', raw)).toEqual({
      state: expected,
      source: 'sse',
      raw,
    });
  });

  it('error 是过程警告，不能当本轮失败', () => {
    expect(toRunState('sse', 'error').state).toBe('degraded');
  });

  it('conflict 表示任务被拒后回收，本轮仍在继续', () => {
    expect(toRunState('sse', 'conflict').state).toBe('degraded');
  });
});

describe('未知取值兜底', () => {
  it.each(UNKNOWN_CASES)('%s/%s → unknown 且保留原始值', (source, raw, expectedRaw) => {
    const mapped = toRunState(source, raw);
    expect(mapped).toEqual({ state: 'unknown', source, raw: expectedRaw });
    expect(mapped.state).not.toBe('complete');
  });

  it.each(NON_STRING_RAW_CASES)('非字符串 %s 归一为 %s 且不抛异常', (raw, expectedRaw) => {
    expect(() => toRunState('agent-run', raw)).not.toThrow();
    expect(toRunState('agent-run', raw)).toEqual({
      state: 'unknown',
      source: 'agent-run',
      raw: expectedRaw,
    });
  });

  it('未知事件名不抛异常，也不会被猜成成功', () => {
    const mapped = toRunState('sse', 'brand_new_event');
    expect(mapped.state).toBe('unknown');
    expect(mapped.state).not.toBe('complete');
    expect(mapped).toMatchObject({ source: 'sse', raw: 'brand_new_event' });
  });
});
