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

/** 行程项执行状态（P1）：计划 → 进行中 → 已完成 / 已跳过，可重置回计划 */
export type ItineraryStatus = 'PENDING' | 'IN_PROGRESS' | 'COMPLETED' | 'SKIPPED';

export const ITINERARY_STATUS_LABELS: Record<ItineraryStatus, string> = {
  PENDING: '未开始',
  IN_PROGRESS: '进行中',
  COMPLETED: '已完成',
  SKIPPED: '已跳过',
};

/** 状态文案兜底：旧数据缺字段/未知值一律按「未开始」 */
export function itineraryStatusLabel(status?: string | null): string {
  return ITINERARY_STATUS_LABELS[status as ItineraryStatus] ?? ITINERARY_STATUS_LABELS.PENDING;
}

/**
 * 价格深链：`kind=price` 去 OTA 查价（如携程），`kind=map` 在地图上查看位置。
 * 旧后端不返回 `price`，这里所有字段都按可选/可空处理。
 */
export interface ItineraryPriceLink {
  provider?: string | null;
  label?: string | null;
  url?: string | null;
  kind?: 'price' | 'map' | string | null;
}

/**
 * 价格溯源（后端新增）。`amount` 只是本地估算的参考价，未接入房态/库存，
 * 展示层只能表述为「参考价」，不得显示成实时报价或可订状态。
 *
 * 兼容性：旧后端**完全没有** `price` 字段；`cost` 为 null/0 时后端给 `price: null`。
 * 因此这里每个子字段都可选/可空，任何缺失都退回「只展示金额」的旧行为。
 */
export interface ItineraryPrice {
  amount?: number | null;
  currency?: string | null;
  /** 价格类型，当前只有 reference（参考价） */
  kind?: 'reference' | string | null;
  /** 金额旁的小标签文案，后端给「参考价」 */
  label?: string | null;
  /** 来源标识，如 local-estimate */
  source?: string | null;
  /** 来源的人类可读名，如「本地估算」 */
  sourceLabel?: string | null;
  /** 免责声明：必须原样展示，不得改写成承诺 */
  disclaimer?: string | null;
  links?: ItineraryPriceLink[] | null;
}

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
  /** 价格溯源（可选）：缺失或 null 时保持只展示金额的旧行为 */
  price?: ItineraryPrice | null;
  /** 执行状态；旧后端缺失时按 PENDING 处理 */
  status?: ItineraryStatus | null;
  /** 实际开始/完成/到访时间（ISO）；未发生时为 null，绝不拿计划时间冒充 */
  startedAt?: string | null;
  completedAt?: string | null;
  visitedAt?: string | null;
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
  /**
   * 真实道路几何，**WGS-84、纬度在前**（`[[lat, lng], ...]`），来自后端 `route-legs` 的扩展字段。
   *
   * 旧后端不返回该字段（或缺省/空数组），此时前端按 ADR-009 回退为「两端点直连」；
   * 渲染给高德前必须逐点转 GCJ-02（见 `AMapProvider`），业务层不感知偏移。
   */
  polyline?: [number, number][];
  /** amap = 实测路径；estimated = 本地估算（必须自报家门，不得显示成实时） */
  source: 'amap' | 'estimated' | string;
}

/** 足迹实际路线点（WGS-84，纬度在前） */
export interface TripMapRouteStop {
  itemId: number | null;
  title: string | null;
  visitedAt: string | null;
  lat: number | null;
  lng: number | null;
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
  /** 业务阶段：UPCOMING / ONGOING / PAST / CANCELLED */
  phase?: 'UPCOMING' | 'ONGOING' | 'PAST' | 'CANCELLED' | string | null;
  /** 计划地点数 */
  plannedPlaces?: number | null;
  /** 实际到访数（只认 visitedAt） */
  visitedPlaces?: number | null;
  /** 是否有真实打卡；未来计划不得当成「去过」 */
  visited?: boolean | null;
  /** 实际到访路线（按到访时间排序） */
  actualRoute?: TripMapRouteStop[] | null;
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
