import { describe, expect, it } from 'vitest';
import {
  cityFootprints,
  filterByPeriod,
  footprintColor,
  footprintMonths,
  footprintStats,
  footprintYears,
  locatedPoints,
  orderedFootprints,
  routeCoordinates,
  routeGeoJson,
  tripDays,
  tripRangeLabel,
} from './footprintData';
import type { TripMapPoint } from '../../types/trip';

function point(overrides: Partial<TripMapPoint>): TripMapPoint {
  return {
    tripId: 1,
    name: '上海三日游',
    destination: '上海',
    status: 'CONFIRMED',
    startDate: '2026-01-01',
    endDate: '2026-01-03',
    budget: 3000,
    lat: 31.2304,
    lng: 121.4737,
    ...overrides,
  };
}

describe('footprintData', () => {
  it('只保留有坐标的行程', () => {
    const points = [point({}), point({ tripId: 2, lat: null, lng: null })];
    expect(locatedPoints(points)).toHaveLength(1);
    expect(locatedPoints(points)[0].tripId).toBe(1);
  });

  it('按出发日期升序排列，缺日期的排最后', () => {
    const points = [
      point({ tripId: 3, startDate: '2026-05-01' }),
      point({ tripId: 1, startDate: null }),
      point({ tripId: 2, startDate: '2026-02-01' }),
    ];
    expect(orderedFootprints(points).map((p) => p.tripId)).toEqual([2, 3, 1]);
  });

  it('同为无日期时按 tripId 稳定排序', () => {
    const points = [
      point({ tripId: 9, startDate: null }),
      point({ tripId: 4, startDate: null }),
    ];
    expect(orderedFootprints(points).map((p) => p.tripId)).toEqual([4, 9]);
  });

  it('连点坐标按 [lng, lat] 输出，且顺序与足迹顺序一致', () => {
    const points = [
      point({ tripId: 2, startDate: '2026-03-01', lat: 30.2741, lng: 120.1551 }),
      point({ tripId: 1, startDate: '2026-01-01', lat: 31.2304, lng: 121.4737 }),
    ];
    expect(routeCoordinates(points)).toEqual([
      [121.4737, 31.2304],
      [120.1551, 30.2741],
    ]);
  });

  it('不足两个定位点时没有连线', () => {
    expect(routeGeoJson([point({})])).toBeNull();
    expect(routeGeoJson([])).toBeNull();
  });

  it('两个以上定位点时生成 LineString', () => {
    const geo = routeGeoJson([point({ tripId: 1 }), point({ tripId: 2, lat: 30.2741, lng: 120.1551 })]);
    expect(geo?.geometry.type).toBe('LineString');
    expect(geo?.geometry.coordinates).toHaveLength(2);
  });

  it('计算行程天数，日期缺失或倒挂时按 1 天处理', () => {
    expect(tripDays(point({}))).toBe(3);
    expect(tripDays(point({ endDate: null }))).toBe(1);
    expect(tripDays(point({ startDate: '2026-01-05', endDate: '2026-01-01' }))).toBe(1);
  });

  it('日期区间文案：单日只显示一天，无日期显示待定', () => {
    expect(tripRangeLabel(point({ startDate: '2026-01-01', endDate: '2026-01-01' }))).toBe('2026-01-01');
    expect(tripRangeLabel(point({}))).toBe('2026-01-01 → 2026-01-03');
    expect(tripRangeLabel(point({ startDate: null, endDate: null }))).toBe('日期待定');
  });

  it('统计城市数去重、预算与天数求和', () => {
    const stats = footprintStats([
      point({ tripId: 1, destination: '上海' }),
      point({ tripId: 2, destination: '上海', budget: 1000, startDate: '2026-02-01', endDate: '2026-02-02' }),
      point({ tripId: 3, destination: '杭州', lat: null, lng: null, budget: 500 }),
    ]);
    // 天数按全部行程累加：3 天 + 2 天 + 3 天
    expect(stats).toEqual({ trips: 3, cities: 2, located: 2, days: 8, budget: 4500 });
  });

  it('配色循环使用', () => {
    expect(footprintColor(0)).toBe(footprintColor(6));
    expect(footprintColor(0)).not.toBe(footprintColor(1));
  });

  it('按城市聚合：到访次数、天数、预算与首末到访时间', () => {
    const cities = cityFootprints([
      point({ tripId: 1, destination: '上海', startDate: '2025-01-01', endDate: '2025-01-03', budget: 3000 }),
      point({ tripId: 2, destination: '上海', startDate: '2026-02-01', endDate: '2026-02-02', budget: 1000 }),
      point({ tripId: 3, destination: '杭州', startDate: '2025-06-01', endDate: '2025-06-01', budget: 500 }),
    ]);

    expect(cities.map((c) => c.city)).toEqual(['上海', '杭州']);
    expect(cities[0]).toMatchObject({
      visits: 2,
      days: 5,
      budget: 4000,
      firstVisit: '2025-01-01',
      lastVisit: '2026-02-01',
    });
    expect(cities[1].visits).toBe(1);
  });

  it('按城市聚合时忽略没有坐标的行程', () => {
    expect(cityFootprints([point({ tripId: 1, lat: null, lng: null })])).toHaveLength(0);
  });

  it('年份/月份取值来自行程日期', () => {
    const points = [
      point({ tripId: 1, startDate: '2025-03-01' }),
      point({ tripId: 2, startDate: '2026-11-05' }),
      point({ tripId: 3, startDate: '2025-08-09' }),
      point({ tripId: 4, startDate: null }),
    ];
    expect(footprintYears(points)).toEqual(['2025', '2026']);
    expect(footprintMonths(points)).toEqual(['03', '08', '11']);
  });

  it('时间轴过滤：不限年份保留全部（含无日期），选定年份按年/月过滤', () => {
    const points = [
      point({ tripId: 1, startDate: '2025-03-01' }),
      point({ tripId: 2, startDate: '2025-08-09' }),
      point({ tripId: 3, startDate: '2026-11-05' }),
      point({ tripId: 4, startDate: null }),
    ];

    expect(filterByPeriod(points, null, null)).toHaveLength(4);
    expect(filterByPeriod(points, '2025', null).map((p) => p.tripId)).toEqual([1, 2]);
    expect(filterByPeriod(points, '2025', '08').map((p) => p.tripId)).toEqual([2]);
    // 选定年份后，无日期的行程无法归入该年
    expect(filterByPeriod(points, '2026', null).map((p) => p.tripId)).toEqual([3]);
  });
});
