// @vitest-environment happy-dom
import { afterEach, describe, expect, it, vi } from 'vitest';
import { cleanup, render, screen } from '@testing-library/react';
import { App } from 'antd';
import { MemoryRouter } from 'react-router-dom';
import Expenses from './index';
import type { ExpenseOverview } from '../../types/expense';

const api = vi.hoisted(() => ({ getExpenseOverview: vi.fn() }));

vi.mock('../../api/expense', () => ({ getExpenseOverview: api.getExpenseOverview }));

// 壳层不是本用例的被测对象
vi.mock('../../components/AppShell', () => ({
  default: ({ children }: { children: React.ReactNode }) => <div>{children}</div>,
}));

afterEach(() => {
  cleanup();
  api.getExpenseOverview.mockReset();
});

/** antd 会在两个汉字之间插入空格（「重 试」），可访问名称匹配要容忍它 */
const RETRY = /重\s*试/;

function overview(patch: Partial<ExpenseOverview> = {}): ExpenseOverview {
  return {
    totalSpent: 45,
    monthSpent: 45,
    count: 1,
    byPaymentMethod: { 微信支付: 45 },
    byCategory: { 餐饮: 45 },
    byTrip: [{ tripId: 1, tripName: '杭州行', total: 45 }],
    recent: [
      {
        id: 1,
        tripId: 1,
        tripName: '杭州行',
        category: '餐饮',
        amount: 45,
        note: '午餐',
        paymentMethod: '微信支付',
        spentAt: '2026-11-27',
      },
    ],
    ...patch,
  };
}

function renderPage() {
  return render(
    <MemoryRouter>
      <App>
        <Expenses />
      </App>
    </MemoryRouter>,
  );
}

describe('Expenses · 状态语义（P1-E）', () => {
  it('加载失败显示错误与重试，不伪装成空账本', async () => {
    api.getExpenseOverview.mockRejectedValue(new Error('接口 500'));

    renderPage();

    expect(await screen.findByText('支出记录加载失败')).toBeTruthy();
    expect(screen.getByText('接口 500')).toBeTruthy();
    expect(screen.getByRole('button', { name: RETRY })).toBeTruthy();
    expect(screen.queryByText('还没有支出记录')).toBeNull();
  });

  it('真的没有记录时才显示空状态', async () => {
    api.getExpenseOverview.mockResolvedValue(overview({ count: 0, recent: [], byTrip: [] }));

    renderPage();

    expect(await screen.findByText('还没有支出记录')).toBeTruthy();
    expect(screen.queryByText('支出记录加载失败')).toBeNull();
  });

  it('失败后点重试能恢复概览', async () => {
    api.getExpenseOverview
      .mockRejectedValueOnce(new Error('接口 500'))
      .mockResolvedValueOnce(overview());

    renderPage();

    expect(await screen.findByText('支出记录加载失败')).toBeTruthy();
    screen.getByRole('button', { name: RETRY }).click();

    expect(await screen.findByText('1 条记录')).toBeTruthy();
    expect(screen.queryByText('支出记录加载失败')).toBeNull();
  });
});
