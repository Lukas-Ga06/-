// @vitest-environment happy-dom
import { afterEach, describe, expect, it, vi } from 'vitest';
import { cleanup, render, screen } from '@testing-library/react';
import { App } from 'antd';
import { MemoryRouter } from 'react-router-dom';
import Reviews from './index';
import type { Trip } from '../../types/trip';

const api = vi.hoisted(() => ({ listTrips: vi.fn() }));

vi.mock('../../api/trip', () => ({ listTrips: api.listTrips }));

vi.mock('../../api/reminder', () => ({
  getReminders: vi.fn(() =>
    Promise.resolve({ items: [], count: 0, serverTime: new Date().toISOString() }),
  ),
  dismissReminder: vi.fn(),
}));

afterEach(() => {
  cleanup();
  api.listTrips.mockReset();
});

function trip(patch: Partial<Trip>): Trip {
  return {
    id: 1,
    name: '行程',
    destination: '上海',
    startDate: '2026-01-01',
    endDate: '2026-01-02',
    budget: 1000,
    status: 'CONFIRMED',
    phase: 'UPCOMING',
    daysUntilStart: 10,
    createdAt: '2025-12-01',
    ...patch,
  };
}

function renderPage() {
  return render(
    <MemoryRouter>
      <App>
        <Reviews />
      </App>
    </MemoryRouter>,
  );
}

describe('Reviews · 旅行回顾列表', () => {
  it('可回顾列表只含进行中/已结束，未来行程单列且不给回顾入口', async () => {
    api.listTrips.mockResolvedValue([
      trip({ id: 1, destination: '上海', phase: 'UPCOMING', startDate: '2026-12-01' }),
      trip({ id: 2, destination: '杭州', phase: 'PAST', startDate: '2026-03-01' }),
      trip({ id: 3, destination: '成都', phase: 'ONGOING', startDate: '2026-11-18' }),
    ]);

    renderPage();

    const list = await screen.findByTestId('reviews-list');
    const rows = Array.from(list.querySelectorAll('[data-testid^="review-trip-"]'));
    expect(rows.map((row) => row.getAttribute('data-testid'))).toEqual([
      'review-trip-3',
      'review-trip-2',
    ]);
    expect(screen.getAllByRole('button', { name: /查看回顾/ })).toHaveLength(2);

    // 待出发行程仍然可见（用户要能找到订好的行程），但不提供「查看回顾」
    const upcoming = screen.getByTestId('reviews-upcoming');
    expect(upcoming.textContent).toContain('待出发（尚不可回顾）');
    expect(upcoming.querySelector('[data-testid="review-trip-1"]')).toBeTruthy();
    const upcomingButtons = upcoming.querySelectorAll('button');
    // antd 会在两个中文字之间插空格（「行程」→「行 程」），只断言数量与文案含关键字
    expect(upcomingButtons).toHaveLength(1);
    expect(upcomingButtons[0].textContent).toContain('行');
  });

  it('只有未来行程时，可回顾列表为空但待出发分组仍在', async () => {
    api.listTrips.mockResolvedValue([trip({ id: 1, phase: 'UPCOMING' })]);

    renderPage();

    expect(await screen.findByTestId('reviews-upcoming')).toBeTruthy();
    expect(screen.queryByTestId('reviews-list')).toBeNull();
    expect(screen.queryByText('还没有可回顾的行程')).toBeNull();
  });

  it('无行程时展示空态与规划入口', async () => {
    api.listTrips.mockResolvedValue([]);

    renderPage();

    expect(await screen.findByText('还没有可回顾的行程')).toBeTruthy();
    expect(screen.getByRole('button', { name: /去规划一次旅行/ })).toBeTruthy();
  });

  it('加载失败时给出重试，而不是伪装成空数据', async () => {
    api.listTrips.mockRejectedValue(new Error('接口 500'));

    renderPage();

    expect(await screen.findByText('接口 500')).toBeTruthy();
    expect(screen.getByRole('button', { name: /重\s*试/ })).toBeTruthy();
    expect(screen.queryByText('还没有可回顾的行程')).toBeNull();
  });
});
