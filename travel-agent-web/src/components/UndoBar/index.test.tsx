// @vitest-environment happy-dom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { act, cleanup, fireEvent, render, screen } from '@testing-library/react';
import UndoBar from './index';

beforeEach(() => {
  vi.useFakeTimers();
});

afterEach(() => {
  cleanup();
  vi.useRealTimers();
});

function renderBar(patch: Partial<React.ComponentProps<typeof UndoBar>> = {}) {
  const onAction = vi.fn();
  const onExpire = vi.fn();
  render(
    <UndoBar
      token={1}
      message="已完成「外滩」"
      detail="已滚到下一项：豫园"
      onAction={onAction}
      onExpire={onExpire}
      {...patch}
    />,
  );
  return { onAction, onExpire };
}

describe('UndoBar 短时撤销', () => {
  it('展示发生了什么、接下来做了什么，并给出剩余秒数', () => {
    renderBar({ durationMs: 8000 });

    const bar = screen.getByTestId('undo-bar');
    expect(bar.textContent).toContain('已完成「外滩」');
    expect(bar.textContent).toContain('已滚到下一项：豫园');
    // 读屏用户也能听到这条状态
    expect(bar.getAttribute('aria-live')).toBe('polite');
    expect(screen.getByTestId('undo-action').textContent).toContain('撤销');
    expect(screen.getByTestId('undo-action').textContent).toContain('8s');
  });

  it('倒计时结束自动收起（调用 onExpire，不触发撤销）', () => {
    const { onAction, onExpire } = renderBar({ durationMs: 3000 });

    act(() => {
      vi.advanceTimersByTime(2999);
    });
    expect(onExpire).not.toHaveBeenCalled();

    act(() => {
      vi.advanceTimersByTime(1);
    });
    expect(onExpire).toHaveBeenCalledTimes(1);
    expect(onAction).not.toHaveBeenCalled();
  });

  it('点撤销触发一次 onAction，且不会同时触发超时回调', () => {
    const { onAction, onExpire } = renderBar({ durationMs: 3000 });

    fireEvent.click(screen.getByTestId('undo-action'));
    expect(onAction).toHaveBeenCalledTimes(1);

    act(() => {
      vi.advanceTimersByTime(3000);
    });
    expect(onExpire).not.toHaveBeenCalled();
  });

  it('关闭按钮收起窗口（等同超时，不撤销）', () => {
    const { onAction, onExpire } = renderBar();

    fireEvent.click(screen.getByLabelText('关闭撤销提示'));
    expect(onExpire).toHaveBeenCalledTimes(1);
    expect(onAction).not.toHaveBeenCalled();
  });

  it('剩余秒数随时钟递减', () => {
    renderBar({ durationMs: 4000 });
    expect(screen.getByTestId('undo-action').textContent).toContain('4s');

    act(() => {
      vi.advanceTimersByTime(2100);
    });
    expect(screen.getByTestId('undo-action').textContent).toContain('2s');
  });

  it('没有下一项时不渲染 detail 行', () => {
    renderBar({ detail: undefined });
    expect(screen.getByTestId('undo-bar').textContent).not.toContain('已滚到下一项');
  });
});
