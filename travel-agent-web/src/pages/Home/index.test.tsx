// @vitest-environment happy-dom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, render, screen } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { App } from 'antd';
import Home from './index';
import type { TripMapPoint } from '../../types/trip';

const api = vi.hoisted(() => ({
  getTripsMap: vi.fn(),
  getExpenseOverview: vi.fn(),
  getMemos: vi.fn(),
  toggleMemo: vi.fn(),
  listSchedule: vi.fn(),
  getReminders: vi.fn(),
}));

vi.mock('../../api/trip', () => ({ getTripsMap: api.getTripsMap }));
vi.mock('../../api/expense', () => ({ getExpenseOverview: api.getExpenseOverview }));
vi.mock('../../api/memo', () => ({ getMemos: api.getMemos, toggleMemo: api.toggleMemo }));
vi.mock('../../api/assistant', () => ({ listSchedule: api.listSchedule }));
vi.mock('../../api/reminder', () => ({ getReminders: api.getReminders }));

// 壳层与地图、城市抽屉都不是本用例的被测对象
vi.mock('../../components/AppShell', () => ({
  default: ({ children }: { children: React.ReactNode }) => <div>{children}</div>,
}));
vi.mock('../../components/CityDrawer', () => ({ default: () => null }));
vi.mock('../../components/FootprintMap', () => ({ default: () => <div data-testid="fp-map" /> }));

function point(patch: Partial<TripMapPoint> = {}): TripMapPoint {
  return {
    tripId: 1,
    name: '上海两日游',
    destination: '上海',
    status: 'CONFIRMED',
    startDate: '2026-12-01',
    endDate: '2026-12-02',
    budget: 3000,
    lat: 31.23,
    lng: 121.47,
    phase: 'UPCOMING',
    visited: false,
    ...patch,
  };
}

function renderPage() {
  return render(
    <MemoryRouter>
      <App>
        <Home />
      </App>
    </MemoryRouter>,
  );
}

beforeEach(() => {
  api.getTripsMap.mockResolvedValue([]);
  api.getExpenseOverview.mockResolvedValue({ monthSpent: 0, byTrip: [] });
  api.getMemos.mockResolvedValue([]);
  api.listSchedule.mockResolvedValue([]);
  api.getReminders.mockResolvedValue({ items: [], count: 0, serverTime: new Date().toISOString() });
});

afterEach(() => {
  cleanup();
  Object.values(api).forEach((fn) => fn.mockReset());
});

describe('Home · 首屏收敛（阶段 B）', () => {
  it('首屏只有一个主输入 + 下一次旅行 + 今日/待处理三块，不再展示运行诊断', async () => {
    api.getTripsMap.mockResolvedValue([point()]);

    renderPage();

    // 下一次旅行优先展示，并直接给出唯一主动作
    expect(await screen.findByTestId('home-next-trip')).toBeTruthy();
    expect(screen.getByTestId('home-open-trip')).toBeTruthy();
    expect(screen.getByTestId('home-todo')).toBeTruthy();
    expect(screen.getByTestId('home-overview')).toBeTruthy();

    // 最近 Agent 运行已迁入「设置 > 诊断」，不再占首页
    expect(screen.queryByText(/最近 Agent 运行/)).toBeNull();
    expect(screen.queryByText(/运行观察/)).toBeNull();
  });

  it('没有已打卡足迹时不渲染地图，只给紧凑空状态与一个主按钮', async () => {
    api.getTripsMap.mockResolvedValue([point({ visited: false })]);

    renderPage();

    expect(await screen.findByTestId('home-footprint-empty')).toBeTruthy();
    expect(screen.getByText('还没有已打卡的足迹')).toBeTruthy();
    expect(screen.getByTestId('home-footprint-empty-plan')).toBeTruthy();
    expect(screen.queryByTestId('fp-map')).toBeNull();
  });

  it('有已打卡足迹时才渲染地图', async () => {
    api.getTripsMap.mockResolvedValue([point({ visited: true })]);

    renderPage();

    expect(await screen.findByTestId('fp-map')).toBeTruthy();
    expect(screen.queryByTestId('home-footprint-empty')).toBeNull();
  });

  it('四次常用入口直接表达任务，不只是一句示例', async () => {
    renderPage();

    const plan = await screen.findByTestId('home-quick-plan');
    expect(plan.textContent).toContain('规划旅行');
    expect(screen.getByTestId('home-quick-weather').textContent).toContain('根据天气调整');
    expect(screen.getByTestId('home-quick-expense').textContent).toContain('记一笔消费');
    expect(screen.getByTestId('home-quick-guide').textContent).toContain('查询攻略');
  });

  it('进行中的行程优先于未来行程', async () => {
    const today = new Date().toISOString().slice(0, 10);
    api.getTripsMap.mockResolvedValue([
      point({ tripId: 9, name: '未来的杭州', destination: '杭州', startDate: '2099-01-01' }),
      point({
        tripId: 7,
        name: '正在进行的成都',
        destination: '成都',
        startDate: today,
        endDate: today,
        phase: 'ONGOING',
      }),
    ]);

    renderPage();

    expect(await screen.findByText('正在进行的成都')).toBeTruthy();
    expect(screen.getByText('正在进行')).toBeTruthy();
    expect(screen.queryByText('未来的杭州')).toBeNull();
  });

  it('没有行程时给出单一 CTA「规划第一次旅行」', async () => {
    renderPage();

    expect(await screen.findByTestId('home-plan-first')).toBeTruthy();
    expect(screen.getAllByText(/还没有安排中的旅行/)).toHaveLength(1);
  });

  it('待处理区显示待确认方案数与提醒数', async () => {
    api.getTripsMap.mockResolvedValue([
      point({ tripId: 1, status: 'PLANNING', name: '待确认 A' }),
      point({ tripId: 2, status: 'PLANNING', name: '待确认 B' }),
    ]);
    api.getReminders.mockResolvedValue({
      items: [],
      count: 3,
      serverTime: new Date().toISOString(),
    });

    renderPage();

    expect(await screen.findByTestId('pending-plans')).toBeTruthy();
    expect(screen.getByText('个方案待确认')).toBeTruthy();
    expect(screen.getByText('个提醒待处理')).toBeTruthy();
    // 数字是 CountUp 缓动出来的（默认 900ms），负载高时要等它落定，不能用同步 getByText
    expect(await screen.findByText('2', {}, { timeout: 3000 })).toBeTruthy();
  });

  it('接口失败时指标显示「—」而不是 0，并给出重试（P0-04）', async () => {
    api.getExpenseOverview.mockRejectedValue(new Error('500 内部错误'));
    api.getReminders.mockRejectedValue(new Error('500 内部错误'));

    renderPage();

    expect(await screen.findByText('部分内容加载失败')).toBeTruthy();
    // 失败指标：破折号 + 明确标注，绝不显示 ¥0 / 0
    expect(screen.getAllByText('—').length).toBeGreaterThanOrEqual(2);
    expect(screen.queryByText('¥0')).toBeNull();
    expect(screen.getAllByText(/加载失败/).length).toBeGreaterThan(0);
    expect(screen.getByRole('button', { name: /重\s*试/ })).toBeTruthy();
  });

  it('待办加载失败时不显示「暂无待办」', async () => {
    api.getMemos.mockRejectedValue(new Error('500 内部错误'));

    renderPage();

    await screen.findByText('部分内容加载失败');
    expect(screen.queryByText(/暂无待办/)).toBeNull();
  });
});
