import { create } from 'zustand';
import { getItinerary, getTrip, listTrips } from '../api/trip';
import type { ItineraryItem, Trip } from '../types/trip';

interface TripState {
  trips: Trip[];
  tripsLoading: boolean;
  currentTrip: Trip | null;
  items: ItineraryItem[];
  loadTrips: () => Promise<void>;
  loadTrip: (id: number) => Promise<void>;
  loadItinerary: (id: number) => Promise<void>;
  clearCurrent: () => void;
}

export const useTripStore = create<TripState>()((set) => ({
  trips: [],
  tripsLoading: false,
  currentTrip: null,
  items: [],

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

  clearCurrent: () => set({ currentTrip: null, items: [] }),
}));
