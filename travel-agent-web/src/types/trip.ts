export interface UserInfo {
  id: number;
  username: string;
  nickname: string;
}

export interface AuthResp {
  token: string;
  id: number;
  username: string;
  nickname: string;
}

export interface Trip {
  id: number;
  name: string;
  destination: string;
  startDate: string;
  endDate: string;
  budget: number;
  /**
   * 同行人数（FR-01）：预算的酒店房数/餐饮与门票口径以它为准。
   * 旧后端不返回该字段，展示层按 1 人回落。
   */
  partySize?: number | null;
  status: string;
  /** 业务阶段（后端按日期派生）：UPCOMING / ONGOING / PAST / CANCELLED */
  phase?: 'UPCOMING' | 'ONGOING' | 'PAST' | 'CANCELLED';
  /** 距出发天数，仅 UPCOMING 有值 */
  daysUntilStart?: number | null;
  createdAt: string;
}

/** 行程阶段的展示文案（例如「3 天后出发」「进行中」「已结束」） */
export function tripPhaseLabel(trip: Trip): string {
  switch (trip.phase) {
    case 'CANCELLED':
      return '已取消';
    case 'PAST':
      return '已结束';
    case 'ONGOING':
      return '进行中';
    default:
      if (trip.daysUntilStart == null) return '待出发';
      return trip.daysUntilStart <= 0 ? '今天出发' : `${trip.daysUntilStart} 天后出发`;
  }
}

/** 阶段对应的徽标样式类 */
export function tripPhaseClass(trip: Trip): string {
  switch (trip.phase) {
    case 'CANCELLED':
      return 'cancelled';
    case 'PAST':
      return 'past';
    case 'ONGOING':
      return 'ongoing';
    default:
      return 'upcoming';
  }
}

export type ItemType = 'hotel' | 'attraction' | 'restaurant' | 'transport' | string;

export interface ItineraryItem {
  id: number | null;
  tripId?: number | null;
  dayIndex: number;
  itemType: ItemType;
  title: string;
  startTime: string | null;
  endTime: string | null;
  latitude: number | null;
  longitude: number | null;
  cost: number | null;
  tags?: string[] | null;
  description?: string | null;
}

export interface AddItemPayload {
  dayIndex: number;
  itemType: string;
  title: string;
  startTime?: string;
  endTime?: string;
  cost?: number | null;
  latitude?: number | null;
  longitude?: number | null;
  tags?: string[];
  description?: string;
}

/**
 * 每日相邻行程点之间的路线段（V2.2 FR-07，GET /trips/{id}/route-legs）。
 *
 * `source` 标注距离来源：`amap` = 高德真实路径规划，`estimated` = 本地估算；
 * `taxiCost` 始终是估算值（未接计价 Provider），前端不得展示成实时价格。
 */
export interface RouteLeg {
  dayIndex: number;
  /** 当天内的段序号（从 1 开始） */
  sequence: number;
  fromItemId: number | null;
  toItemId: number | null;
  fromTitle: string | null;
  toTitle: string | null;
  fromLatitude: number | null;
  fromLongitude: number | null;
  toLatitude: number | null;
  toLongitude: number | null;
  distanceKm: number | null;
  walkMinutes: number | null;
  driveMinutes: number | null;
  taxiCost: number | null;
  /** amap = 实测路径；estimated = 本地估算（必须自报家门，不得显示成实时） */
  source: 'amap' | 'estimated' | string;
}

/** 首页/足迹地图点位。目的地未定位、行程未填日期或预算时，后端会返回 null */
export interface TripMapPoint {
  tripId: number;
  name: string | null;
  destination: string | null;
  status: string | null;
  startDate: string | null;
  endDate: string | null;
  budget: number | null;
  lat: number | null;
  lng: number | null;
}

export function totalCost(items: ItineraryItem[]): number {
  return items.reduce((sum, i) => sum + (i.cost ?? 0), 0);
}

export function daysOf(trip: Trip | null): number {
  if (!trip?.startDate || !trip?.endDate) return 1;
  const start = new Date(trip.startDate).getTime();
  const end = new Date(trip.endDate).getTime();
  return Math.max(Math.round((end - start) / 86400000) + 1, 1);
}
