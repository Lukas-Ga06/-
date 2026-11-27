// @vitest-environment happy-dom
import { afterEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { App } from 'antd';
import Schedule from './index';

/**
 * 日程页三态契约（P0-04 / P1-E）：加载失败必须显示「失败 + 重试」，
 * 不能像改造前那样吞成一条 toast、再把列表渲染成「日程是空的」。
 */
const api = vi.hoisted(() => ({
  listSchedule: vi.fn(),
  addSchedule: vi.fn(),
  deleteSchedule: vi.fn(),
  toggleSchedule: vi.fn(),
}));

vi.mock('../../api/assistant', () => ({
  listSchedule: api.listSchedule,
  addSchedule: api.addSchedule,
  deleteSchedule: api.deleteSchedule,
  toggleSchedule: api.toggleSchedule,
}));

// 壳层不是本用例的被测对象
vi.mock('../../components/AppShell', () => ({
  default: ({ children }: { children: React.ReactNode }) => <div>{children}</div>,
}));

function renderPage() {
  return render(
    <App>
      <Schedule />
    </App>,
  );
}

afterEach(() => {
  cleanup();
  Object.values(api).forEach((fn) => fn.mockReset());
});

describe('Schedule · 加载中 / 空 / 失败三态', () => {
  it('加载失败显示可重试的错误，不能伪装成「日程是空的」', async () => {
    api.listSchedule.mockRejectedValue(new Error('500 内部错误'));

    renderPage();

    expect(await screen.findByText('日程加载失败')).toBeTruthy();
    expect(screen.getByText('500 内部错误')).toBeTruthy();
    expect(screen.queryByText('日程是空的')).toBeNull();
    expect(screen.getByTestId('schedule-retry')).toBeTruthy();
  });

  it('确实没有日程时才显示空态，且与失败文案区分', async () => {
    api.listSchedule.mockResolvedValue([]);

    renderPage();

    expect(await screen.findByTestId('schedule-empty')).toBeTruthy();
    expect(screen.queryByText('日程加载失败')).toBeNull();
  });

  it('点重试恢复列表，失败提示消失', async () => {
    api.listSchedule.mockRejectedValueOnce(new Error('网络错误'));
    api.listSchedule.mockResolvedValue([
      { id: 1, title: '开评审会', date: '2026-11-27', recurrence: 'NONE', done: false },
    ]);

    renderPage();
    await screen.findByText('日程加载失败');
    fireEvent.click(screen.getByTestId('schedule-retry'));

    expect(await screen.findByText('开评审会')).toBeTruthy();
    expect(screen.queryByText('日程加载失败')).toBeNull();
  });
});
