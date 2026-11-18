import { describe, expect, it } from 'vitest';
import { stageText } from './stageText';

/**
 * stageText 单测（Batch 15）：任务 DAG 的 delegate / recover / conflict 三个新事件映射，
 * 并覆盖事件字段缺失时的兜底（给占位符而不是抛错）。
 * 既有事件文案一字未改，由 AgentActivityPanel / ChatPanel 的既有用例守护。
 */

describe('stageText（Batch 15 任务 DAG 事件）', () => {
  it('delegate 渲染接收方与任务类型', () => {
    const data = {
      taskId: 't-plan',
      receiver: 'planner-agent',
      taskType: 'PLAN',
      dependsOn: ['t-intent'],
      attempt: 0,
    };
    expect(stageText('delegate', data)).toBe('委派 planner-agent：PLAN');
  });

  it('delegate 缺字段时退回占位符而不抛错', () => {
    expect(stageText('delegate', {})).toBe('委派 …：…');
    expect(stageText('delegate', null)).toBe('委派 …：…');
  });

  it('recover 按 attempt+1 渲染第几次尝试', () => {
    expect(stageText('recover', { taskId: 't-2', replacesTaskId: 't-1', taskType: 'REVIEW', attempt: 1 })).toBe(
      '重派 REVIEW（第 2 次尝试）',
    );
    expect(stageText('recover', { taskId: 't-2', replacesTaskId: 't-1', taskType: 'REVIEW', attempt: 2 })).toBe(
      '重派 REVIEW（第 3 次尝试）',
    );
  });

  it('recover 的 attempt 缺省按 0（首次）处理', () => {
    expect(stageText('recover', { taskType: 'REPLAN' })).toBe('重派 REPLAN（第 1 次尝试）');
  });

  it('conflict 渲染前置未完成的冲突反馈', () => {
    const data = {
      receiver: 'reflect-agent',
      taskType: 'REVIEW',
      missingDeps: ['t-preview'],
      reason: '前置未完成',
    };
    expect(stageText('conflict', data)).toBe('任务冲突：reflect-agent/REVIEW 前置未完成，已回收');
  });

  it('conflict 缺字段时退回占位符而不抛错', () => {
    expect(stageText('conflict', {})).toBe('任务冲突：…/… 前置未完成，已回收');
  });
});