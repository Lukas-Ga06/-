// @vitest-environment happy-dom
import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { act, cleanup, fireEvent, render, screen } from '@testing-library/react';
import OfflineBanner from './index';

// React 18 的 act 环境标记（happy-dom 下 RTL 不会自动设置）
(globalThis as unknown as { IS_REACT_ACT_ENVIRONMENT: boolean }).IS_REACT_ACT_ENVIRONMENT = true;

/** 模拟浏览器在线状态：navigator.onLine 是只读 getter，测试里用 defineProperty 覆盖 */
function setNavigatorOnline(value: boolean) {
  Object.defineProperty(navigator, 'onLine', { configurable: true, get: () => value });
}

function dispatchNetwork(type: 'online' | 'offline') {
  act(() => {
    window.dispatchEvent(new Event(type));
  });
}

beforeEach(() => setNavigatorOnline(true));

afterEach(() => {
  cleanup();
  // 删掉实例上的覆盖，恢复 happy-dom 的默认实现
  Reflect.deleteProperty(navigator, 'onLine');
});

describe('OfflineBanner 离线状态提示', () => {
  it('在线时不渲染离线提示条', () => {
    render(<OfflineBanner />);

    expect(screen.queryByTestId('offline-banner')).toBeNull();
  });

  it('进入页面时 navigator.onLine=false 直接显示「离线：显示缓存数据」', () => {
    setNavigatorOnline(false);
    render(<OfflineBanner />);

    expect(screen.getByTestId('offline-banner').textContent).toContain('离线：显示缓存数据');
  });

  it('派发 offline 事件后出现，派发 online 事件后自动消失', () => {
    render(<OfflineBanner />);
    expect(screen.queryByTestId('offline-banner')).toBeNull();

    setNavigatorOnline(false);
    dispatchNetwork('offline');
    expect(screen.getByTestId('offline-banner')).toBeTruthy();

    setNavigatorOnline(true);
    dispatchNetwork('online');
    expect(screen.queryByTestId('offline-banner')).toBeNull();
  });

  it('可以手动关闭；恢复在线后再断网会重新提示', () => {
    setNavigatorOnline(false);
    render(<OfflineBanner />);

    fireEvent.click(screen.getByLabelText('关闭离线提示'));
    expect(screen.queryByTestId('offline-banner')).toBeNull();

    setNavigatorOnline(true);
    dispatchNetwork('online');
    setNavigatorOnline(false);
    dispatchNetwork('offline');
    expect(screen.getByTestId('offline-banner')).toBeTruthy();
  });
});