// @vitest-environment happy-dom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { App } from 'antd';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import TripPage from './index';
import { useTripStore } from '../../store/tripStore';
import { useStatusQueueStore } from '../../store/statusQueueStore';
import type { ItineraryItem, Trip } from '../../types/trip';

/**
 * 行程详情页的结构化布局与行中执行流程（阶段 D）：
 * 移动端「行程 / 地图 / AI」分段、桌面「时间轴主栏 + 地图/AI 侧栏」、页头按阶段收敛，
 * 以及 §7.3 第 3/4 条：完成后自动滚到下一项 + 短时撤销、离线变更进待同步队列。
 * 地图、聊天、时间轴各自有独立测试，这里只验证它们的挂载/切换与状态流转。
 */
vi.mock('../../components/AppShell', () => ({
  default: ({ children }: { children: React.ReactNode }) => <div>{children}</div>,
}));
vi.mock('../../components/MapView', () => ({ default: () => <div data-testid="map-view" /> }));
vi.mock('../../components/ChatPanel', () => ({
  default: () => <div data-testid="chat-panel" />,
}));
// 时间轴用可交互替身：把「完成某一条」暴露成按钮，验证页面侧的状态流转
vi.mock('../../components/Timeline', () => ({
  default: ({
    onItemStatusChange,
    pendingSyncIds,
  }: {
    onItemStatusChange?: (itemId: number, status: string) => void;
    pendingSyncIds?: Set<number>;
  }) => (
    <div data-testid="timeline">
      <button data-testid="complete-item-1" onClick={() => onItemStatusChange?.(1, 'COMPLETED')}>
        完成 1
      </button>
      <span data-testid="pending-ids">{Array.from(pendingSyncIds ?? []).join(',')}</span>
    </div>
  ),
}));
vi.mock('../../components/PlanDiff', () => ({ default: () => null }));
vi.mock('../../components/BudgetChart', () => ({ default: () => null }));
vi.mock('../../components/BudgetExecution', () => ({ default: () => null }));
vi.mock('../../components/ExpensePanel', () => ({ default: () => null }));

const api = vi.hoisted(() => ({
  getTrip: vi.fn(),
  getItinerary: vi.fn(),
  getRouteLegs: vi.fn(),
  getBudgetExecution: vi.fn(),
  updateItemStatus: vi.fn(),
  listTrips: vi.fn(),
}));
vi.mock('../../api/trip', () => api);
vi.mock('../../api/agent', () => ({ undoTrip: vi.fn() }));

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
  } as Trip;
}

/** 同一天两条相邻安排：外滩(1) → 豫园(2) */
function twoItems(): ItineraryItem[] {
  return [
    {
      id: 1,
      dayIndex: 1,
      itemType: 'attraction',
      title: '外滩',
      startTime: '09:00',
      endTime: '10:00',
      latitude: 31.23,
      longitude: 121.47,
      cost: 0,
      status: 'PENDING',
    } as ItineraryItem,
    {
      id: 2,
      dayIndex: 1,
      itemType: 'attraction',
      title: '豫园',
      startTime: '11:00',
      endTime: '12:00',
      latitude: 31.24,
      longitude: 121.48,
      cost: 0,
      status: 'PENDING',
    } as ItineraryItem,
  ];
}

/** 把视口固定成移动端 / 桌面端（页面按 720px 断点切换结构） */
function stubViewport(mobile: boolean) {
  const mql = (query: string) => ({
    matches: mobile && query.includes('720px'),
    media: query,
    onchange: null,
    addEventListener: () => undefined,
    removeEventListener: () => undefined,
    addListener: () => undefined,
    removeListener: () => undefined,
    dispatchEvent: () => false,
  });
  Object.defineProperty(window, 'matchMedia', { writable: true, configurable: true, value: mql });
}

function setNavigatorOnline(value: boolean) {
  Object.defineProperty(navigator, 'onLine', { configurable: true, get: () => value });
}

function renderPage() {
  return render(
    <MemoryRouter initialEntries={['/trip/1']}>
      <App>
        <Routes>
          <Route path="/trip/:id" element={<TripPage />} />
        </Routes>
      </App>
    </MemoryRouter>,
  );
}

beforeEach(() => {
  setNavigatorOnline(true);
  localStorage.clear();
  useStatusQueueStore.setState({
    entries: [],
    syncing: false,
    lastResults: [],
    hydrated: false,
  });
  useTripStore.setState({
    currentTrip: trip({ phase: 'ONGOING' }),
    items: twoItems(),
    routeLegs: [],
    trips: [],
    tripsError: null,
  });
  api.getTrip.mockResolvedValue(trip({ phase: 'ONGOING' }));
  api.getItinerary.mockResolvedValue(twoItems());
  api.getRouteLegs.mockResolvedValue([]);
  api.getBudgetExecution.mockResolvedValue(null);
  api.updateItemStatus.mockResolvedValue({});
});

afterEach(() => {
  cleanup();
  Object.values(api).forEach((fn) => fn.mockReset());
  Reflect.deleteProperty(navigator, 'onLine');
});

describe('Trip · 移动端分段视图（P1-04）', () => {
  it('默认显示行程，地图与 AI 是互斥的辅助视图', () => {
    stubViewport(true);
    renderPage();

    expect(screen.getByTestId('timeline')).toBeTruthy();
    expect(screen.queryByTestId('map-view')).toBeNull();
    expect(screen.queryByTestId('chat-panel')).toBeNull();

    const switchBar = screen.getByTestId('trip-view-switch');
    fireEvent.click(within(switchBar).getByText('地图'));
    expect(screen.getByTestId('map-view')).toBeTruthy();
    expect(screen.queryByTestId('timeline')).toBeNull();

    fireEvent.click(within(switchBar).getByText('AI'));
    expect(screen.getByTestId('chat-panel')).toBeTruthy();
    expect(screen.queryByTestId('map-view')).toBeNull();

    fireEvent.click(within(switchBar).getByText('行程'));
    expect(screen.getByTestId('timeline')).toBeTruthy();
  });
});

describe('Trip · 桌面工作台（§8.5）', () => {
  it('时间轴做主栏，地图/AI 共享右侧面板', () => {
    stubViewport(false);
    renderPage();

    // 主栏与侧栏同时存在：时间轴不再被地图/聊天推到首屏之外
    expect(screen.getByTestId('timeline')).toBeTruthy();
    expect(screen.getByTestId('map-view')).toBeTruthy();
    // 侧栏切换不影响主栏
    fireEvent.click(within(screen.getByTestId('trip-side-switch')).getByText('AI 助手'));
    expect(screen.getByTestId('timeline')).toBeTruthy();
  });

  it('侧栏切换只切可见性：地图不卸载重建，缩放状态才不会被重置', () => {
    stubViewport(false);
    renderPage();

    const mapPane = () => screen.getByTestId('trip-side-pane-map');
    const aiPane = () => screen.getByTestId('trip-side-pane-ai');
    expect(mapPane().getAttribute('data-active')).toBe('true');
    expect(aiPane().getAttribute('data-active')).toBe('false');

    const switchBar = screen.getByTestId('trip-side-switch');
    fireEvent.click(within(switchBar).getByText('AI 助手'));

    // 地图仍然挂载（同一个实例，不会被重新初始化），只是不再可见/可交互
    expect(screen.getByTestId('map-view')).toBeTruthy();
    expect(mapPane().getAttribute('data-active')).toBe('false');
    expect(mapPane().getAttribute('aria-hidden')).toBe('true');
    expect(aiPane().getAttribute('data-active')).toBe('true');
    expect(screen.getByTestId('chat-panel')).toBeTruthy();

    fireEvent.click(within(switchBar).getByText('地图'));
    expect(mapPane().getAttribute('data-active')).toBe('true');
    expect(screen.getByTestId('map-view')).toBeTruthy();
  });
});

describe('Trip · 页头按阶段收敛（P1-05）', () => {
  it('行中阶段一级动作是记账，回顾/路书/预算进更多菜单', async () => {
    stubViewport(false);
    renderPage();

    expect(await screen.findByRole('button', { name: /记\s*账/ })).toBeTruthy();
    // 回顾、路书不再占页头一级按钮
    expect(screen.queryByRole('button', { name: /旅行回顾/ })).toBeNull();
    expect(screen.queryByRole('button', { name: /路书/ })).toBeNull();
    expect(screen.getByRole('button', { name: '更多行程操作' })).toBeTruthy();
  });

  it('行前阶段一级动作是预算分析', async () => {
    stubViewport(false);
    useTripStore.setState({ currentTrip: trip({ phase: 'UPCOMING' }) });
    api.getTrip.mockResolvedValue(trip({ phase: 'UPCOMING' }));

    renderPage();

    expect(await screen.findByRole('button', { name: /预算分析/ })).toBeTruthy();
    expect(screen.queryByRole('button', { name: /记\s*账/ })).toBeNull();
  });
});

describe('Trip · 完成一项后自动滚到下一项 + 短时撤销（§7.3 第 3 条）', () => {
  it('提交成功后给出撤销窗口，并说明已经滚到哪一项', async () => {
    stubViewport(false);
    renderPage();

    // 服务端确认后的行程：第一条已完成
    api.getItinerary.mockResolvedValue([{ ...twoItems()[0], status: 'COMPLETED' }, twoItems()[1]]);

    await waitFor(() => expect(useTripStore.getState().items).toHaveLength(2));
    fireEvent.click(screen.getByTestId('complete-item-1'));

    await waitFor(() => expect(api.updateItemStatus).toHaveBeenCalledWith(1, 'COMPLETED'));
    const bar = await screen.findByTestId('undo-bar');
    expect(bar.textContent).toContain('已完成「外滩」');
    expect(bar.textContent).toContain('已滚到下一项：豫园');
    expect(screen.getByTestId('undo-action')).toBeTruthy();
  });

  it('点撤销把状态写回上一个值（撤销同样进服务端，不是只改本地）', async () => {
    stubViewport(false);
    renderPage();

    api.getItinerary.mockResolvedValue([{ ...twoItems()[0], status: 'COMPLETED' }, twoItems()[1]]);
    await waitFor(() => expect(useTripStore.getState().items).toHaveLength(2));
    fireEvent.click(screen.getByTestId('complete-item-1'));
    await screen.findByTestId('undo-bar');

    fireEvent.click(screen.getByTestId('undo-action'));

    await waitFor(() => expect(api.updateItemStatus).toHaveBeenCalledWith(1, 'PENDING'));
    await waitFor(() => expect(screen.queryByTestId('undo-bar')).toBeNull());
  });

  it('没有下一项时文案说明已全部处理完，而不是留空', async () => {
    stubViewport(false);
    useTripStore.setState({ items: [twoItems()[0]] });
    api.getItinerary.mockResolvedValue([{ ...twoItems()[0], status: 'COMPLETED' }]);
    renderPage();

    await waitFor(() => expect(useTripStore.getState().items).toHaveLength(1));
    fireEvent.click(screen.getByTestId('complete-item-1'));

    const bar = await screen.findByTestId('undo-bar');
    expect(bar.textContent).toContain('全部安排都处理完了');
  });
});

describe('Trip · 离线变更进待同步队列（§7.3 第 4 条）', () => {
  it('离线时本地记录 + 标记待同步，不发请求也不报错', async () => {
    stubViewport(false);
    setNavigatorOnline(false);
    renderPage();

    await waitFor(() => expect(useTripStore.getState().items).toHaveLength(2));
    fireEvent.click(screen.getByTestId('complete-item-1'));

    // 不向服务端发请求：离线时「等服务器确认」只会得到一个红色错误提示
    expect(api.updateItemStatus).not.toHaveBeenCalled();
    // 队列里有一条待同步变更，时间轴拿到待同步 id
    await waitFor(() => expect(useStatusQueueStore.getState().entries).toHaveLength(1));
    const queued = useStatusQueueStore.getState().entries[0];
    expect(queued).toMatchObject({
      tripId: 1,
      itemId: 1,
      status: 'COMPLETED',
      previousStatus: 'PENDING',
      title: '外滩',
    });
    expect(screen.getByTestId('pending-ids').textContent).toBe('1');
    // 页头给出总数，撤销窗口说明这是「待同步」而不是「已完成」
    expect(screen.getByTestId('trip-pending-sync').textContent).toContain('1 项待同步');
    expect(screen.getByTestId('undo-bar').textContent).toContain('待同步');
  });

  it('撤销待同步变更只是出队，不产生服务端请求', async () => {
    stubViewport(false);
    setNavigatorOnline(false);
    renderPage();

    await waitFor(() => expect(useTripStore.getState().items).toHaveLength(2));
    fireEvent.click(screen.getByTestId('complete-item-1'));
    await screen.findByTestId('undo-bar');
    fireEvent.click(screen.getByTestId('undo-action'));

    await waitFor(() => expect(useStatusQueueStore.getState().entries).toHaveLength(0));
    expect(api.updateItemStatus).not.toHaveBeenCalled();
  });

  it('在线但请求发不出去（网络错误）时同样降级为待同步', async () => {
    stubViewport(false);
    api.updateItemStatus.mockRejectedValue(new Error('Network Error'));
    renderPage();

    await waitFor(() => expect(useTripStore.getState().items).toHaveLength(2));
    fireEvent.click(screen.getByTestId('complete-item-1'));

    await waitFor(() => expect(useStatusQueueStore.getState().entries).toHaveLength(1));
    expect(screen.getByTestId('undo-bar').textContent).toContain('待同步');
  });

  it('409 冲突时提示「此行程已在其他位置更新」，并重拉行程/时间轴（阶段 D）', async () => {
    stubViewport(false);
    const conflict = Object.assign(new Error('数据已被其他操作修改，请刷新后重试'), {
      status: 409,
    });
    api.updateItemStatus.mockRejectedValue(conflict);
    renderPage();

    await waitFor(() => expect(useTripStore.getState().items).toHaveLength(2));
    // 首屏已经拉过一次 getTrip/getItinerary，这里记住基线次数
    const tripsBefore = api.getTrip.mock.calls.length;
    const itinerariesBefore = api.getItinerary.mock.calls.length;
    fireEvent.click(screen.getByTestId('complete-item-1'));

    await waitFor(() => expect(document.body.textContent).toContain('此行程已在其他位置更新'));
    // 冲突不是「网络失败」：不进待同步队列，也不留撤销窗口
    expect(useStatusQueueStore.getState().entries).toHaveLength(0);
    expect(screen.queryByTestId('undo-bar')).toBeNull();
    // 刷新：行程与时间轴都要重新对齐服务端
    await waitFor(() => expect(api.getTrip.mock.calls.length).toBeGreaterThan(tripsBefore));
    await waitFor(() =>
      expect(api.getItinerary.mock.calls.length).toBeGreaterThan(itinerariesBefore),
    );
  });

  it('服务端明确拒绝（带状态码）时不入队，直接把理由告诉用户', async () => {
    stubViewport(false);
    const rejection = Object.assign(new Error('行程条目不存在'), { status: 404 });
    api.updateItemStatus.mockRejectedValue(rejection);
    renderPage();

    await waitFor(() => expect(useTripStore.getState().items).toHaveLength(2));
    fireEvent.click(screen.getByTestId('complete-item-1'));

    await waitFor(() => expect(api.updateItemStatus).toHaveBeenCalled());
    expect(useStatusQueueStore.getState().entries).toHaveLength(0);
    expect(screen.queryByTestId('undo-bar')).toBeNull();
  });
});
