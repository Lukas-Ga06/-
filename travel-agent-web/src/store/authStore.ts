import { create } from 'zustand';
import type { UserInfo } from '../types/trip';
import { clearAccessTicketCache } from '../api/ticket';

interface AuthState {
  /**
   * 仅存内存（刷新即失）：登录态的持久化在服务端 httpOnly Cookie 里，
   * 刷新后通过 GET /api/auth/me 恢复 user；token 字段只兼容极少数调用方。
   */
  token: string | null;
  user: UserInfo | null;
  /** 启动时是否已完成登录态恢复；未完成前 Protected 不做跳转判断 */
  authChecked: boolean;
  /**
   * 登录态恢复「暂时失败」的原因（429 限流 / 5xx / 网络错误）。
   * 这类失败不能当成未登录：清掉它的是下一次成功的 /auth/me 或真正的 401。
   */
  sessionError: string | null;
  setAuth: (token: string, user: UserInfo) => void;
  setUser: (user: UserInfo) => void;
  markAuthChecked: () => void;
  setSessionError: (message: string | null) => void;
  logout: () => void;
}

export const useAuthStore = create<AuthState>()((set) => ({
  token: null,
  user: null,
  authChecked: false,
  sessionError: null,
  setAuth: (token, user) => set({ token, user, sessionError: null }),
  setUser: (user) => set({ user, sessionError: null }),
  markAuthChecked: () => set({ authChecked: true }),
  setSessionError: (message) => set({ sessionError: message }),
  /**
   * 登出：先作废短期访问票据缓存，再清内存登录态。
   * 票据缓存 key 已按 `user.id|token` 隔离（见 api/ticket.ts 的 authKey），
   * 这里补的是「登出即作废」：否则 token 为空的那段登录态会命中上一张票据。
   */
  logout: () => {
    clearAccessTicketCache();
    set({ token: null, user: null, sessionError: null });
  },
}));
