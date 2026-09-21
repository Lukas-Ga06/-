import { create } from 'zustand';
import type { SelectedElement } from '../types/agent';

interface UIState {
  selectedDay: number;
  selectedElement: SelectedElement | null;
  setSelectedDay: (day: number) => void;
  setSelectedElement: (el: SelectedElement | null) => void;
}

export const useUIStore = create<UIState>()((set) => ({
  selectedDay: 1,
  selectedElement: null,
  setSelectedDay: (day) => set({ selectedDay: day }),
  setSelectedElement: (el) => set({ selectedElement: el }),
}));
