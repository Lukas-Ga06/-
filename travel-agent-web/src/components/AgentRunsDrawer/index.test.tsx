// @vitest-environment happy-dom
import { afterEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { MemoryRouter, useLocation } from 'react-router-dom';
import AgentRunsDrawer from './index';
import type { RunDetail, RunSummary, SpendView } from '../../types/agent';

/**
 * 运行记录抽屉的「本轮花费」区块（V29）。
 * 只覆盖 spend 的渲染与缺席语义；任务链 / 工具调用的既有行为不在这里重复。
 */
const api = vi.hoisted(() => ({
  getRuns: vi.fn(),
  getRunDetail: vi.fn(),
}));

vi.mock('../../api/agent', () => ({
  getRuns: api.getRuns,
  getRunDetail: api.getRunDetail,
}));

const RUN: RunSummary = {
  runId: 'run-1',
  status: 'SUCCEEDED',
  intent: 'PLAN_TRIP',
  startedAt: '2026-11-26T10:00:00',
  endedAt: '2026-11-26T10:00:02',
  durationMs: 2000,
};

/** spend 缺省（老 run）与显式 null 是两种响应形态，用例里都要能构造出来 */
function makeDetail(spend?: SpendView | null): RunDetail {
  const detail: RunDetail = { run: RUN, tasks: [], toolCalls: [] };
  if (spend !== undefined) detail.spend = spend;
  return detail;
}

/** 打开抽屉 → 点第一条 run → 等详情渲染出来 */
async function openDetail(detail: RunDetail): Promise<void> {
  api.getRuns.mockResolvedValue([RUN]);
  api.getRunDetail.mockResolvedValue(detail);
  // 抽屉里的「重跑」要用 router 导航（ABANDONED 的下一步），渲染必须在 Router 内
  render(
    <MemoryRouter>
      <AgentRunsDrawer open onClose={vi.fn()} />
    </MemoryRouter>,
  );
  fireEvent.click(await screen.findByTestId('agent-run-run-1'));
  await screen.findByText('任务链');
}

/** 花费区块里某一项的数值（按标签配对，不靠位置） */
function spendValue(label: string): string | null {
  const block = screen.getByTestId('agent-run-spend');
  const labelNode = Array.from(block.querySelectorAll('.spend-label')).find(
    (node) => node.textContent === label,
  );
  const valueNode = labelNode?.parentElement?.querySelector('.spend-value');
  return valueNode?.textContent ?? null;
}

function spendLabels(): string[] {
  return Array.from(screen.getByTestId('agent-run-spend').querySelectorAll('.spend-label')).map(
    (node) => node.textContent ?? '',
  );
}

function sectionTitles(): Array<string | null> {
  return Array.from(document.querySelectorAll('.runs-drawer .runs-section-title')).map(
    (node) => node.textContent,
  );
}

afterEach(cleanup);

describe('AgentRunsDrawer · 本轮花费（V29）', () => {
  it('有 spend 时渲染全部已有字段，并排在「任务链」之前', async () => {
    await openDetail(
      makeDetail({
        llmCalls: 3,
        toolCalls: 12,
        retries: 0,
        promptTokens: 1200,
        completionTokens: 340,
        totalTokens: 1540,
        costUsd: 0.0123,
      }),
    );

    expect(spendLabels()).toEqual([
      'LLM 调用次数',
      '工具调用次数',
      '重试次数',
      '输入 token',
      '输出 token',
      '合计 token',
      '费用',
    ]);
    expect(spendValue('LLM 调用次数')).toBe('3');
    expect(spendValue('工具调用次数')).toBe('12');
    // 0 次重试是真实记录，不能当空值省略
    expect(spendValue('重试次数')).toBe('0');
    expect(spendValue('输入 token')).toBe('1,200');
    expect(spendValue('输出 token')).toBe('340');
    expect(spendValue('合计 token')).toBe('1,540');
    expect(spendValue('费用')).toBe('$0.0123');
    // 位置：本轮花费 → 任务链 → 工具调用
    expect(sectionTitles()).toEqual(['本轮花费', '任务链', '工具调用']);
    // 未触顶：不出现警示行
    expect(screen.queryByText(/预算触顶/)).toBeNull();
  });

  it('老 run 没有 spend：整块不渲染，也不写「无花费记录」之类的空壳', async () => {
    await openDetail(makeDetail());
    expect(screen.queryByText('本轮花费')).toBeNull();
    expect(screen.queryByTestId('agent-run-spend')).toBeNull();
    cleanup();

    // 后端若显式序列化 null，语义与缺省一致
    await openDetail(makeDetail(null));
    expect(screen.queryByText('本轮花费')).toBeNull();
    expect(screen.queryByTestId('agent-run-spend')).toBeNull();
  });

  it('只渲染后端真的落过的字段：缺省项不补 0', async () => {
    await openDetail(makeDetail({ llmCalls: 2 }));
    expect(spendLabels()).toEqual(['LLM 调用次数']);
    expect(screen.queryByText('费用')).toBeNull();
    expect(screen.queryByText(/token/)).toBeNull();
  });

  it('exhausted 翻成中文；未知 tag 原样显示', async () => {
    const cases: Array<[string, string]> = [
      ['llm_calls', 'LLM 调用次数'],
      ['tool_calls', '工具调用次数'],
      ['retries', '重试次数'],
      ['tokens', 'token'],
      ['cost', '费用'],
      ['future_tag', 'future_tag'],
    ];
    for (const [tag, label] of cases) {
      await openDetail(makeDetail({ llmCalls: 1, exhausted: tag }));
      expect(screen.getByText(`⚠ 预算触顶：${label}`)).toBeTruthy();
      cleanup();
    }
  });

  it('费用固定 4 位小数：小额显示 <$0.0001，0 照实显示 $0.0000', async () => {
    await openDetail(makeDetail({ costUsd: 0.00004 }));
    expect(spendValue('费用')).toBe('<$0.0001');
    cleanup();

    // 边界值 0.0001 不是「小于」，正常四舍五入
    await openDetail(makeDetail({ costUsd: 0.0001 }));
    expect(spendValue('费用')).toBe('$0.0001');
    cleanup();

    // 0 = 后端未配置单价，是真实记录，不能美化掉
    await openDetail(makeDetail({ costUsd: 0 }));
    expect(spendValue('费用')).toBe('$0.0000');
  });
});

describe('AgentRunsDrawer · ABANDONED（结果未知）', () => {
  const ABANDONED_RUN: RunSummary = {
    runId: 'run-abandoned',
    status: 'ABANDONED',
    message: '帮我规划杭州两日游',
    intent: 'PLAN_TRIP',
    startedAt: '2026-11-26T10:00:00',
    endedAt: null,
  };

  function renderWithProbe() {
    function LocationProbe() {
      const location = useLocation();
      return <div data-testid="probe-loc">{location.pathname + location.search}</div>;
    }
    return render(
      <MemoryRouter initialEntries={['/assistant']}>
        <LocationProbe />
        <AgentRunsDrawer open onClose={vi.fn()} />
      </MemoryRouter>,
    );
  }

  it('显示「结果未知（进程重启或心跳超时）」而不是「已取消」，并给 degraded 档', async () => {
    api.getRuns.mockResolvedValue([ABANDONED_RUN]);
    api.getRunDetail.mockResolvedValue({ run: ABANDONED_RUN, tasks: [], toolCalls: [] });

    renderWithProbe();
    fireEvent.click(await screen.findByTestId('agent-run-run-abandoned'));

    const note = await screen.findByTestId('agent-run-abandoned-note');
    expect(note.textContent).toContain('结果未知（进程重启或心跳超时）');
    // 后端把 ABANDONED 与 CANCELLED 分开，界面也不许混成一档
    expect(screen.queryByText('已取消')).toBeNull();
    expect(document.querySelector('.run-badge.muted')).toBeNull();
    expect(document.querySelector('.run-badge.warn')).toBeTruthy();
    expect(note.querySelector('[data-status="degraded"]')).toBeTruthy();
    expect(screen.getByTestId('run-rerun')).toBeTruthy();
  });

  it('「重跑」把原请求带回 AI 助手（可重发），不是只给一句提示', async () => {
    api.getRuns.mockResolvedValue([ABANDONED_RUN]);
    api.getRunDetail.mockResolvedValue({ run: ABANDONED_RUN, tasks: [], toolCalls: [] });

    renderWithProbe();
    fireEvent.click(await screen.findByTestId('agent-run-run-abandoned'));
    fireEvent.click(await screen.findByTestId('run-rerun'));

    const loc = screen.getByTestId('probe-loc').textContent ?? '';
    expect(loc).toContain('/assistant?q=');
    expect(decodeURIComponent(loc)).toContain('帮我规划杭州两日游');
  });
});
