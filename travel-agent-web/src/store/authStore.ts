import { create } from 'zustand';
import type { UserInfo } from '../types/trip';

interface AuthState {
  /**
   * 仅存内存（刷新即失）：登录态的持久化在服务端 httpOnly Cookie 里，
   * 刷新后通过 GET /api/auth/me 恢复 user；token 字段只兼容极少数调用方。
   */
  token: string | null;
  user: UserInfo | null;
  /** 启动时是否已完成登录态恢复；未完成前 Protected 不做跳转判断 */
  authChecked: boolean;
  setAuth: (token: string, user: UserInfo) => void;
  setUser: (user: UserInfo) => void;
  markAuthChecked: () => void;
  logout: () => void;
}

export const useAuthStore = create<AuthState>()((set) => ({
  token: null,
  user: null,
  authChecked: false,
  setAuth: (token, user) => set({ token, user }),
  setUser: (user) => set({ user }),
  markAuthChecked: () => set({ authChecked: true }),
  logout: () => set({ token: null, user: null }),
}));
