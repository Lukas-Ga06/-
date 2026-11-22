import { create } from 'zustand';
import type { SelectedElement } from '../types/agent';

interface UIState {
  selectedDay: number;
  selectedElement: SelectedElement | null;
  remindersOpen: boolean;
  setSelectedDay: (day: number) => void;
  setSelectedElement: (el: SelectedElement | null) => void;
  setRemindersOpen: (open: boolean) => void;
}

export const useUIStore = create<UIState>()((set) => ({
  selectedDay: 1,
  selectedElement: null,
  remindersOpen: false,
  setSelectedDay: (day) => set({ selectedDay: day }),
  setSelectedElement: (el) => set({ selectedElement: el }),
  setRemindersOpen: (open) => set({ remindersOpen: open }),
}));
