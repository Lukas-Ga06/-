/**
 * 旅行回顾（P1）与旅行事件的前端契约。
 * 口径与后端 TravelReviewDtos 一致：计划/实际分开，visitedPlaces 只认 visitedAt。
 */

/** 计划地点 / 实际到访地点（同一形状；visitedAt 为 null 表示还没真正到访） */
export interface ReviewPlace {
  itemId: number | null;
  dayIndex: number | null;
  itemType: string | null;
  title: string;
  status: string | null;
  plannedAt: string | null;
  visitedAt: string | null;
  latitude: number | null;
  longitude: number | null;
}

/** 实际路线点（按 visitedAt 排序，只含真实到访且坐标齐全的地点） */
export interface ReviewRouteStop {
  itemId: number | null;
  title: string;
  visitedAt: string;
  latitude: number | null;
  longitude: number | null;
}

/** 旅行事件：天气突变 / 拥堵 / 闭馆 / 打卡 / Agent 调整 / 手动修改 */
export interface TripEventItem {
  id: number;
  itemId: number | null;
  /** WEATHER / CONGESTION / CLOSURE / CHECK_IN / AGENT_ADJUST / MANUAL_EDIT */
  type: string;
  /** weather / amap / user / agent / system */
  source: string | null;
  summary: string;
  occurredAt: string;
}

/** 操作历史（撤销链）条目 */
export interface TripModificationItem {
  id: number;
  /** AGENT_COMMIT / TRIP_CREATE / MANUAL_EDIT */
  operationType: string;
  reason: string | null;
  at: string | null;
}

/** 回顾页照片（与 /api/photos/{id}/raw 的票据鉴权口径一致） */
export interface ReviewPhoto {
  id: number;
  tripId: number;
  originalName: string | null;
  contentType: string | null;
  sizeBytes: number | null;
  createdAt: string | null;
  url: string;
}

/** GET /api/trips/{id}/review */
export interface TripReview {
  tripId: number;
  name: string;
  destination: string;
  startDate: string | null;
  endDate: string | null;
  totalDays: number;
  phase: string;
  totalItems: number;
  completedItems: number;
  skippedItems: number;
  inProgressItems: number;
  pendingItems: number;
  /** 已完成条目 / 全部条目，0..1；跳过不计入完成 */
  completionRate: number;
  plannedCost: number;
  actualCost: number;
  budget: number | null;
  /** actualCost - plannedCost */
  variance: number;
  overBudget: boolean;
  plannedPlaces: ReviewPlace[];
  visitedPlaces: ReviewPlace[];
  actualRoute: ReviewRouteStop[];
  photoCount: number;
  photos: ReviewPhoto[];
  events: TripEventItem[];
  modifications: TripModificationItem[];
  memoCount: number;
  scheduleCount: number;
  aiSummary: string | null;
}
