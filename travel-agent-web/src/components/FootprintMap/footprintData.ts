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

/** 城市足迹：同一个城市的多次到访聚合成一个节点 */
export interface CityFootprint {
  city: string;
  visits: number;
  trips: TripMapPoint[];
  firstVisit: string | null;
  lastVisit: string | null;
  days: number;
  budget: number;
  lat: number;
  lng: number;
}

/** 按城市聚合足迹（只统计已定位的行程），按到访次数降序；同次数按最近到访排前 */
export function cityFootprints(points: TripMapPoint[]): CityFootprint[] {
  const byCity = new Map<string, CityFootprint>();
  locatedPoints(points).forEach((point) => {
    const city = (point.destination ?? point.name ?? '未命名').trim() || '未命名';
    const existing = byCity.get(city) ?? {
      city,
      visits: 0,
      trips: [],
      firstVisit: null,
      lastVisit: null,
      days: 0,
      budget: 0,
      lat: point.lat as number,
      lng: point.lng as number,
    };
    existing.visits += 1;
    existing.trips.push(point);
    existing.days += tripDays(point);
    existing.budget += point.budget ?? 0;
    const start = point.startDate;
    if (start) {
      if (!existing.firstVisit || start < existing.firstVisit) existing.firstVisit = start;
      if (!existing.lastVisit || start > existing.lastVisit) existing.lastVisit = start;
    }
    byCity.set(city, existing);
  });
  return Array.from(byCity.values()).sort((a, b) => {
    if (b.visits !== a.visits) return b.visits - a.visits;
    return (b.lastVisit ?? '').localeCompare(a.lastVisit ?? '');
  });
}

/** 行程的起始年份（无日期返回 null） */
export function tripYear(point: TripMapPoint): string | null {
  return point.startDate ? point.startDate.slice(0, 4) : null;
}

/** 有行程数据的年份（升序） */
export function footprintYears(points: TripMapPoint[]): string[] {
  const years = new Set<string>();
  points.forEach((point) => {
    const year = tripYear(point);
    if (year) years.add(year);
  });
  return Array.from(years).sort();
}

/** 有行程数据的月份「01」..「12」（升序） */
export function footprintMonths(points: TripMapPoint[]): string[] {
  const months = new Set<string>();
  points.forEach((point) => {
    if (point.startDate && point.startDate.length >= 7) {
      months.add(point.startDate.slice(5, 7));
    }
  });
  return Array.from(months).sort();
}

/**
 * 按时间窗口过滤行程：year 为 null 表示不限年份，month 为 null 表示该年全部月份。
 * 没有日期的行程在「不限年份」时保留，选定年份后排除（无法判断年份）。
 */
export function filterByPeriod(points: TripMapPoint[], year: string | null, month: string | null): TripMapPoint[] {
  if (!year) {
    return points;
  }
  return points.filter((point) => {
    if (!point.startDate || point.startDate.length < 7) {
      return false;
    }
    if (point.startDate.slice(0, 4) !== year) {
      return false;
    }
    return !month || point.startDate.slice(5, 7) === month;
  });
}
