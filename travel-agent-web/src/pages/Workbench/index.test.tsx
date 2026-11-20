// @vitest-environment happy-dom
import { afterEach, describe, expect, it, vi } from 'vitest';
import { cleanup, render, screen } from '@testing-library/react';
import { App } from 'antd';
import { MemoryRouter } from 'react-router-dom';
import Workbench from './index';
import type { RunDetail, RunSummary } from '../../types/agent';

/** 只桩掉运行追踪与侧栏提醒，不触网；页面其余部分按真实组件渲染 */
const api = vi.hoisted(() => ({
  getRuns: vi.fn(),
  getRunDetail: vi.fn(),
}));

vi.mock('../../api/agent', () => ({
  getRuns: api.getRuns,
  getRunDetail: api.getRunDetail,
}));

vi.mock('../../api/reminder', () => ({
  getReminders: vi.fn(() =>
    Promise.resolve({ items: [], count: 0, serverTime: new Date().toISOString() }),
  ),
  dismissReminder: vi.fn(),
}));

afterEach(() => {
  cleanup();
  api.getRuns.mockReset();
  api.getRunDetail.mockReset();
});

function renderPage() {
  return render(
    <MemoryRouter>
      <App>
        <Workbench />
      </App>
    </MemoryRouter>,
  );
}

const run: RunSummary = {
  runId: 'run-1',
  message: '帮我规划杭州两日游',
  intent: 'PLAN_TRIP',
  status: 'SUCCEEDED',
  startedAt: '2026-11-19T10:00:00',
  endedAt: '2026-11-19T10:00:02',
  durationMs: 2100,
};

const detail: RunDetail = {
  run,
  tasks: [
    {
      taskId: 'task-1',
      receiver: 'budget-guard',
      taskType: 'DEGRADE',
      status: 'SUCCEEDED',
      summary: 'RESEARCH：预算耗尽降级（剩余 1200ms）',
      startedAt: '2026-11-19T10:00:01',
      durationMs: 1,
      attempt: 0,
    },
    {
      taskId: 'task-2',
      receiver: 'research-agent',
      taskType: 'RESEARCH',
      status: 'CONFLICT',
      error: '前置任务未完成，任务被拒绝启动',
      startedAt: '2026-11-19T10:00:01',
      durationMs: 0,
      attempt: 0,
    },
  ],
  toolCalls: [
    {
      id: 1,
      taskId: 'task-2',
      toolName: 'query_weather',
      success: false,
      errorCode: 'TIMEOUT',
      latencyMs: 1200,
      startedAt: '2026-11-19T10:00:01',
    },
  ],
};

describe('Workbench · Agent 运行观察', () => {
  it('自动选中首次运行并展示降级、冲突与工具失败原因', async () => {
    api.getRuns.mockResolvedValue([run]);
    api.getRunDetail.mockResolvedValue(detail);

    renderPage();

    // 列表与详情各渲染一行同一运行，属预期
    expect((await screen.findAllByTestId('agent-run-run-1')).length).toBeGreaterThan(0);
    expect(await screen.findByTestId('agent-task-task-1')).toBeTruthy();
    expect(api.getRunDetail).toHaveBeenCalledWith('run-1');

    // 预算降级是后端 budget-guard → DEGRADE 任务，不需要额外字段
    expect(await screen.findByText('预算降级 · 已跳过部分阶段')).toBeTruthy();
    expect(screen.getByText('冲突')).toBeTruthy();
    expect(screen.getByText('失败原因：TIMEOUT')).toBeTruthy();
    expect(screen.getByText('前往 AI 助手')).toBeTruthy();
    expect(screen.getByText(/降级 1 · 重派 0 · 冲突 1/)).toBeTruthy();
  });

  it('空列表与未选中详情都给出明确空态', async () => {
    api.getRuns.mockResolvedValue([]);

    renderPage();

    expect(await screen.findByText('还没有运行记录，去 AI 助手发起一次旅行规划吧')).toBeTruthy();
    expect(screen.getByText('选择左侧运行查看任务链与工具调用')).toBeTruthy();
    expect(api.getRunDetail).not.toHaveBeenCalled();
  });

  it('部分完成不能显示成「成功」，并说明受影响范围（P0-07）', async () => {
    const partial: RunSummary = { ...run, runId: 'run-partial', status: 'PARTIAL_SUCCESS' };
    api.getRuns.mockResolvedValue([partial]);
    api.getRunDetail.mockResolvedValue({ ...detail, run: partial });

    renderPage();

    expect((await screen.findAllByText('部分完成')).length).toBeGreaterThan(0);
    // 关键：运行徽标不能是绿色（任务级「成功」徽标属于另一层，不受此约束）
    expect(document.querySelector('.run-row .run-badge.ok')).toBeNull();
    expect(document.querySelector('.run-row .run-badge.warn')).toBeTruthy();
    // 受影响的具体内容要写出来，而不是只换徽标颜色
    const note = await screen.findByTestId('run-partial-note');
    expect(note.textContent).toContain('部分完成');
    expect(note.textContent).toContain('预算降级');
    expect(note.textContent).toContain('工具失败');
  });

  it('完全成功时不显示部分完成说明', async () => {
    api.getRuns.mockResolvedValue([run]);
    api.getRunDetail.mockResolvedValue(detail);

    renderPage();

    expect((await screen.findAllByText('成功')).length).toBeGreaterThan(0);
    expect(document.querySelector('.run-row .run-badge.ok')).toBeTruthy();
    expect(screen.queryByTestId('run-partial-note')).toBeNull();
  });
});
