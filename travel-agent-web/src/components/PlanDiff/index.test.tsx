// @vitest-environment happy-dom
import { afterEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import PlanDiff from './index';
import type { AgentResult } from '../../types/agent';

afterEach(cleanup);

function result(patch: Partial<AgentResult> = {}): AgentResult {
  return {
    status: 'NEED_CONFIRMATION',
    planId: 'plan-1',
    message: '已生成方案，请确认',
    confirmation: {
      planId: 'plan-1',
      summary: '将新增 2 个景点、替换 1 家酒店',
      riskLevel: 'MEDIUM',
    },
    diff: {
      changes: [
        { type: 'ADD', title: '豫园', cost: 40, dayIndex: 1 },
        { type: 'REMOVE', title: '某连锁酒店', dayIndex: 2 },
      ],
      budgetBefore: 2800,
      budgetAfter: 3200,
    },
    ...patch,
  };
}

describe('PlanDiff · V2.2 warnings 与内容指纹', () => {
  it('渲染 warnings 列表（⚠ 前缀）', async () => {
    render(
      <PlanDiff
        open
        result={result({ warnings: ['雨天的户外项目建议备选', '预算已接近上限'] })}
        onConfirm={() => {}}
        onCancel={() => {}}
      />,
    );

    await waitFor(() => expect(screen.getByTestId('plan-diff-dialog')).toBeTruthy());
    expect(screen.getByText('雨天的户外项目建议备选')).toBeTruthy();
    expect(screen.getByText('预算已接近上限')).toBeTruthy();
    expect(screen.getAllByText('⚠').length).toBeGreaterThanOrEqual(2);
  });

  it('contentHash 显示为前 8 + 后 8 位的指纹徽标，并说明绑定语义', async () => {
    const hash = '11223344556677889900aabbccddeeff0011223344556677aabbccdd99887766';
    render(
      <PlanDiff
        open
        result={result({ confirmation: { planId: 'plan-1', summary: '将新增 2 项', riskLevel: 'LOW', contentHash: hash } })}
        onConfirm={() => {}}
        onCancel={() => {}}
      />,
    );

    await waitFor(() => expect(screen.getByTestId('plan-diff-dialog')).toBeTruthy());
    expect(screen.getByText('内容指纹')).toBeTruthy();
    expect(screen.getByText('11223344…99887766')).toBeTruthy();
    expect(screen.getByText('确认将绑定此方案内容')).toBeTruthy();
    // 完整指纹不进可见 DOM（只在 title 提示里）
    expect(screen.queryByText(hash)).toBeNull();
  });

  it('没有 warnings / contentHash 时不渲染对应区块（兼容旧后端）', async () => {
    render(<PlanDiff open result={result()} onConfirm={() => {}} onCancel={() => {}} />);

    await waitFor(() => expect(screen.getByTestId('plan-diff-dialog')).toBeTruthy());
    expect(screen.queryByTestId('plan-diff-warnings')).toBeNull();
    expect(screen.queryByTestId('plan-diff-fingerprint')).toBeNull();
  });

  it('既有 testid 契约不变：dialog 容器与确认按钮', async () => {
    const onConfirm = vi.fn();
    render(<PlanDiff open result={result()} onConfirm={onConfirm} onCancel={() => {}} />);

    await waitFor(() => expect(screen.getByTestId('plan-diff-dialog')).toBeTruthy());
    const confirmBtn = screen.getByTestId('plan-diff-confirm');
    expect(confirmBtn).toBeTruthy();
    fireEvent.click(confirmBtn);
    expect(onConfirm).toHaveBeenCalledTimes(1);
  });
});
