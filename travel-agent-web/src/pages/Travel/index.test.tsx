// @vitest-environment happy-dom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { App } from 'antd';
import { MemoryRouter } from 'react-router-dom';
import Travel from './index';
import { useTripStore } from '../../store/tripStore';
import type { Trip } from '../../types/trip';

const api = vi.hoisted(() => ({
  listTrips: vi.fn(),
  deleteTrip: vi.fn(),
  updateTripStatus: vi.fn(),
  getTrip: vi.fn(),
  getItinerary: vi.fn(),
  getRouteLegs: vi.fn(),
}));

vi.mock('../../api/trip', () => api);

// 壳层与同页另外两个 tab 都不是被测对象：只验证行程列表的语义与状态分支
vi.mock('../../components/AppShell', () => ({
  default: ({ children }: { children: React.ReactNode }) => <div>{children}</div>,
}));
vi.mock('../Schedule', () => ({ default: () => <div data-testid="schedule-tab" /> }));
vi.mock('../Footprint', () => ({ default: () => <div data-testid="footprint-tab" /> }));

function trip(patch: Partial<Trip> = {}): Trip {
  return {
    id: 1,
    name: '上海 2 日游',
    destination: '上海',
    startDate: '2026-11-21',
    endDate: '2026-11-22',
    budget: 5000,
    status: 'CONFIRMED',
    phase: 'UPCOMING',
    daysUntilStart: 1,
    createdAt: '2026-11-01',
    ...patch,
  };
}

function renderPage() {
  return render(
    <MemoryRouter>
      <App>
        <Travel />
      </App>
    </MemoryRouter>,
  );
}

beforeEach(() => {
  useTripStore.setState({
    trips: [],
    tripsLoading: false,
    tripsError: null,
    currentTrip: null,
    items: [],
    routeLegs: [],
  });
  Object.values(api).forEach((fn) => fn.mockReset());
});

afterEach(() => {
  cleanup();
});

/** antd 会在两个汉字之间插入空格（「重 试」），可访问名称匹配要容忍它 */
const RETRY = /重\s*试/;

describe('Travel · 行程列表状态（P0-04）', () => {
  it('接口失败时显示错误与重试，绝不伪装成「还没有行程」', async () => {
    api.listTrips.mockRejectedValue(new Error('500 内部错误'));

    renderPage();

    expect(await screen.findByText('行程列表加载失败')).toBeTruthy();
    expect(screen.getByText('500 内部错误')).toBeTruthy();
    expect(screen.getByRole('button', { name: RETRY })).toBeTruthy();
    expect(screen.queryByText(/还没有行程/)).toBeNull();
  });

  it('真的没有数据时才显示空状态', async () => {
    api.listTrips.mockResolvedValue([]);

    renderPage();

    expect(await screen.findByText(/还没有行程/)).toBeTruthy();
    expect(screen.queryByText('行程列表加载失败')).toBeNull();
  });

  it('失败后点重试能恢复列表', async () => {
    api.listTrips.mockRejectedValueOnce(new Error('500 内部错误'));
    api.listTrips.mockResolvedValueOnce([trip()]);

    renderPage();

    expect(await screen.findByText('行程列表加载失败')).toBeTruthy();
    screen.getByRole('button', { name: RETRY }).click();

    expect(await screen.findByText('上海 2 日游')).toBeTruthy();
    expect(screen.queryByText('行程列表加载失败')).toBeNull();
  });
});

describe('Travel · 行程行的可访问语义（P0-05）', () => {
  const longName = '很长的行程标题用于验证换行与操作区不挤压';

  it('打开详情是按钮而不是可点击 div', async () => {
    api.listTrips.mockResolvedValue([trip({ name: longName })]);

    renderPage();

    // 标题文本所在的可点击祖先必须是 button：键盘可 Tab、可回车
    const title = await screen.findByText(longName);
    const openBtn = title.closest('button');
    expect(openBtn).toBeTruthy();
    expect(openBtn?.className).toContain('tr-open');
  });

  it('删除按钮有可访问名称，且不与主按钮混同', async () => {
    api.listTrips.mockResolvedValue([trip({ name: longName })]);

    renderPage();

    const openBtn = (await screen.findByText(longName)).closest('button');
    const deleteBtn = screen.getByRole('button', { name: `删除行程 ${longName}` });
    expect(deleteBtn).not.toBe(openBtn);
  });

  it('状态与金额仍在行内，操作不依赖 hover 才能使用', async () => {
    api.listTrips.mockResolvedValue([trip({ budget: 5000 })]);

    const { container } = renderPage();

    expect(await screen.findByText('¥5000')).toBeTruthy();
    // 行内动作按钮常驻可见（不靠 hover 才出现），文字是「取消」
    const actionBtn = container.querySelector('.trip-row .tr-right .ant-btn') as HTMLElement;
    expect(actionBtn).toBeTruthy();
    expect(actionBtn.textContent?.replace(/\s/g, '')).toBe('取消');
  });
});

describe('Travel · 我的行程（筛选 / 搜索 / 分页，阶段 B）', () => {
  const base = { budget: 1000, destination: '上海' };

  const fixtures = [
    trip({ id: 1, name: '进行中的上海', phase: 'ONGOING', status: 'CONFIRMED', ...base }),
    trip({ id: 2, name: '待确认的杭州', phase: 'UPCOMING', status: 'PLANNING', ...base }),
    trip({ id: 3, name: '将出发的成都', phase: 'UPCOMING', status: 'CONFIRMED', ...base }),
    trip({ id: 4, name: '已结束的西安', phase: 'PAST', status: 'CONFIRMED', ...base }),
    trip({ id: 5, name: '已取消的南京', phase: 'CANCELLED', status: 'CANCELLED', ...base }),
  ];

  it('页面标题与侧栏文案一致为「我的行程」', async () => {
    api.listTrips.mockResolvedValue([]);
    renderPage();
    expect(await screen.findByRole('heading', { level: 1, name: /我的行程/ })).toBeTruthy();
    expect(screen.queryByRole('heading', { level: 1, name: /旅行规划/ })).toBeNull();
  });

  it('默认排序：进行中 → 待确认 → 将出发 → 已结束 → 已取消', async () => {
    api.listTrips.mockResolvedValue(fixtures);

    const { container } = renderPage();

    // 渲染顺序 = 视觉顺序；用行标题（.tr-name）读取，避免与删除按钮的可访问名称混淆
    await screen.findByText('进行中的上海');
    const names = Array.from(container.querySelectorAll('.trip-row .tr-name')).map(
      (el) => el.textContent ?? '',
    );
    expect(names).toEqual([
      '进行中的上海',
      '待确认的杭州',
      '将出发的成都',
      '已结束的西安',
      '已取消的南京',
    ]);
  });

  it('状态筛选只显示该状态的行程', async () => {
    api.listTrips.mockResolvedValue(fixtures);

    renderPage();

    fireEvent.click(await screen.findByRole('button', { name: '已结束' }));

    expect(await screen.findByText('已结束的西安')).toBeTruthy();
    expect(screen.queryByText('进行中的上海')).toBeNull();
    expect(screen.queryByText('已取消的南京')).toBeNull();
  });

  it('搜索按名称/目的地过滤，并提供清除筛选', async () => {
    api.listTrips.mockResolvedValue(fixtures);

    renderPage();

    fireEvent.change(await screen.findByLabelText('搜索行程'), { target: { value: '杭州' } });

    expect(await screen.findByText('待确认的杭州')).toBeTruthy();
    expect(screen.queryByText('将出发的成都')).toBeNull();

    fireEvent.change(screen.getByLabelText('搜索行程'), { target: { value: '不存在的城市' } });
    expect(await screen.findByText(/没有符合条件的行程/)).toBeTruthy();

    fireEvent.click(screen.getByRole('button', { name: '清除筛选' }));
    expect(await screen.findByText('进行中的上海')).toBeTruthy();
  });

  it('行程多于单页时渲染翻页条，翻页后显示下一页', async () => {
    const many = Array.from({ length: 10 }, (_, i) =>
      trip({
        id: 100 + i,
        name: `第 ${i + 1} 个行程`,
        phase: 'UPCOMING',
        status: 'CONFIRMED',
        startDate: `2026-12-${String(i + 1).padStart(2, '0')}`,
        endDate: `2026-12-${String(i + 2).padStart(2, '0')}`,
        ...base,
      }),
    );
    api.listTrips.mockResolvedValue(many);

    renderPage();

    expect(await screen.findByText(/共 10 条/)).toBeTruthy();
    // 第一页 8 条
    expect(screen.getByText('第 1 个行程')).toBeTruthy();
    expect(screen.queryByText('第 9 个行程')).toBeNull();

    fireEvent.click(screen.getByRole('button', { name: /下一页/ }));

    expect(await screen.findByText('第 9 个行程')).toBeTruthy();
    expect(screen.queryByText('第 1 个行程')).toBeNull();
  });

  it('筛选无结果与真的没有行程给出不同文案', async () => {
    api.listTrips.mockResolvedValue([]);
    renderPage();
    expect(await screen.findByText(/还没有行程/)).toBeTruthy();
    expect(screen.queryByText(/没有符合条件的行程/)).toBeNull();
  });
});
