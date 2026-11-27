import { describe, expect, it } from 'vitest';
import { ABANDONED_RESULT_TEXT, agentRunBadge } from './agentRunBadge';
import type { AgentRunStatus } from '../types/agent';

/**
 * 徽标投影的行为契约：判定必须与 `lib/runState.ts` 的七态一致，
 * ABANDONED 既不能写成「已取消」也不能写成「失败」（后端语义分开，见 AgentRun.java:51）。
 */
describe('agentRunBadge · 后端运行状态 → 七态徽标', () => {
  const cases: Array<[AgentRunStatus, string, string, string]> = [
    ['RUNNING', 'loading', '进行中', 'run'],
    ['SUCCEEDED', 'complete', '完成', 'ok'],
    ['PARTIAL_SUCCESS', 'partial', '部分成功', 'warn'],
    ['FAILED', 'failed', '失败', 'fail'],
    ['CANCELLED', 'cancelled', '已取消', 'muted'],
  ];

  it.each(cases)('%s → %s（%s / %s）', (raw, state, label, toneClass) => {
    const badge = agentRunBadge(raw);
    expect(badge.state).toBe(state);
    expect(badge.label).toBe(label);
    expect(badge.toneClass).toBe(toneClass);
    expect(badge.note).toBeNull();
    expect(badge.raw).toBe(raw);
  });

  it('ABANDONED 走 degraded 档，文案是「结果未知」而不是「已取消」/「失败」', () => {
    const badge = agentRunBadge('ABANDONED');
    expect(badge.state).toBe('degraded');
    expect(badge.toneClass).toBe('warn');
    expect(badge.label).toBe('结果未知');
    expect(badge.note).toBe(ABANDONED_RESULT_TEXT);
    expect(ABANDONED_RESULT_TEXT).toBe('结果未知（进程重启或心跳超时）');
    expect(badge.label).not.toBe('已取消');
    expect(badge.state).not.toBe('cancelled');
    expect(badge.state).not.toBe('failed');
  });

  it('未登记取值给 unknown + 「状态未知」，绝不当成功也不抛异常', () => {
    const badge = agentRunBadge('SOME_FUTURE_STATUS');
    expect(badge.state).toBe('unknown');
    expect(badge.label).toBe('状态未知');
    expect(badge.toneClass).toBe('muted');
    expect(badge.note).toContain('SOME_FUTURE_STATUS');
    expect(() => agentRunBadge('SOME_FUTURE_STATUS')).not.toThrow();
  });

  it('缺字段（旧后端 / null）按 unknown 兜底，不静默当成完成', () => {
    expect(agentRunBadge(null).state).toBe('unknown');
    expect(agentRunBadge(undefined).state).toBe('unknown');
  });
});
