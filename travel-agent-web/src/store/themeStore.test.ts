// @vitest-environment happy-dom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { THEME_STORAGE_KEY } from './themeStore';

/**
 * themeStore 单测要点（契约 §6）：
 * 1) 没有存储值时默认跟随 prefers-color-scheme；
 * 2) 手动切换后写入 localStorage（key = travel-agent-theme，形如 {state:{mode}}）；
 * 3) 存储值非法/损坏时回落 light，且 <html data-theme> 同步成 light。
 *
 * store 在模块导入时会读取 storage + matchMedia，所以每个用例都 resetModules 后重新 import。
 */

const STORAGE_KEY = THEME_STORAGE_KEY;

/** 替换 window 的最小外壳：matchMedia + localStorage（happy-dom 自带 window，这里只改 matchMedia） */
function stubSystemTheme(prefersDark: boolean) {
  const matchMedia = vi.fn().mockImplementation((query: string) => ({
    matches: prefersDark && query.includes('dark'),
    media: query,
    onchange: null,
    addListener: vi.fn(),
    removeListener: vi.fn(),
    addEventListener: vi.fn(),
    removeEventListener: vi.fn(),
    dispatchEvent: vi.fn(),
  }));
  vi.stubGlobal('matchMedia', matchMedia);
  Object.defineProperty(window, 'matchMedia', { configurable: true, writable: true, value: matchMedia });
  return matchMedia;
}

/** 每次重新加载 store（persist 在模块导入时自动 hydrate 一次） */
async function loadStore() {
  vi.resetModules();
  const mod = await import('./themeStore');
  return mod;
}

function writeStorage(value: string) {
  localStorage.setItem(STORAGE_KEY, value);
}

beforeEach(() => {
  localStorage.clear();
  document.documentElement.removeAttribute('data-theme');
});

afterEach(() => {
  vi.unstubAllGlobals();
  Reflect.deleteProperty(window, 'matchMedia');
  localStorage.clear();
});

describe('themeStore 主题偏好', () => {
  it('没有存储值时跟随系统暗色偏好', async () => {
    stubSystemTheme(true);

    const { useThemeStore } = await loadStore();

    expect(useThemeStore.getState().mode).toBe('dark');
    expect(document.documentElement.dataset.theme).toBe('dark');
  });

  it('系统偏好浅色且没有存储值时默认 light', async () => {
    stubSystemTheme(false);

    const { useThemeStore } = await loadStore();

    expect(useThemeStore.getState().mode).toBe('light');
    expect(document.documentElement.dataset.theme).toBe('light');
  });

  it('手动切换后写入 localStorage，并同步 <html data-theme>', async () => {
    stubSystemTheme(false);
    const { useThemeStore } = await loadStore();

    useThemeStore.getState().toggle();

    expect(useThemeStore.getState().mode).toBe('dark');
    expect(document.documentElement.dataset.theme).toBe('dark');
    expect(localStorage.getItem(STORAGE_KEY)).toContain('dark');

    useThemeStore.getState().setMode('light');
    expect(localStorage.getItem(STORAGE_KEY)).toContain('light');
    expect(document.documentElement.dataset.theme).toBe('light');
  });

  it('持久化只在 state.mode 里，下次进入沿用用户选择而不是系统偏好', async () => {
    stubSystemTheme(false);
    const first = await loadStore();
    first.useThemeStore.getState().setMode('dark');

    // 模拟刷新：系统仍是浅色，但存储里有 dark
    stubSystemTheme(false);
    const second = await loadStore();

    expect(second.useThemeStore.getState().mode).toBe('dark');
    expect(document.documentElement.dataset.theme).toBe('dark');
  });

  it('存储值是非法主题名时回落 light', async () => {
    writeStorage(JSON.stringify({ state: { mode: 'neon' }, version: 0 }));

    const { useThemeStore } = await loadStore();

    expect(useThemeStore.getState().mode).toBe('light');
    expect(document.documentElement.dataset.theme).toBe('light');
  });

  it('存储值损坏（非 JSON）时回落 light', async () => {
    writeStorage('{not-json');

    const { useThemeStore } = await loadStore();

    expect(useThemeStore.getState().mode).toBe('light');
    expect(document.documentElement.dataset.theme).toBe('light');
  });

  it('存储值缺少 mode 字段时回落 light', async () => {
    writeStorage(JSON.stringify({ state: {}, version: 0 }));

    const { useThemeStore } = await loadStore();

    expect(useThemeStore.getState().mode).toBe('light');
  });

  it('applyTheme 只改 data-theme，不动其它 DOM 状态', async () => {
    const { applyTheme } = await loadStore();

    applyTheme('dark');
    expect(document.documentElement.dataset.theme).toBe('dark');
    applyTheme('light');
    expect(document.documentElement.dataset.theme).toBe('light');
  });
});