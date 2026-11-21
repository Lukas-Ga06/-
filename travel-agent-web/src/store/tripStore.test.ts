// @vitest-environment happy-dom
import { beforeEach, describe, expect, it, vi } from 'vitest';
import type { RouteLeg } from '../types/trip';

const { getRouteLegsMock, listTripsMock, getTripMock, getItineraryMock } = vi.hoisted(() => ({
  getRouteLegsMock: vi.fn(),
  listTripsMock: vi.fn(),
  getTripMock: vi.fn(),
  getItineraryMock: vi.fn(),
}));

vi.mock('../api/trip', () => ({
  listTrips: listTripsMock,
  getTrip: getTripMock,
  getItinerary: getItineraryMock,
  getRouteLegs: getRouteLegsMock,
}));

import { useTripStore } from './tripStore';

function leg(patch: Partial<RouteLeg> = {}): RouteLeg {
  return {
    dayIndex: 1,
    sequence: 1,
    fromItemId: 1,
    toItemId: 2,
    fromTitle: '外滩',
    toTitle: '豫园',
    fromLatitude: 31.23,
    fromLongitude: 121.47,
    toLatitude: 31.24,
    toLongitude: 121.48,
    distanceKm: 4.2,
    walkMinutes: 56,
    driveMinutes: 12,
    taxiCost: 22,
    source: 'estimated',
    ...patch,
  };
}

describe('tripStore · routeLegs（FR-07）', () => {
  beforeEach(() => {
    useTripStore.setState({ routeLegs: [], items: [], currentTrip: null });
    getRouteLegsMock.mockReset();
  });

  it('loadRouteLegs 把转场段写入 store', async () => {
    getRouteLegsMock.mockResolvedValue([leg()]);

    await useTripStore.getState().loadRouteLegs(7);

    expect(getRouteLegsMock).toHaveBeenCalledWith(7);
    expect(useTripStore.getState().routeLegs).toHaveLength(1);
    expect(useTripStore.getState().routeLegs[0].distanceKm).toBe(4.2);
  });

  it('拉取失败时静默置空数组，不向调用方抛错（不得阻塞时间线/地图）', async () => {
    useTripStore.setState({ routeLegs: [leg()] });
    getRouteLegsMock.mockRejectedValue(new Error('500 内部错误'));

    await expect(useTripStore.getState().loadRouteLegs(7)).resolves.toBeUndefined();

    expect(useTripStore.getState().routeLegs).toEqual([]);
  });

  it('clearCurrent 一并清空转场段', () => {
    useTripStore.setState({ routeLegs: [leg()] });

    useTripStore.getState().clearCurrent();

    expect(useTripStore.getState().routeLegs).toEqual([]);
  });
});

function trip(id: number, name: string): any {
  return {
    id,
    name,
    destination: '上海',
    startDate: '2026-12-01',
    endDate: '2026-12-02',
    budget: 3000,
    status: 'CONFIRMED',
    phase: 'UPCOMING',
  };
}

describe('tripStore · 列表状态与请求乱序（阶段 A）', () => {
  beforeEach(() => {
    useTripStore.setState({
      trips: [],
      tripsLoading: false,
      tripsError: null,
      currentTrip: null,
      items: [],
      routeLegs: [],
    });
    listTripsMock.mockReset();
    getTripMock.mockReset();
    getItineraryMock.mockReset();
  });

  it('加载失败时记录错误，且不把上一次的数据伪装成空列表（P0-04）', async () => {
    useTripStore.setState({ trips: [trip(1, '上海两日游')] });
    listTripsMock.mockRejectedValue(new Error('500 内部错误'));

    await expect(useTripStore.getState().loadTrips()).rejects.toThrow('500');

    const state = useTripStore.getState();
    expect(state.tripsError).toBe('500 内部错误');
    expect(state.tripsLoading).toBe(false);
    expect(state.trips).toHaveLength(1);
  });

  it('加载成功后清空历史错误', async () => {
    useTripStore.setState({ tripsError: '上次失败' });
    listTripsMock.mockResolvedValue([trip(1, '上海两日游')]);

    await useTripStore.getState().loadTrips();

    expect(useTripStore.getState().tripsError).toBeNull();
    expect(useTripStore.getState().trips).toHaveLength(1);
  });

  it('快速切换行程时，逆序返回的旧响应不会覆盖当前行程（P1-08）', async () => {
    let resolveFirst: (v: any) => void = () => {};
    let resolveSecond: (v: any) => void = () => {};
    getTripMock
      .mockImplementationOnce(() => new Promise((r) => (resolveFirst = r)))
      .mockImplementationOnce(() => new Promise((r) => (resolveSecond = r)));

    const first = useTripStore.getState().loadTrip(1);
    const second = useTripStore.getState().loadTrip(2);

    // 第二个（当前）先回来
    resolveSecond(trip(2, '当前行程'));
    await second;
    expect(useTripStore.getState().currentTrip?.id).toBe(2);

    // 第一个迟到的响应必须被丢弃
    resolveFirst(trip(1, '旧行程'));
    await first;

    expect(useTripStore.getState().currentTrip?.id).toBe(2);
    expect(useTripStore.getState().currentTrip?.name).toBe('当前行程');
  });

  it('切换行程时先清掉上一个行程的内容，避免串页', async () => {
    useTripStore.setState({
      currentTrip: trip(1, '旧行程'),
      items: [{ id: 9 } as any],
      routeLegs: [leg()],
    });
    let resolveTrip: (v: any) => void = () => {};
    getTripMock.mockImplementationOnce(() => new Promise((r) => (resolveTrip = r)));

    const pending = useTripStore.getState().loadTrip(2);
    // 请求在途期间不能还显示旧行程
    expect(useTripStore.getState().currentTrip).toBeNull();
    expect(useTripStore.getState().items).toEqual([]);

    resolveTrip(trip(2, '新行程'));
    await pending;
    expect(useTripStore.getState().currentTrip?.id).toBe(2);
  });

  it('syncTrip 发现版本变了才返回 true，并写入服务端最新行程（阶段 D 冲突提示）', async () => {
    useTripStore.setState({ currentTrip: { ...trip(1, '杭州周末'), version: 3 } as any });
    getTripMock.mockResolvedValue({ ...trip(1, '杭州周末'), version: 4 } as any);

    const changed = await useTripStore.getState().syncTrip(1);

    expect(changed).toBe(true);
    expect(useTripStore.getState().currentTrip?.version).toBe(4);
  });

  it('syncTrip 版本没变时返回 false（不打扰用户），旧后端缺 version 时也不误报', async () => {
    useTripStore.setState({ currentTrip: { ...trip(1, '杭州周末'), version: 4 } as any });
    getTripMock.mockResolvedValue({ ...trip(1, '杭州周末'), version: 4 } as any);
    expect(await useTripStore.getState().syncTrip(1)).toBe(false);

    // 旧后端不返回 version：两边都缺版本，同样不判定为「被别处更新」
    useTripStore.setState({ currentTrip: trip(2, '上海出差') as any });
    getTripMock.mockResolvedValue(trip(2, '上海出差') as any);
    expect(await useTripStore.getState().syncTrip(2)).toBe(false);
  });

  it('syncTrip 不会把非当前行程的数据写回（防止切换行程期间串页）', async () => {
    useTripStore.setState({ currentTrip: trip(2, '当前行程') as any });
    getTripMock.mockResolvedValue(trip(1, '另一个行程') as any);

    const changed = await useTripStore.getState().syncTrip(1);

    expect(changed).toBe(false);
    expect(useTripStore.getState().currentTrip?.id).toBe(2);
    expect(getTripMock).not.toHaveBeenCalled();
  });

  it('离页（clearCurrent）后迟到的时间轴响应不再写回', async () => {
    let resolveItems: (v: any) => void = () => {};
    getItineraryMock.mockImplementationOnce(() => new Promise((r) => (resolveItems = r)));

    const pending = useTripStore.getState().loadItinerary(1);
    useTripStore.getState().clearCurrent();

    resolveItems([{ id: 1, title: '外滩' }]);
    await pending;

    expect(useTripStore.getState().items).toEqual([]);
  });
});
