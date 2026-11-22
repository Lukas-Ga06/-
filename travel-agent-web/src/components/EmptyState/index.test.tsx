// @vitest-environment happy-dom
import { afterEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { App } from 'antd';
import EmptyState from './index';

afterEach(cleanup);

function renderState(props: Parameters<typeof EmptyState>[0]) {
  return render(
    <App>
      <EmptyState {...props} />
    </App>,
  );
}

describe('EmptyState · 统一空状态（阶段 E）', () => {
  it('标题、引导与唯一主操作一起渲染', () => {
    const onClick = vi.fn();
    renderState({
      testId: 'x-empty',
      icon: '🧳',
      title: '还没有行程',
      hint: '点右上角新建行程',
      action: { label: '去规划', onClick },
    });

    expect(screen.getByTestId('x-empty').getAttribute('role')).toBe('status');
    expect(screen.getByText('还没有行程')).toBeTruthy();
    expect(screen.getByText('点右上角新建行程')).toBeTruthy();

    fireEvent.click(screen.getByRole('button', { name: /去规划/ }));
    expect(onClick).toHaveBeenCalledTimes(1);
  });

  it('没有 action 时只渲染说明，不伪造按钮（页面已有主动作时用这种）', () => {
    renderState({ title: '其余日期暂无日程' });

    expect(screen.getByText('其余日期暂无日程')).toBeTruthy();
    expect(screen.queryByRole('button')).toBeNull();
  });

  it('图标是装饰性的，不进可访问名称', () => {
    renderState({ icon: '📍', title: '还没有足迹' });

    const icon = document.querySelector('.es-icon');
    expect(icon?.getAttribute('aria-hidden')).toBe('true');
  });
});
