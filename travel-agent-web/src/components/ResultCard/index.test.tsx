// @vitest-environment happy-dom
import { afterEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { App } from 'antd';
import ResultCard from './index';
import type { AgentResult } from '../../types/agent';

function result(patch: Partial<AgentResult> = {}): AgentResult {
  return { status: 'COMPLETED', message: '行程已保存', ...patch };
}

function renderCard(props: Parameters<typeof ResultCard>[0]) {
  return render(
    <App>
      <ResultCard {...props} />
    </App>,
  );
}

afterEach(cleanup);

describe('ResultCard · 结构化结果（阶段 C）', () => {
  it('完成状态展示「已完成」与结果正文', () => {
    renderCard({ result: result() });

    expect(screen.getByTestId('result-status').textContent).toBe('已完成');
    expect(screen.getByTestId('result-card').getAttribute('data-status')).toBe('COMPLETED');
    expect(screen.getByText('行程已保存')).toBeTruthy();
  });

  it('有过程警告时降级为「部分完成」，绝不显示无条件成功（P0-07）', () => {
    renderCard({ result: result(), warnings: ['天气服务超时，已用本地缓存'] });

    expect(screen.getByTestId('result-status').textContent).toBe('部分完成');
    expect(screen.getByTestId('result-warnings').textContent).toContain('天气服务超时');
  });

  it('待确认方案提示「尚未写入行程」，并可取消方案', () => {
    const onCancelPlan = vi.fn();
    renderCard({
      result: result({
        status: 'NEED_CONFIRMATION',
        message: '已生成方案，请确认',
        confirmation: { planId: 'p-1', summary: '上海三日游' },
      }),
      onCancelPlan,
    });

    expect(screen.getByTestId('result-status').textContent).toBe('待确认');
    fireEvent.click(screen.getByRole('button', { name: /取消方案/ }));
    expect(onCancelPlan).toHaveBeenCalledTimes(1);
  });

  it('影响范围汇总 Diff 条数、标题与预算变化', () => {
    renderCard({
      result: result({
        diff: {
          changes: [
            { type: 'ADD', title: '外滩', itemType: 'attraction' },
            { type: 'MODIFY', title: '城隍庙' },
            { type: 'REMOVE', title: '迪士尼' },
            { type: 'ADD', title: '豫园' },
          ],
          budgetBefore: 3000,
          budgetAfter: 3200,
        },
      }),
    });

    const scope = screen.getByTestId('result-scope').textContent ?? '';
    expect(scope).toContain('新增「外滩」');
    expect(scope).toContain('调整「城隍庙」');
    expect(scope).toContain('等 4 项');
    expect(scope).toContain('预算 ¥3000 → ¥3200');
  });

  it('审核问题按风险等级展示', () => {
    renderCard({
      result: result({
        review: {
          passed: false,
          blocked: true,
          issues: [
            { type: 'BUDGET_OVERRUN', severity: 'HIGH', message: '超出预算 12%' },
            { type: 'TRANSFER_TIME', severity: 'MEDIUM', message: '换乘时间偏紧' },
          ],
        },
      }),
    });

    const issues = screen.getByTestId('result-issues').textContent ?? '';
    expect(issues).toContain('高风险 · 超出预算 12%');
    expect(issues).toContain('需留意 · 换乘时间偏紧');
  });

  it('失败状态提供重试动作', () => {
    const onRetry = vi.fn();
    renderCard({ result: result({ status: 'FAILED', message: '模型超时' }), onRetry });

    expect(screen.getByTestId('result-status').textContent).toBe('失败');
    fireEvent.click(screen.getByRole('button', { name: /重试/ }));
    expect(onRetry).toHaveBeenCalledTimes(1);
  });

  it('有 tripId 时可打开行程', () => {
    const onOpenTrip = vi.fn();
    renderCard({ result: result({ tripId: 42 }), onOpenTrip });

    fireEvent.click(screen.getByRole('button', { name: /打开行程/ }));
    expect(onOpenTrip).toHaveBeenCalledWith(42);
  });

  it('已停止状态不提供「打开行程」，但可重试', () => {
    const onRetry = vi.fn();
    const onOpenTrip = vi.fn();
    renderCard({
      result: result({ status: 'CANCELLED', message: '已停止生成' }),
      onRetry,
      onOpenTrip,
    });

    expect(screen.getByTestId('result-status').textContent).toBe('已停止');
    expect(screen.queryByRole('button', { name: /打开行程/ })).toBeNull();
    fireEvent.click(screen.getByRole('button', { name: /重试/ }));
    expect(onRetry).toHaveBeenCalledTimes(1);
  });
});
