// @vitest-environment happy-dom
import { afterEach, describe, expect, it } from 'vitest';
import { cleanup, render, screen } from '@testing-library/react';
import BudgetExecutionPanel from './index';
import type { BudgetExecution } from '../../types/expense';

afterEach(cleanup);

function execution(patch: Partial<BudgetExecution> = {}): BudgetExecution {
  return {
    tripId: 1,
    tripName: '上海 3 日',
    budget: 1000,
    planned: 800,
    actual: 1200,
    remaining: -200,
    variance: 400,
    executionRate: 1.2,
    overBudget: true,
    planOverBudget: false,
    categories: [{ category: '餐饮', planned: 300, actual: 400 }],
    daily: [
      { day: 0, planned: 0, actual: 120, variance: 120 },
      { day: 1, planned: 200, actual: 150, variance: -50 },
    ],
    ...patch,
  };
}

describe('BudgetExecutionPanel', () => {
  it('渲染「预算使用率」一格：executionRate 是比例，展示成百分比', () => {
    const { container } = render(<BudgetExecutionPanel data={execution()} />);

    expect(screen.getByText('预算使用率')).toBeTruthy();
    expect(container.textContent).toContain('120%');
  });

  it('使用率超过 100% 时套用现有 danger 样式', () => {
    render(<BudgetExecutionPanel data={execution()} />);

    const cell = screen.getByText('预算使用率').parentElement as HTMLElement;
    expect(cell.querySelector('b')?.className).toContain('over');
  });

  it('使用率未超 100% 时不套 danger 样式', () => {
    render(
      <BudgetExecutionPanel
        data={execution({
          executionRate: 0.856,
          actual: 856,
          remaining: 144,
          variance: 56,
          overBudget: false,
        })}
      />,
    );

    const cell = screen.getByText('预算使用率').parentElement as HTMLElement;
    expect(cell.textContent).toContain('86%');
    expect(cell.querySelector('b')?.className).not.toContain('over');
  });

  it('「较计划」正偏差带 + 号', () => {
    const { container } = render(<BudgetExecutionPanel data={execution()} />);
    expect(container.textContent).toContain('+¥400');
  });

  it('负偏差带 - 号，且符号在 ¥ 之前', () => {
    const { container } = render(
      <BudgetExecutionPanel data={execution({ variance: -300, actual: 500, planned: 800 })} />,
    );
    expect(container.textContent).toContain('-¥300');
    expect(container.textContent).not.toContain('¥-300');
  });

  it('行程外的支出单独成行：标注「行程外」、计划位占位、偏差带 + 号', () => {
    render(<BudgetExecutionPanel data={execution()} />);

    const row = screen.getByText('行程外').closest('.be-cat') as HTMLElement;
    expect(row).toBeTruthy();
    expect(row.textContent).toContain('—');
    expect(row.textContent).toContain('实际 ¥120');
    expect(row.textContent).toContain('+¥120');
  });

  it('无预算时使用率显示占位而不是 0%', () => {
    const { container } = render(
      <BudgetExecutionPanel
        data={execution({ budget: null, remaining: null, executionRate: null, overBudget: false })}
      />,
    );

    const cell = screen.getByText('预算使用率').parentElement as HTMLElement;
    expect(cell.textContent).toContain('—');
    expect(cell.querySelector('b')?.className).not.toContain('over');
    expect(container.textContent).not.toContain('NaN');
  });

  it('旧后端不返回 daily 时降级为空明细，不抛错也不影响其它区块', () => {
    const data = execution();
    delete (data as Partial<BudgetExecution>).daily;

    const { container } = render(<BudgetExecutionPanel data={data} />);

    expect(screen.queryByText('行程外')).toBeNull();
    expect(container.textContent).toContain('实际支出');
    expect(container.textContent).toContain('120%');
  });

  it('data 为 null 时不渲染任何内容', () => {
    const { container } = render(<BudgetExecutionPanel data={null} />);
    expect(container.querySelector('.budget-exec')).toBeNull();
  });
});
