// @vitest-environment happy-dom
import { afterEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import ExecutionResultPanel from './index';
import type { ExecutionModule, ExecutionSummary } from '../../types/agent';

afterEach(cleanup);

function module(patch: Partial<ExecutionModule> = {}): ExecutionModule {
  return { module: 'calendar', status: 'SUCCEEDED', ...patch };
}

function execution(patch: Partial<ExecutionSummary> = {}): ExecutionSummary {
  return {
    executionId: 'exec-1',
    planId: 'plan-1',
    tripId: 7,
    status: 'PARTIAL_FAILED',
    modules: [
      module({ module: 'calendar', status: 'SUCCEEDED', detail: '已写入 8 个时间块' }),
      module({ module: 'budget', status: 'FAILED', error: '预算服务超时' }),
      module({ module: 'footprint', status: 'SUCCEEDED', detail: '已标记 3 个计划足迹' }),
    ],
    ...patch,
  };
}

describe('ExecutionResultPanel', () => {
  it('渲染日程/预算/足迹三个模块行与模块状态', () => {
    render(<ExecutionResultPanel execution={execution()} />);

    expect(screen.getByText('日程')).toBeTruthy();
    expect(screen.getByText('预算')).toBeTruthy();
    expect(screen.getByText('足迹')).toBeTruthy();
    // 两个 SUCCEEDED 模块都显示「已同步」
    expect(screen.getAllByText('已同步')).toHaveLength(2);
    expect(screen.getByText('失败')).toBeTruthy();
  });

  it('顶部状态行：PARTIAL_FAILED 显示「部分同步失败」', () => {
    const { container } = render(<ExecutionResultPanel execution={execution()} />);
    expect(container.querySelector('.exec-status')?.textContent).toContain('部分同步失败');
  });

  it('SUCCEEDED 显示「全部同步完成」，FAILED 显示「同步失败」', () => {
    const first = render(<ExecutionResultPanel execution={execution({ status: 'SUCCEEDED' })} />);
    expect(first.container.querySelector('.exec-status')?.textContent).toContain('全部同步完成');
    cleanup();

    const second = render(
      <ExecutionResultPanel
        execution={execution({ status: 'FAILED', modules: [module({ module: 'budget', status: 'FAILED' })] })}
      />,
    );
    expect(second.container.querySelector('.exec-status')?.textContent).toContain('同步失败');
    // FAILED 状态下的提示语也渲染
    expect(second.container.textContent).toContain('成功项不会重复创建');
  });

  it('FAILED 行有重试按钮，点击触发 onRetry；SUCCEEDED 行没有按钮', () => {
    const onRetry = vi.fn();
    render(<ExecutionResultPanel execution={execution()} onRetry={onRetry} />);

    const okRow = screen.getByText('日程').closest('.exec-module-row') as HTMLElement;
    const failRow = screen.getByText('预算').closest('.exec-module-row') as HTMLElement;
    expect(okRow?.querySelector('.em-retry-btn')).toBeNull();
    expect(failRow?.querySelector('.em-retry-btn')).toBeTruthy();

    fireEvent.click(failRow.querySelector('.em-retry-btn') as HTMLElement);
    expect(onRetry).toHaveBeenCalledTimes(1);
  });

  it('失败模块展示 error 与「成功项不会重复创建」的部分成功语义', () => {
    render(<ExecutionResultPanel execution={execution()} />);

    expect(screen.getByText('预算服务超时')).toBeTruthy();
    expect(screen.getByText(/成功项不会重复创建/)).toBeTruthy();
  });

  it('未传 onRetry 时失败行不渲染重试按钮', () => {
    render(<ExecutionResultPanel execution={execution()} />);
    expect(screen.queryByText('重试')).toBeNull();
  });

  it('失败行渲染 error 文案与 detail', () => {
    render(
      <ExecutionResultPanel
        execution={execution({
          modules: [module({ module: 'budget', status: 'FAILED', detail: '快照创建失败', error: '服务 500' })],
        })}
      />,
    );
    expect(screen.getByText('快照创建失败')).toBeTruthy();
    expect(screen.getByText('服务 500')).toBeTruthy();
  });
});
