// @vitest-environment happy-dom
import { beforeEach, describe, expect, it, vi } from 'vitest';
import type { RouteLeg } from '../types/trip';

const { getRouteLegsMock } = vi.hoisted(() => ({ getRouteLegsMock: vi.fn() }));

vi.mock('../api/trip', () => ({
  listTrips: vi.fn(),
  getTrip: vi.fn(),
  getItinerary: vi.fn(),
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
