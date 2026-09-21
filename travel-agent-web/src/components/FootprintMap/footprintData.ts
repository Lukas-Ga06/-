import type { TripMapPoint } from '../../types/trip';

/** 足迹连线与标记的配色（与行程地图的 Day 配色区分开） */
export const FOOTPRINT_COLORS = ['#007aff', '#34c759', '#ff9500', '#af52de', '#ff2d55', '#5ac8fa'];

export function footprintColor(index: number): string {
  return FOOTPRINT_COLORS[index % FOOTPRINT_COLORS.length];
}

/** 只保留有坐标的行程（城市未定位时不参与连线） */
export function locatedPoints(points: TripMapPoint[]): TripMapPoint[] {
  return points.filter((p) => p.lat != null && p.lng != null);
}

/**
 * 足迹顺序：按出发日期升序（缺失日期的排在最后）。
 * 连线与回放都基于这个顺序，保证「从最早的旅行一路走到现在」。
 */
export function orderedFootprints(points: TripMapPoint[]): TripMapPoint[] {
  return locatedPoints(points)
    .slice()
    .sort((a, b) => {
      const da = a.startDate ?? '';
      const db = b.startDate ?? '';
      if (da === db) return (a.tripId ?? 0) - (b.tripId ?? 0);
      if (!da) return 1;
      if (!db) return -1;
      return da.localeCompare(db);
    });
}

/** GeoJSON 坐标顺序为 [lng, lat] */
export function routeCoordinates(points: TripMapPoint[]): [number, number][] {
  return orderedFootprints(points).map((p) => [p.lng as number, p.lat as number]);
}

export function routeGeoJson(points: TripMapPoint[]): GeoJSON.Feature<GeoJSON.LineString> | null {
  const coordinates = routeCoordinates(points);
  if (coordinates.length < 2) {
    return null;
  }
  return { type: 'Feature', properties: {}, geometry: { type: 'LineString', coordinates } };
}

export function tripDays(point: TripMapPoint): number {
  if (!point.startDate || !point.endDate) {
    return 1;
  }
  const start = Date.parse(point.startDate);
  const end = Date.parse(point.endDate);
  if (Number.isNaN(start) || Number.isNaN(end) || end < start) {
    return 1;
  }
  return Math.round((end - start) / 86400000) + 1;
}

export function tripRangeLabel(point: TripMapPoint): string {
  if (!point.startDate) {
    return '日期待定';
  }
  const end = point.endDate ?? point.startDate;
  return point.startDate === end ? point.startDate : `${point.startDate} → ${end}`;
}

export interface FootprintStats {
  /** 全部行程数（含未定位） */
  trips: number;
  /** 去过的城市数（按目的地去重） */
  cities: number;
  /** 已定位（可在地图上显示）的行程数 */
  located: number;
  /** 旅行总天数 */
  days: number;
  /** 行程预算合计 */
  budget: number;
}

export function footprintStats(points: TripMapPoint[]): FootprintStats {
  const cities = new Set(
    points.map((p) => (p.destination ?? p.name ?? '').trim()).filter((name) => name.length > 0),
  );
  return {
    trips: points.length,
    cities: cities.size,
    located: locatedPoints(points).length,
    days: points.reduce((sum, p) => sum + tripDays(p), 0),
    budget: points.reduce((sum, p) => sum + (p.budget ?? 0), 0),
  };
}
