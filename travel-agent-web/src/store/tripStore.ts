import { create } from 'zustand';
import { getItinerary, getRouteLegs, getTrip, listTrips } from '../api/trip';
import type { ItineraryItem, RouteLeg, Trip } from '../types/trip';
import { hasRemoteUpdate } from '../lib/tripConflict';

interface TripState {
  trips: Trip[];
  tripsLoading: boolean;
  /** 行程列表的失败原因；与「空列表」严格区分（P0-04） */
  tripsError: string | null;
  currentTrip: Trip | null;
  items: ItineraryItem[];
  /** 每日相邻行程点的转场路线段（FR-07）；只用于时间线/地图增强，缺失即静默降级 */
  routeLegs: RouteLeg[];
  loadTrips: () => Promise<void>;
  loadTrip: (id: number) => Promise<void>;
  /**
   * 回到页面时与服务端对齐（阶段 D 冲突提示）：
   * 用最新的 Trip 覆盖本地，并告诉调用方「版本是否变了」（= 别处更新过）。
   */
  syncTrip: (id: number) => Promise<boolean>;
  loadItinerary: (id: number) => Promise<void>;
  loadRouteLegs: (id: number) => Promise<void>;
  clearCurrent: () => void;
}

/**
 * 请求序号：快速切换行程时两个响应可能逆序返回（P1-08）。
 * 每次发起自增，落地前比对目标 tripId 与序号，旧请求一律丢弃，
 * 避免 A 行程的标题配上 B 行程的时间轴。
 */
let tripsSeq = 0;
let tripSeq = 0;
let itinerarySeq = 0;

export const useTripStore = create<TripState>()((set, get) => ({
  trips: [],
  tripsLoading: false,
  tripsError: null,
  currentTrip: null,
  items: [],
  routeLegs: [],

  loadTrips: async () => {
    const seq = ++tripsSeq;
    set({ tripsLoading: true, tripsError: null });
    try {
      const trips = await listTrips();
      if (seq !== tripsSeq) return;
      set({ trips: Array.isArray(trips) ? trips : [], tripsLoading: false, tripsError: null });
    } catch (e: any) {
      if (seq !== tripsSeq) return;
      // 失败不能退化成「空列表」：保留上一次成功的数据，同时把错误暴露给页面
      set({ tripsLoading: false, tripsError: e?.message || '行程列表加载失败' });
      throw e;
    }
  },

  loadTrip: async (id) => {
    const seq = ++tripSeq;
    // 切到别的行程时先清掉旧内容，否则请求在途期间会显示上一个行程的标题/时间轴
    if (get().currentTrip && get().currentTrip!.id !== id) {
      set({ currentTrip: null, items: [], routeLegs: [] });
    }
    const trip = await getTrip(id);
    if (seq !== tripSeq) return;
    set({ currentTrip: trip });
  },

  syncTrip: async (id) => {
    const seq = ++tripSeq;
    const before = get().currentTrip;
    if (before && before.id !== id) return false;
    const trip = await getTrip(id);
    if (seq !== tripSeq) return false;
    // 等待期间用户可能已经切到别的行程：别把旧 id 的数据写回来
    if (get().currentTrip && get().currentTrip!.id !== id) return false;
    set({ currentTrip: trip });
    return before?.id === id && hasRemoteUpdate(before?.version, trip.version);
  },

  loadItinerary: async (id) => {
    const seq = ++itinerarySeq;
    const items = await getItinerary(id);
    if (seq !== itinerarySeq) return;
    set({ items: Array.isArray(items) ? items : [] });
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

  clearCurrent: () => {
    // 作废在途的详情请求，避免离页后它又把旧行程写回来
    tripSeq += 1;
    itinerarySeq += 1;
    set({ currentTrip: null, items: [], routeLegs: [] });
  },
}));
