// @vitest-environment happy-dom
import { afterEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import MapView from './index';

/**
 * 地图失败态的展示契约（P1-E）：失败必须显示成 failed（可重试），
 * 而不是空白地图或「没有地点」；同时给出不依赖地图的出口。
 */
const provider = vi.hoisted(() => ({ initCalls: 0 }));

vi.mock('./AMapProvider', () => ({
  AMapProvider: class {
    init(_container: HTMLElement, onStatus: (status: 'ok' | 'failed') => void): void {
      provider.initCalls += 1;
      onStatus('failed');
    }
    destroy(): void {}
    render(): void {}
    setTheme(): void {}
  },
}));

afterEach(() => {
  cleanup();
  provider.initCalls = 0;
});

function renderMap(overrides: Partial<Parameters<typeof MapView>[0]> = {}) {
  return render(<MapView items={[]} focus={null} focusDay={null} {...overrides} />);
}

describe('MapView · 地图失败态', () => {
  it('失败时显示 failed 档与重试出口，并说明时间轴仍可用', async () => {
    renderMap();

    const box = await screen.findByTestId('map-unavailable');
    // 语义来自 toRunState('resource','error') → failed，不是「暂无内容」
    expect(box.querySelector('[data-status="failed"]')).toBeTruthy();
    expect(box.textContent).toContain('地图暂时不可用');
    expect(box.textContent).toContain('行程时间轴与 AI 助手仍可正常使用');
    expect(screen.getByTestId('map-retry')).toBeTruthy();
    // 不把 Key / 环境变量暴露给用户
    expect(box.textContent).not.toContain('VITE_AMAP_KEY');
  });

  it('「查看时间轴」与「打开诊断」都走调用方给的出口', async () => {
    const onViewTimeline = vi.fn();
    const onOpenDiagnostics = vi.fn();
    renderMap({ onViewTimeline, onOpenDiagnostics });

    await screen.findByTestId('map-unavailable');
    fireEvent.click(screen.getByTestId('map-view-timeline'));
    fireEvent.click(screen.getByTestId('map-open-diagnostics'));

    expect(onViewTimeline).toHaveBeenCalledTimes(1);
    expect(onOpenDiagnostics).toHaveBeenCalledTimes(1);
  });

  it('「重试地图」真的重建地图引擎（重新 init 一次）', async () => {
    renderMap();
    await screen.findByTestId('map-unavailable');
    expect(provider.initCalls).toBe(1);

    fireEvent.click(screen.getByTestId('map-retry'));

    expect(provider.initCalls).toBe(2);
    expect(await screen.findByTestId('map-unavailable')).toBeTruthy();
  });
});
