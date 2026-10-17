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
