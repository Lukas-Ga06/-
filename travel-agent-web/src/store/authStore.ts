import { create } from 'zustand';
import type { UserInfo } from '../types/trip';
import { clearAccessTicketCache } from '../api/ticket';

/**
 * 「上次登录用户」的本地缓存键。
 *
 * 为什么需要它：登录态的权威在服务端 httpOnly Cookie，但 PWA 的离线承诺是
 * 「已打开过的行程还能看」——而启动时的 /auth/me 在断网下必然失败，
 * 若此时只保留 sessionError，整站会停在「登录状态校验失败」页，缓存数据永远渲染不出来。
 * 因此这里只缓存**展示所需的公开资料**（id/username/nickname，绝不含 token），
 * 断网时用它先渲染界面；恢复在线后仍以 /auth/me（或 401）为准。
 */
export const USER_CACHE_KEY = 'ta.lastUser';

function cacheUser(user: UserInfo | null): void {
  try {
    if (user) {
      localStorage.setItem(
        USER_CACHE_KEY,
        JSON.stringify({ id: user.id, username: user.username, nickname: user.nickname }),
      );
    } else {
      localStorage.removeItem(USER_CACHE_KEY);
    }
  } catch {
    // 隐私模式 / 配额满 / SSR：缓存失败不影响在线流程
  }
}

function readCachedUser(): UserInfo | null {
  try {
    const raw = localStorage.getItem(USER_CACHE_KEY);
    if (!raw) return null;
    const parsed = JSON.parse(raw) as Partial<UserInfo>;
    if (typeof parsed?.id !== 'number' || typeof parsed?.username !== 'string') return null;
    return {
      id: parsed.id,
      username: parsed.username,
      nickname: typeof parsed.nickname === 'string' ? parsed.nickname : parsed.username,
    };
  } catch {
    return null;
  }
}

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
  /** 断网启动兜底：用上次登录的公开资料恢复界面；没有缓存返回 null */
  restoreCachedUser: () => UserInfo | null;
  logout: () => void;
}

export const useAuthStore = create<AuthState>()((set) => ({
  token: null,
  user: null,
  authChecked: false,
  sessionError: null,
  setAuth: (token, user) => {
    cacheUser(user);
    set({ token, user, sessionError: null });
  },
  setUser: (user) => {
    cacheUser(user);
    set({ user, sessionError: null });
  },
  markAuthChecked: () => set({ authChecked: true }),
  setSessionError: (message) => set({ sessionError: message }),
  restoreCachedUser: () => {
    const cached = readCachedUser();
    if (cached) {
      // 离线兜底：只恢复展示身份，不伪造 token，也不把 authChecked 之外的状态当成已校验
      set({ user: cached, sessionError: null });
    }
    return cached;
  },
  /**
   * 登出：先作废短期访问票据缓存，再清内存登录态与本地用户缓存。
   * 票据缓存 key 已按 `user.id|token` 隔离（见 api/ticket.ts 的 authKey），
   * 这里补的是「登出即作废」：否则 token 为空的那段登录态会命中上一张票据。
   */
  logout: () => {
    clearAccessTicketCache();
    cacheUser(null);
    // 会话记忆属于上一个用户：登出即抹掉，避免换人登录后串上下文
    try {
      sessionStorage.removeItem('travel-agent-conversation');
    } catch {
      // 隐私模式下 sessionStorage 可能不可用，忽略
    }
    set({ token: null, user: null, sessionError: null, authChecked: true });
  },
}));
