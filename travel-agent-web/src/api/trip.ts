import { request } from './client';
import type { AddItemPayload, ItineraryItem, Trip, TripMapPoint } from '../types/trip';
import type { BudgetExecution } from '../types/expense';

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

/** 预算执行：计划 / 预算 / 实际 / 偏差 + 分类对账 */
export function getBudgetExecution(tripId: number): Promise<BudgetExecution> {
  return request<BudgetExecution>({ url: `/trips/${tripId}/budget` });
}

export function addItineraryItem(tripId: number, payload: AddItemPayload): Promise<ItineraryItem> {
  return request<ItineraryItem>({ url: `/trips/${tripId}/itinerary`, method: 'post', data: payload });
}

export function updateItineraryItem(itemId: number, payload: Partial<AddItemPayload>): Promise<ItineraryItem> {
  return request<ItineraryItem>({ url: `/itinerary/${itemId}`, method: 'put', data: payload });
}

export function deleteItineraryItem(itemId: number): Promise<void> {
  return request<void>({ url: `/itinerary/${itemId}`, method: 'delete' });
}
