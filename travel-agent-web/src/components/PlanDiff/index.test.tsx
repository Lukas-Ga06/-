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
        result={result({
          confirmation: {
            planId: 'plan-1',
            summary: '将新增 2 项',
            riskLevel: 'LOW',
            contentHash: hash,
          },
        })}
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

describe('PlanDiff · Batch 13 审核结论与风险接受', () => {
  /** 修不好 → blocked=true；issues 含 HIGH+MEDIUM，repairs 是自动修复动作 */
  function blockedResult(): AgentResult {
    return result({
      review: {
        passed: false,
        blocked: true,
        attempts: 2,
        issues: [
          {
            type: 'TRANSFER_TIME',
            severity: 'HIGH',
            message: 'Day 1「外滩」→「迪士尼」转场需 65 分钟，但只留了 10 分钟',
          },
          { type: 'TIME_CONFLICT', severity: 'MEDIUM', message: 'Day 2 午餐与酒店入住时间重叠' },
        ],
        repairs: ['Day 1 把「外滩」之后的安排整体后移 60 分钟', 'Day 2 把雨天户外景点换成室内景点'],
      },
      confirmation: {
        planId: 'plan-1',
        summary: '预算 1 元的行程风险较高',
        riskLevel: 'HIGH',
        requiresAcknowledgement: true,
      },
    });
  }

  it('渲染审核结论：问题逐条列出、重规划次数与动作、blocked 时顶部 error Alert', async () => {
    render(<PlanDiff open result={blockedResult()} onConfirm={() => {}} onCancel={() => {}} />);

    await waitFor(() => expect(screen.getByTestId('plan-review')).toBeTruthy());
    const issues = screen.getByTestId('plan-review-issues');
    expect(issues.textContent).toContain('转场需 65 分钟');
    expect(issues.textContent).toContain('午餐与酒店入住时间重叠');

    const review = screen.getByTestId('plan-review');
    expect(review.textContent).toContain('已自动重规划 2 次');
    expect(review.textContent).toContain('整体后移 60 分钟');
    expect(review.textContent).toContain('雨天户外景点换成室内景点');

    expect(screen.getByText('存在未解决的问题，需明确接受风险后才能提交')).toBeTruthy();
  });

  it('requiresAcknowledgement：未勾选时确认按钮 disabled，勾选后才回调 onConfirm(true)', async () => {
    const onConfirm = vi.fn();
    render(<PlanDiff open result={blockedResult()} onConfirm={onConfirm} onCancel={() => {}} />);

    await waitFor(() => expect(screen.getByTestId('plan-diff-dialog')).toBeTruthy());
    const confirmBtn = screen.getByTestId('plan-diff-confirm') as HTMLButtonElement;
    expect(confirmBtn.disabled).toBe(true);
    fireEvent.click(confirmBtn);
    expect(onConfirm).not.toHaveBeenCalled();

    fireEvent.click(screen.getByTestId('plan-diff-ack'));
    await waitFor(() => expect(confirmBtn.disabled).toBe(false));

    fireEvent.click(confirmBtn);
    expect(onConfirm).toHaveBeenCalledTimes(1);
    expect(onConfirm).toHaveBeenCalledWith(true);
  });

  it('P1：citations 展示知识依据（title / 版本 / 相似度 / snippet / source）', async () => {
    render(
      <PlanDiff
        open
        result={result({
          citations: [
            {
              documentId: 7,
              version: 3,
              chunkId: 12,
              score: 0.923,
              source: '个人知识库',
              title: '上海亲子两日游',
              snippet: '外滩傍晚人流较少，适合带娃散步。',
              label: '《上海亲子两日游》 v3',
            },
          ],
        })}
        onConfirm={() => {}}
        onCancel={() => {}}
      />,
    );

    await waitFor(() => expect(screen.getByTestId('plan-diff-dialog')).toBeTruthy());
    const list = screen.getByTestId('citation-list');
    expect(list.textContent).toContain('上海亲子两日游');
    expect(list.textContent).toContain('v3');
    expect(list.textContent).toContain('相似度 0.923');
    expect(list.textContent).toContain('外滩傍晚人流较少');
    expect(list.textContent).toContain('来源：个人知识库');
    expect(list.textContent).toContain('文档 #7');
  });

  it('P1：citations 为空数组时不渲染知识依据区块', async () => {
    render(
      <PlanDiff open result={result({ citations: [] })} onConfirm={() => {}} onCancel={() => {}} />,
    );

    await waitFor(() => expect(screen.getByTestId('plan-diff-dialog')).toBeTruthy());
    expect(screen.queryByTestId('citation-list')).toBeNull();
  });

  it('旧后端（无 review / requiresAcknowledgement）渲染零新增，确认仍回调 false', async () => {
    const onConfirm = vi.fn();
    render(<PlanDiff open result={result()} onConfirm={onConfirm} onCancel={() => {}} />);

    await waitFor(() => expect(screen.getByTestId('plan-diff-dialog')).toBeTruthy());
    expect(screen.queryByTestId('plan-review')).toBeNull();
    expect(screen.queryByTestId('plan-diff-ack')).toBeNull();

    const confirmBtn = screen.getByTestId('plan-diff-confirm') as HTMLButtonElement;
    expect(confirmBtn.disabled).toBe(false);
    fireEvent.click(confirmBtn);
    expect(onConfirm).toHaveBeenCalledWith(false);
  });
});
