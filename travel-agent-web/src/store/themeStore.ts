import { create } from 'zustand';
import { persist } from 'zustand/middleware';

export type ThemeMode = 'light' | 'dark';

/**
 * localStorage key。index.html 的 <head> 内联脚本用同一个 key 做首帧预设，
 * 两边规则必须保持一致：合法值直接用 / 存了但非法回落 light / 没存过则跟随系统。
 */
export const THEME_STORAGE_KEY = 'travel-agent-theme';

export function isThemeMode(value: unknown): value is ThemeMode {
  return value === 'light' || value === 'dark';
}

/** 系统偏好暗色；没有 matchMedia（单测 / 旧浏览器 / SSR）时按浅色处理 */
export function systemPrefersDark(): boolean {
  if (typeof window === 'undefined' || typeof window.matchMedia !== 'function') return false;
  return window.matchMedia('(prefers-color-scheme: dark)').matches === true;
}

/**
 * 读持久化偏好：
 * - `null`：没存过（交给系统偏好）；
 * - `'invalid'`：存了但损坏/非法（回落 light）；
 * - 合法值：直接使用。
 */
export function readStoredTheme(): ThemeMode | 'invalid' | null {
  try {
    const raw =
      typeof localStorage === 'undefined' ? null : localStorage.getItem(THEME_STORAGE_KEY);
    if (!raw) return null;
    let parsed: unknown;
    try {
      parsed = JSON.parse(raw);
    } catch {
      return 'invalid';
    }
    const mode = (parsed as { state?: { mode?: unknown } } | null | undefined)?.state?.mode;
    return isThemeMode(mode) ? mode : 'invalid';
  } catch {
    // localStorage 被禁用（隐私模式）时按非法处理：宁可固定浅色，也不要抛错白屏
    return 'invalid';
  }
}

/** 首屏/水合之前的初始主题：存储合法值优先，否则非法→light、没存过→跟随系统 */
export function resolveInitialMode(): ThemeMode {
  const stored = readStoredTheme();
  if (stored === 'invalid') return 'light';
  if (stored) return stored;
  return systemPrefersDark() ? 'dark' : 'light';
}

/** 把主题写到 <html data-theme>，global.css 里靠这个属性切换令牌 */
export function applyTheme(mode: ThemeMode): void {
  if (typeof document !== 'undefined') {
    document.documentElement.dataset.theme = mode;
  }
}

interface ThemeState {
  mode: ThemeMode;
  setMode: (mode: ThemeMode) => void;
  toggle: () => void;
}

/**
 * 主题：默认跟随系统，用户手动切换后记住选择。
 * 持久化用 zustand persist，key 与 index.html 里的预置脚本保持一致（避免首帧闪白）。
 */
export const useThemeStore = create<ThemeState>()(
  persist(
    (set, get) => ({
      mode: resolveInitialMode(),
      setMode: (mode) => {
        applyTheme(mode);
        set({ mode });
      },
      toggle: () => get().setMode(get().mode === 'dark' ? 'light' : 'dark'),
    }),
    {
      name: THEME_STORAGE_KEY,
      // 只持久化 mode：setMode/toggle 是函数，本来也不该进 localStorage
      partialize: (state) => ({ mode: state.mode }),
      /**
       * 存储值可能被手改坏、或来自旧版本：非法值一律回落 light，
       * 绝不把非法字符串写进 data-theme（否则会出现"没有主题令牌"的透明界面）。
       * 完全没有存储值时 persist 不会调用 merge，mode 保持初始值（跟随系统）。
       */
      merge: (persisted, current) => {
        // 没存过（persist 也会以 undefined 调 merge）：保留初始值 = 跟随系统
        if (persisted === null || persisted === undefined) return current;
        const stored = (persisted as { mode?: unknown }).mode;
        return { ...current, mode: isThemeMode(stored) ? stored : 'light' };
      },
      /**
       * 水合后立刻同步到 DOM（首次进入由 index.html 的脚本负责）。
       * storage 读坏时 persist 会以 undefined 回调，这里按初始解析规则兜底，
       * 保证 data-theme 永远是合法值。
       */
      onRehydrateStorage: () => (state) => {
        applyTheme(state?.mode ?? resolveInitialMode());
      },
    },
  ),
);
