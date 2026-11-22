import { request } from './client';
import type {
  AddItemPayload,
  ItineraryItem,
  ItineraryStatus,
  RouteLeg,
  Trip,
  TripMapPoint,
} from '../types/trip';
import type { BudgetExecution } from '../types/expense';
import type { TripEventItem, TripReview } from '../types/review';

export function listTrips(): Promise<Trip[]> {
  return request<Trip[]>({ url: '/trips' });
}

export function getTripsMap(): Promise<TripMapPoint[]> {
  return request<TripMapPoint[]>({ url: '/trips/map' });
}

export function createTrip(data: Partial<Trip>): Promise<Trip> {
  return request<Trip>({ url: '/trips', method: 'post', data });
}

export function getTrip(id: number): Promise<Trip> {
  return request<Trip>({ url: `/trips/${id}` });
}

export function updateTrip(id: number, data: Partial<Trip>): Promise<Trip> {
  return request<Trip>({ url: `/trips/${id}`, method: 'put', data });
}

export function deleteTrip(id: number): Promise<void> {
  return request<void>({ url: `/trips/${id}`, method: 'delete' });
}

/** 行程状态：PLANNING（待确认）/ CONFIRMED（已确认）/ CANCELLED（已取消，可恢复） */
export function updateTripStatus(id: number, status: string): Promise<Trip> {
  return request<Trip>({ url: `/trips/${id}/status`, method: 'put', data: { status } });
}

export function getItinerary(tripId: number): Promise<ItineraryItem[]> {
  return request<ItineraryItem[]>({ url: `/trips/${tripId}/itinerary` });
}

/**
 * 每日相邻行程点的转场路线段（FR-07）。属于增强信息：
 * 调用方必须静默降级，拉取失败不得阻塞时间线与地图渲染。
 */
export function getRouteLegs(tripId: number): Promise<RouteLeg[]> {
  return request<RouteLeg[]>({ url: `/trips/${tripId}/route-legs` });
}

/** 预算执行：计划 / 预算 / 实际 / 偏差 + 分类对账 */
export function getBudgetExecution(tripId: number): Promise<BudgetExecution> {
  return request<BudgetExecution>({ url: `/trips/${tripId}/budget` });
}

export function addItineraryItem(tripId: number, payload: AddItemPayload): Promise<ItineraryItem> {
  return request<ItineraryItem>({
    url: `/trips/${tripId}/itinerary`,
    method: 'post',
    data: payload,
  });
}

export function updateItineraryItem(
  itemId: number,
  payload: Partial<AddItemPayload>,
): Promise<ItineraryItem> {
  return request<ItineraryItem>({ url: `/itinerary/${itemId}`, method: 'put', data: payload });
}

export function deleteItineraryItem(itemId: number): Promise<void> {
  return request<void>({ url: `/itinerary/${itemId}`, method: 'delete' });
}

/** 行程项状态推进（P1）：COMPLETED 会写实际到访时间并进撤销链 */
export function updateItemStatus(id: number, status: ItineraryStatus): Promise<ItineraryItem> {
  return request<ItineraryItem>({
    url: `/itinerary/${id}/status`,
    method: 'put',
    data: { status },
  });
}

/** 旅行回顾：完成率、计划/实际花费、到访地点、实际路线、照片、事件与修改历史 */
export function getTripReview(tripId: number): Promise<TripReview> {
  return request<TripReview>({ url: `/trips/${tripId}/review` });
}

/** 旅行事件流（按时间倒序） */
export function getTripEvents(tripId: number, limit = 20): Promise<TripEventItem[]> {
  return request<TripEventItem[]>({ url: `/trips/${tripId}/events`, params: { limit } });
}
