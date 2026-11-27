// @vitest-environment happy-dom
import { afterEach, describe, expect, it } from 'vitest';
import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import AgentActivityPanel from './index';
import type { ActivityEvent } from '../../store/stageText';
import type { ExecutionSummary } from '../../types/agent';

afterEach(cleanup);

/** 默认折叠（§8.6）：展开后才渲染时间线行 */
function expand() {
  fireEvent.click(screen.getByRole('button', { name: '查看运行详情' }));
}

function ev(event: string, data: any, time = Date.now() - 1000): ActivityEvent {
  return { event, data, time };
}

describe('AgentActivityPanel', () => {
  it('activity 为空时整块不渲染（退化为单行 stage）', () => {
    const { container } = render(<AgentActivityPanel activity={[]} />);
    expect(container.querySelector('.agent-activity')).toBeNull();
    expect(container.textContent).toBe('');
  });

  it('事件按顺序渲染成时间线行', () => {
    render(
      <AgentActivityPanel
        activity={[
          ev('start', {}),
          ev('run', { runId: '01234567-abcd-4ef0-9abc-def012345678' }),
          ev('intent', { intent: 'PLAN_TRIP' }),
          ev('planning', {}),
          ev('plan_update', {}),
          ev('confirmation', {}),
        ]}
      />,
    );

    expand();
    expect(screen.getByText('已连接 Agent…')).toBeTruthy();
    expect(screen.getByText('本轮运行 01234567…')).toBeTruthy();
    expect(screen.getByText('意图识别：PLAN_TRIP')).toBeTruthy();
    expect(screen.getByText('正在规划行程…')).toBeTruthy();
    expect(screen.getByText('生成方案对比…')).toBeTruthy();
    expect(screen.getByText('等待你的确认…')).toBeTruthy();
  });

  it('tool_call 只显示工具名与参数字段名，绝不渲染参数值原文（敏感参数红线）', () => {
    const { container } = render(
      <AgentActivityPanel
        activity={[
          ev('tool_call', {
            tool: 'search_city_online',
            args: { city: 'SECRET_CITY_VALUE', days: 3, apiKey: 'SECRET_KEY_VALUE' },
          }),
        ]}
      />,
    );

    expand();
    expect(container.textContent).toContain('search_city_online');
    expect(container.textContent).toContain('city、days、apiKey');
    expect(container.textContent).not.toContain('SECRET_CITY_VALUE');
    expect(container.textContent).not.toContain('SECRET_KEY_VALUE');
  });

  it('tool_result 按成功/失败渲染不同文案，error 事件渲染错误信息', () => {
    render(
      <AgentActivityPanel
        activity={[
          ev('tool_result', { tool: 'search_city_online', success: true }),
          ev('tool_result', { tool: 'geocode', success: false }),
          ev('error', { message: '规划失败：天气服务不可用' }),
        ]}
      />,
    );

    expand();
    expect(screen.getByText('工具 search_city_online 执行完成')).toBeTruthy();
    expect(screen.getByText('工具 geocode 执行失败')).toBeTruthy();
    expect(screen.getByText('规划失败：天气服务不可用')).toBeTruthy();
  });

  it('Batch 15：delegate/recover/conflict 渲染委派、重派、冲突文案', () => {
    const { container } = render(
      <AgentActivityPanel
        activity={[
          ev('delegate', {
            taskId: 't-plan',
            receiver: 'planner-agent',
            taskType: 'PLAN',
            dependsOn: ['t-intent'],
            attempt: 0,
          }),
          ev('recover', {
            taskId: 't-replan',
            replacesTaskId: 't-plan',
            taskType: 'REPLAN',
            attempt: 1,
          }),
          ev('conflict', {
            receiver: 'reflect-agent',
            taskType: 'REVIEW',
            missingDeps: ['t-preview'],
            reason: '前置未完成',
          }),
        ]}
      />,
    );

    expand();
    expect(screen.getByText('委派 planner-agent：PLAN')).toBeTruthy();
    expect(screen.getByText('重派 REPLAN（第 2 次尝试）')).toBeTruthy();
    expect(screen.getByText('任务冲突：reflect-agent/REVIEW 前置未完成，已回收')).toBeTruthy();
    // data-event 仍然输出事件名
    expect(container.querySelector('[data-event="delegate"]')).toBeTruthy();
    expect(container.querySelector('[data-event="recover"]')).toBeTruthy();
    // 冲突行沿用既有 error 高亮类
    expect(container.querySelector('[data-event="conflict"]')?.className).toContain('error');
  });

  it('Batch 15：三类事件的字段缺失时退回 stageText 占位文案（不抛错、不空行）', () => {
    render(
      <AgentActivityPanel activity={[ev('delegate', {}), ev('recover', {}), ev('conflict', {})]} />,
    );

    expand();
    expect(screen.getByText('委派 …：…')).toBeTruthy();
    expect(screen.getByText('重派 …（第 1 次尝试）')).toBeTruthy();
    expect(screen.getByText('任务冲突：…/… 前置未完成，已回收')).toBeTruthy();
  });

  it('P1：rag_result 携带 citations 时展示知识依据，空 citations 不渲染', () => {
    render(
      <AgentActivityPanel
        activity={[
          ev('rag_result', {
            sources: ['《杭州攻略》 v1'],
            citations: [
              {
                documentId: 3,
                version: 2,
                chunkId: 8,
                score: 0.8123,
                source: '个人知识库',
                title: '杭州亲子攻略',
                snippet: '西湖边适合推婴儿车的路线…',
                label: '《杭州亲子攻略》 v2',
              },
            ],
          }),
        ]}
      />,
    );

    expand();
    expect(screen.getByTestId('activity-citation-list')).toBeTruthy();
    expect(screen.getByText('杭州亲子攻略')).toBeTruthy();
    expect(screen.getByText('v2')).toBeTruthy();
    expect(screen.getByText('相似度 0.812')).toBeTruthy();
    expect(screen.getByText('西湖边适合推婴儿车的路线…')).toBeTruthy();
    expect(screen.getByText('来源：个人知识库')).toBeTruthy();
  });

  it('未映射的事件（focus）不单独渲染成行', () => {
    const { container } = render(
      <AgentActivityPanel activity={[ev('focus', { type: 'itinerary', day: 2 })]} />,
    );
    expect(container.querySelector('.aa-row')).toBeNull();
  });

  it('预算降级：折叠态就给出降级结论与原因，展开后行带「降级」阶段标签', () => {
    const { container } = render(
      <AgentActivityPanel
        activity={[
          ev('run', { runId: '01234567-abcd-4ef0-9abc-def012345678' }),
          ev('planning', {}),
          ev('budget_degraded', {
            stage: 'review',
            detail: '预算耗尽，已跳过审核与重规划（未做路线/天气/预算硬约束校验）',
            remainingMs: 0,
          }),
          ev('complete', { status: 'COMPLETED' }),
        ]}
      />,
    );

    // 折叠态：降级结论必须可见（用户不展开也知道这次走了降级路径）
    const notice = screen.getByTestId('agent-activity-degrade');
    expect(notice.textContent).toContain('本次结果走了降级路径');
    expect(notice.textContent).toContain('预算耗尽，已跳过审核与重规划');
    // 状态不许并进普通成功：预算降级压过 complete
    expect(screen.getByTestId('agent-activity-status').getAttribute('data-status')).toBe(
      'degraded',
    );

    expand();
    const row = container.querySelector('[data-event="budget_degraded"]');
    expect(row?.getAttribute('data-stage')).toBe('degrade');
    expect(row?.getAttribute('data-severity')).toBe('warn');
    expect(row?.querySelector('.aa-stage')?.textContent).toBe('降级');
    expect(row?.textContent).toContain('预算耗尽，已跳过审核与重规划');
    // 阶段切换：规划 → 降级 → 收尾 三段，降级行每次带标签
    expect(container.querySelector('[data-event="planning"] .aa-stage')?.textContent).toBe('规划');
  });

  it('冲突/重派是独立阶段：状态降级但不冒充「降级路径」结论', () => {
    const { container } = render(
      <AgentActivityPanel
        activity={[
          ev('delegate', { receiver: 'planner-agent', taskType: 'PLAN' }),
          ev('conflict', { receiver: 'reflect-agent', taskType: 'REVIEW', reason: '前置未完成' }),
          ev('complete', { status: 'COMPLETED' }),
        ]}
      />,
    );

    expect(screen.queryByTestId('agent-activity-degrade')).toBeNull();
    expect(screen.getByTestId('agent-activity-status').getAttribute('data-status')).toBe(
      'degraded',
    );

    expand();
    const row = container.querySelector('[data-event="conflict"]');
    expect(row?.getAttribute('data-stage')).toBe('dispatch');
    // 同一阶段只出现一次标签（委派与冲突共用一个标签，不每行重复）
    expect(container.querySelectorAll('.aa-stage[data-stage="dispatch"]')).toHaveLength(1);
    expect(container.querySelector('.aa-stage[data-stage="dispatch"]')?.textContent).toBe(
      '冲突/重派',
    );
  });

  it('同阶段连续行只出现一次阶段标签（聚合，不是每事件一标签）', () => {
    const { container } = render(
      <AgentActivityPanel
        activity={[
          ev('tool_call', { tool: 'search_city_online', args: { city: '杭州' } }),
          ev('tool_result', { tool: 'search_city_online', result: { ok: true } }),
        ]}
      />,
    );

    expand();
    expect(container.querySelectorAll('.aa-row[data-stage="tool"]')).toHaveLength(2);
    expect(container.querySelectorAll('.aa-stage')).toHaveLength(1);
  });

  it('过程警告（warning）进降级档并给出服务端原因', () => {
    const { container } = render(
      <AgentActivityPanel
        activity={[
          ev('warning', { message: '模型不可用，已改用规则规划' }),
          ev('complete', { status: 'COMPLETED' }),
        ]}
      />,
    );

    const notice = screen.getByTestId('agent-activity-degrade');
    expect(notice.textContent).toContain('本轮有过程警告');
    expect(notice.textContent).toContain('模型不可用，已改用规则规划');
    expand();
    expect(container.querySelector('[data-event="warning"]')?.getAttribute('data-stage')).toBe(
      'degrade',
    );
  });

  it('用户停止：时间线出现「本轮已停止」，状态是 cancelled 而不是完成', () => {
    const { container } = render(
      <AgentActivityPanel activity={[ev('planning', {}), ev('cancelled', { runId: 'r1' })]} />,
    );

    expand();
    const row = container.querySelector('[data-event="cancelled"]');
    expect(row?.textContent).toContain('本轮已停止（未生成最终结果）');
    expect(row?.className).toContain('muted');
    expect(screen.getByTestId('agent-activity-status').getAttribute('data-status')).toBe(
      'cancelled',
    );
  });

  it('complete 按结果状态给结论：FAILED 不能写成「本轮运行完成」', () => {
    const { container } = render(
      <AgentActivityPanel activity={[ev('complete', { status: 'FAILED' })]} />,
    );

    expand();
    expect(container.querySelector('[data-event="complete"]')?.textContent).toContain(
      '本轮未完成（失败）',
    );
    expect(container.textContent).not.toContain('本轮运行完成');
    expect(screen.getByTestId('agent-activity-status').getAttribute('data-status')).toBe('failed');
  });

  it('工具没返回数据单独成句（不是「执行完成」）', () => {
    const { container } = render(
      <AgentActivityPanel
        activity={[ev('tool_result', { tool: 'mcp_amap_text_search', result: 'null' })]}
      />,
    );

    expand();
    const row = container.querySelector('[data-event="tool_result"]');
    expect(row?.textContent).toContain('工具 mcp_amap_text_search 未返回数据');
    expect(row?.className).toContain('warn');
  });

  it('同步台账：确认后的模块结果作为独立阶段追加，失败项点名原因与可重试', () => {
    const execution: ExecutionSummary = {
      executionId: 'exec-1',
      planId: 'plan-1',
      tripId: 7,
      status: 'PARTIAL_FAILED',
      modules: [
        { module: 'calendar', status: 'SUCCEEDED', detail: '已写入 3 个时间块' },
        { module: 'budget', status: 'FAILED', error: '预算服务超时' },
        { module: 'footprint', status: 'SUCCEEDED' },
      ],
    };

    const { container } = render(
      <AgentActivityPanel
        activity={[ev('confirmation', {}), ev('complete', { status: 'COMPLETED' })]}
        execution={execution}
      />,
    );

    // 台账部分失败：整块状态是「部分成功」，不显示成完成
    expect(screen.getByTestId('agent-activity-status').getAttribute('data-status')).toBe('partial');

    expand();
    const ledger = container.querySelector('[data-event="ledger"]');
    expect(ledger?.getAttribute('data-stage')).toBe('ledger');
    expect(ledger?.querySelector('.aa-stage')?.textContent).toBe('同步台账');
    expect(ledger?.textContent).toContain('同步台账：日程 已同步、预算 失败、足迹 已同步');
    expect(ledger?.textContent).toContain('预算：预算服务超时');
    expect(ledger?.textContent).toContain('可在下方重试');
  });

  it('同步台账全部成功时给完成态，不加降级结论', () => {
    const execution: ExecutionSummary = {
      executionId: 'exec-2',
      planId: 'plan-2',
      status: 'SUCCEEDED',
      modules: [{ module: 'budget', status: 'SUCCEEDED', detail: '预算快照已写入' }],
    };

    const { container } = render(
      <AgentActivityPanel
        activity={[ev('complete', { status: 'COMPLETED' })]}
        execution={execution}
      />,
    );

    expect(screen.getByTestId('agent-activity-status').getAttribute('data-status')).toBe(
      'complete',
    );
    expect(screen.queryByTestId('agent-activity-degrade')).toBeNull();
    expand();
    expect(container.querySelector('[data-event="ledger"]')?.textContent).toContain(
      '同步台账：预算 已同步',
    );
  });
});
