import { create } from 'zustand';
import { persist } from 'zustand/middleware';

export type ThemeMode = 'light' | 'dark';

export const THEME_STORAGE_KEY = 'travel-agent-theme';

function systemPrefersDark(): boolean {
  return typeof window !== 'undefined' && !!window.matchMedia?.('(prefers-color-scheme: dark)').matches;
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
      mode: systemPrefersDark() ? 'dark' : 'light',
      setMode: (mode) => {
        applyTheme(mode);
        set({ mode });
      },
      toggle: () => get().setMode(get().mode === 'dark' ? 'light' : 'dark'),
    }),
    {
      name: THEME_STORAGE_KEY,
      // 重新水合后立刻同步到 DOM（首次进入由 index.html 的脚本负责）
      onRehydrateStorage: () => (state) => {
        if (state) applyTheme(state.mode);
      },
    },
  ),
);
