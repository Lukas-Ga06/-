import { create } from 'zustand';
import type { SelectedElement } from '../types/agent';

interface UIState {
  selectedDay: number;
  selectedElement: SelectedElement | null;
  remindersOpen: boolean;
  setSelectedDay: (day: number) => void;
  setSelectedElement: (el: SelectedElement | null) => void;
  setRemindersOpen: (open: boolean) => void;
  /** 登出 / 换账号：把跨页面的选中上下文归位（选中项属于上一个人的行程） */
  reset: () => void;
}

const INITIAL = {
  selectedDay: 1,
  selectedElement: null,
  remindersOpen: false,
};

export const useUIStore = create<UIState>()((set) => ({
  ...INITIAL,
  setSelectedDay: (day) => set({ selectedDay: day }),
  setSelectedElement: (el) => set({ selectedElement: el }),
  setRemindersOpen: (open) => set({ remindersOpen: open }),
  reset: () => set(INITIAL),
}));
