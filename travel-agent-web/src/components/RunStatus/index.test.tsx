// @vitest-environment happy-dom
import { afterEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import RunStatus from './index';

afterEach(cleanup);

describe('RunStatus · 统一状态语义（P1-E）', () => {
  it('每种状态都有图标 + 文字，不只靠颜色', () => {
    const { container } = render(<RunStatus status="partial" text="部分同步失败" testId="rs" />);
    const node = screen.getByTestId('rs');
    expect(node.getAttribute('data-status')).toBe('partial');
    expect(node.textContent).toContain('部分同步失败');
    expect(container.querySelector('.rs-icon svg')).toBeTruthy();
    expect(node.className).toContain('run-status--warn');
  });

  it('失败状态可以接下一步操作', () => {
    const onRetry = vi.fn();
    render(
      <RunStatus
        status="failed"
        text="同步失败"
        detail="预算服务超时"
        actions={[{ label: '重试', onClick: onRetry }]}
      />,
    );

    expect(screen.getByText('预算服务超时')).toBeTruthy();
    fireEvent.click(screen.getByRole('button', { name: /重\s*试/ }));
    expect(onRetry).toHaveBeenCalledTimes(1);
  });

  it('纯文字场景可以关掉图标（例如文案本身已有 ⚠ 符号）', () => {
    const { container } = render(
      <RunStatus status="degraded" text="⚠ 部分内容待核实" showIcon={false} />,
    );
    expect(container.querySelector('.rs-icon')).toBeNull();
    expect(screen.getByText('⚠ 部分内容待核实')).toBeTruthy();
  });

  it('loading 使用旋转图标，complete / cancelled / empty 各有独立文案', () => {
    const first = render(<RunStatus status="loading" />);
    expect(first.container.querySelector('.rs-icon .anticon-spin')).toBeTruthy();
    expect(first.container.textContent).toContain('进行中');
    cleanup();

    const second = render(<RunStatus status="complete" />);
    expect(second.container.textContent).toContain('完成');
    cleanup();

    const third = render(<RunStatus status="cancelled" />);
    expect(third.container.textContent).toContain('已取消');
    cleanup();

    const fourth = render(<RunStatus status="empty" />);
    expect(fourth.container.textContent).toContain('暂无内容');
  });
});
