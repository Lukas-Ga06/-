import { describe, expect, it } from 'vitest';
import {
  ACTIVITY_STAGE_META,
  activityRunState,
  activitySeverity,
  activityStage,
  ledgerRunState,
  mergeRunStates,
} from './activityStage';

describe('activityStage：事件聚合成用户能理解的阶段', () => {
  it('准备阶段聚合连接与需求理解（run / start / intent）', () => {
    expect(activityStage('run')).toBe('prepare');
    expect(activityStage('start')).toBe('prepare');
    expect(activityStage('intent')).toBe('prepare');
  });

  it('研究检索阶段独立成档（rag_result）', () => {
    expect(activityStage('rag_result')).toBe('research');
  });

  it('工具调用阶段聚合调用与结果（tool_call / tool_result）', () => {
    expect(activityStage('tool_call')).toBe('tool');
    expect(activityStage('tool_result')).toBe('tool');
  });

  it('规划阶段聚合进入规划、方案预览与重规划（planning / plan_update / replan）', () => {
    expect(activityStage('planning')).toBe('plan');
    expect(activityStage('plan_update')).toBe('plan');
    expect(activityStage('replan')).toBe('plan');
  });

  it('冲突/重派阶段聚合任务 DAG 的委派、重派与被拒（delegate / recover / conflict）', () => {
    expect(activityStage('delegate')).toBe('dispatch');
    expect(activityStage('recover')).toBe('dispatch');
    expect(activityStage('conflict')).toBe('dispatch');
  });

  it('降级是独立阶段：预算降级 / 过程警告 / 续传超时都进降级档', () => {
    expect(activityStage('budget_degraded')).toBe('degrade');
    expect(activityStage('warning')).toBe('degrade');
    expect(activityStage('resume_timeout')).toBe('degrade');
  });

  it('确认与收尾各成阶段（confirmation / complete / cancelled / error）', () => {
    expect(activityStage('confirmation')).toBe('confirm');
    expect(activityStage('complete')).toBe('finish');
    expect(activityStage('cancelled')).toBe('finish');
    // 终止错误属于本轮结束；失败由行文案与状态色表达
    expect(activityStage('error')).toBe('finish');
  });

  it('不渲染成行的事件返回 null（focus 只驱动地图）', () => {
    expect(activityStage('focus')).toBeNull();
  });

  it('未登记的线上事件返回 null：不猜它的用户语义，也不抛错', () => {
    expect(activityStage('future_event')).toBeNull();
    expect(activityStage('')).toBeNull();
  });

  it('每个阶段都有非空且互不相同的标签', () => {
    const labels = Object.values(ACTIVITY_STAGE_META).map((meta) => meta.label);
    expect(labels.every((label) => label.length > 0)).toBe(true);
    expect(new Set(labels).size).toBe(labels.length);
  });
});

describe('activitySeverity：单行轻重', () => {
  it('预算降级与过程警告是 warn 档，冲突与终止错误是 error 档', () => {
    expect(activitySeverity('budget_degraded', { detail: '预算不足' })).toBe('warn');
    expect(activitySeverity('warning', { message: '模型不可用' })).toBe('warn');
    expect(activitySeverity('conflict', {})).toBe('error');
    expect(activitySeverity('error', { message: '连接中断' })).toBe('error');
  });

  it('用户主动停止是 muted 档（不是失败）', () => {
    expect(activitySeverity('cancelled', { runId: 'r1' })).toBe('muted');
  });

  it('工具失败或没返回数据是 warn 档，正常返回是 info 档', () => {
    expect(activitySeverity('tool_result', { tool: 'a', success: false })).toBe('warn');
    expect(activitySeverity('tool_result', { tool: 'a', result: 'null' })).toBe('warn');
    expect(activitySeverity('tool_result', { tool: 'a', result: null })).toBe('warn');
    expect(activitySeverity('tool_result', { tool: 'a', result: { ok: true } })).toBe('info');
  });

  it('complete 按结果状态定档：FAILED=error / 部分成功=warn / 其余 info', () => {
    expect(activitySeverity('complete', { status: 'FAILED' })).toBe('error');
    expect(activitySeverity('complete', { status: 'PARTIAL_SUCCESS' })).toBe('warn');
    expect(activitySeverity('complete', { status: 'COMPLETED' })).toBe('info');
  });
});

describe('activityRunState：整条时间线的状态', () => {
  it('空时间线是进行中', () => {
    expect(activityRunState([])).toBe('loading');
  });

  it('只有 complete 是完成', () => {
    expect(activityRunState([{ event: 'complete', data: { status: 'COMPLETED' } }])).toBe(
      'complete',
    );
  });

  it('预算降级压过完成：不许把降级的一轮显示成绿色成功', () => {
    expect(
      activityRunState([
        { event: 'budget_degraded', data: { detail: '预算耗尽，跳过审核' } },
        { event: 'complete', data: { status: 'COMPLETED' } },
      ]),
    ).toBe('degraded');
  });

  it('complete 的结果状态更权威：FAILED → failed，CANCELLED → cancelled', () => {
    expect(activityRunState([{ event: 'complete', data: { status: 'FAILED' } }])).toBe('failed');
    expect(activityRunState([{ event: 'complete', data: { status: 'CANCELLED' } }])).toBe(
      'cancelled',
    );
  });

  it('过程警告（warning）不算失败，只降级', () => {
    expect(
      activityRunState([
        { event: 'warning', data: { message: '模型不可用，改用规则' } },
        { event: 'complete', data: { status: 'COMPLETED' } },
      ]),
    ).toBe('degraded');
  });

  it('终止错误是失败（与 SSE 过程警告区分）', () => {
    expect(activityRunState([{ event: 'error', data: { message: '连接中断' } }])).toBe('failed');
  });

  it('冲突与工具没返回数据都算过程缺口（degraded）', () => {
    expect(activityRunState([{ event: 'conflict', data: {} }])).toBe('degraded');
    expect(activityRunState([{ event: 'tool_result', data: { tool: 'a', result: 'null' } }])).toBe(
      'degraded',
    );
  });

  it('未登记事件不改变状态（按无贡献处理，不默认成功）', () => {
    expect(activityRunState([{ event: 'future_event', data: {} }])).toBe('loading');
  });
});

describe('mergeRunStates：状态合成优先级', () => {
  it('失败 > 取消 > 部分成功 > 降级 > 完成 > 进行中', () => {
    expect(mergeRunStates('complete', 'degraded')).toBe('degraded');
    expect(mergeRunStates('degraded', 'partial')).toBe('partial');
    expect(mergeRunStates('partial', 'cancelled')).toBe('cancelled');
    expect(mergeRunStates('cancelled', 'failed')).toBe('failed');
    expect(mergeRunStates('loading', 'complete')).toBe('complete');
    expect(mergeRunStates()).toBe('loading');
  });
});

describe('ledgerRunState：同步台账状态', () => {
  it('成功 → complete，部分失败 → partial，失败 → failed', () => {
    expect(ledgerRunState('SUCCEEDED')).toBe('complete');
    expect(ledgerRunState('PARTIAL_FAILED')).toBe('partial');
    expect(ledgerRunState('FAILED')).toBe('failed');
  });

  it('未知取值按「结果可能不完整」处理，绝不默认成功', () => {
    expect(ledgerRunState('WHATEVER')).toBe('degraded');
    expect(ledgerRunState('')).toBe('degraded');
  });
});
