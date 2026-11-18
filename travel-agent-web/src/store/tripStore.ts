import { create } from 'zustand';
import { getItinerary, getRouteLegs, getTrip, listTrips } from '../api/trip';
import type { ItineraryItem, RouteLeg, Trip } from '../types/trip';

interface TripState {
  trips: Trip[];
  tripsLoading: boolean;
  currentTrip: Trip | null;
  items: ItineraryItem[];
  /** 每日相邻行程点的转场路线段（FR-07）；只用于时间线/地图增强，缺失即静默降级 */
  routeLegs: RouteLeg[];
  loadTrips: () => Promise<void>;
  loadTrip: (id: number) => Promise<void>;
  loadItinerary: (id: number) => Promise<void>;
  loadRouteLegs: (id: number) => Promise<void>;
  clearCurrent: () => void;
}

export const useTripStore = create<TripState>()((set) => ({
  trips: [],
  tripsLoading: false,
  currentTrip: null,
  items: [],
  routeLegs: [],

  loadTrips: async () => {
    set({ tripsLoading: true });
    try {
      const trips = await listTrips();
      set({ trips });
    } finally {
      set({ tripsLoading: false });
    }
  },

  loadTrip: async (id) => {
    const trip = await getTrip(id);
    set({ currentTrip: trip });
  },

  loadItinerary: async (id) => {
    const items = await getItinerary(id);
    set({ items });
  },

  /**
   * 拉取转场路线段：失败时静默置空，绝不让增强数据阻塞页面。
   * 失败是常态（高德未配置、路由服务降级），因此不向调用方抛出。
   */
  loadRouteLegs: async (id) => {
    try {
      const legs = await getRouteLegs(id);
      set({ routeLegs: Array.isArray(legs) ? legs : [] });
    } catch {
      set({ routeLegs: [] });
    }
  },

  clearCurrent: () => set({ currentTrip: null, items: [], routeLegs: [] }),
}));
