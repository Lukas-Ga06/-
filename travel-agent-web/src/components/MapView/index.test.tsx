// @vitest-environment happy-dom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { act, cleanup, fireEvent, render, screen } from '@testing-library/react';
import MapView from './index';
import { useUIStore } from '../../store/uiStore';
import type { ItineraryItem, RouteLeg } from '../../types/trip';

/**
 * 地图失败态的展示契约（P1-E）：失败必须显示成 failed（可重试），
 * 而不是空白地图或「没有地点」；同时给出不依赖地图的出口。
 *
 * 选中联动（P1-E 4/4）：地图不另存选中态——读 `uiStore.selectedElement` 投影到标记，
 * 点标记回写同一个字段。测试在 Provider 边界上验证这两件事，不依赖真实高德 SDK。
 */
const provider = vi.hoisted(() => ({
  initCalls: 0,
  renders: [] as unknown[],
  selects: [] as (number | null)[],
  status: 'failed' as 'ok' | 'failed',
  onSelect: undefined as ((item: ItineraryItem) => void) | undefined,
}));

vi.mock('./AMapProvider', () => ({
  AMapProvider: class {
    set onMarkerSelect(handler: ((item: ItineraryItem) => void) | undefined) {
      provider.onSelect = handler;
    }
    init(_container: HTMLElement, onStatus: (status: 'ok' | 'failed') => void): void {
      provider.initCalls += 1;
      onStatus(provider.status);
    }
    destroy(): void {}
    render(items: unknown, day: unknown, legs: unknown): void {
      provider.renders.push([items, day, legs]);
    }
    select(itemId: number | null): void {
      provider.selects.push(itemId);
    }
    setTheme(): void {}
  },
}));

beforeEach(() => {
  useUIStore.setState({ selectedDay: 1, selectedElement: null });
});

afterEach(() => {
  cleanup();
  provider.initCalls = 0;
  provider.renders = [];
  provider.selects = [];
  provider.status = 'failed';
  provider.onSelect = undefined;
});

function renderMap(overrides: Partial<Parameters<typeof MapView>[0]> = {}) {
  return render(<MapView items={[]} focus={null} focusDay={null} {...overrides} />);
}

function item(patch: Partial<ItineraryItem> = {}): ItineraryItem {
  return {
    id: 1,
    dayIndex: 1,
    itemType: 'attraction',
    title: '外滩',
    startTime: '09:00',
    endTime: '10:00',
    latitude: 31.23,
    longitude: 121.47,
    cost: null,
    ...patch,
  };
}

function leg(patch: Partial<RouteLeg> = {}): RouteLeg {
  return {
    dayIndex: 1,
    sequence: 1,
    fromItemId: 1,
    toItemId: 2,
    fromTitle: '外滩',
    toTitle: '豫园',
    fromLatitude: 31.23,
    fromLongitude: 121.47,
    toLatitude: 31.24,
    toLongitude: 121.48,
    distanceKm: 4.2,
    walkMinutes: 56,
    driveMinutes: 12,
    taxiCost: 22,
    source: 'estimated',
    ...patch,
  };
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

describe('MapView · 时间线 → 地图（选中投影）', () => {
  it('selectedElement 变化只调用 select 投影，不触发整图重绘', async () => {
    provider.status = 'ok';
    renderMap({ items: [item(), item({ id: 2, title: '豫园' })], focusDay: 1 });
    // 挂载时按当前选中投影一次（此时为空）
    expect(provider.selects).toEqual([null]);
    const rendersBefore = provider.renders.length;

    act(() => {
      useUIStore.getState().setSelectedElement({ type: 'attraction', id: 2, title: '豫园' });
    });

    expect(provider.selects[provider.selects.length - 1]).toBe(2);
    // 选中不走 render：数据/天都没变，地图没有被要求重绘
    expect(provider.renders.length).toBe(rendersBefore);
  });

  it('清空选中同样投影出去（select(null)），地图不会留着旧高亮', async () => {
    provider.status = 'ok';
    renderMap({ items: [item()], focusDay: 1 });
    act(() => {
      useUIStore.getState().setSelectedElement({ type: 'attraction', id: 1, title: '外滩' });
    });
    act(() => {
      useUIStore.getState().setSelectedElement(null);
    });

    expect(provider.selects).toEqual([null, 1, null]);
  });
});

describe('MapView · 地图 → 时间线（标记点击回写唯一选中源）', () => {
  it('点标记写 selectedElement + 切到它所在的 Day（同一个 setter，不新造状态）', () => {
    provider.status = 'ok';
    renderMap({ items: [item()], focusDay: 1 });

    const clicked = item({ id: 7, title: '豫园', dayIndex: 2, itemType: 'restaurant' });
    act(() => provider.onSelect?.(clicked));

    expect(useUIStore.getState().selectedElement).toEqual({
      type: 'restaurant',
      id: 7,
      title: '豫园',
    });
    expect(useUIStore.getState().selectedDay).toBe(2);
  });

  it('没有 id 的条目不写选中（无锚点的条目选中它无处可落）', () => {
    provider.status = 'ok';
    renderMap({ items: [item()], focusDay: 1 });

    act(() => provider.onSelect?.(item({ id: null })));

    expect(useUIStore.getState().selectedElement).toBeNull();
  });
});

describe('MapView · 路线来源在两侧都要能看到', () => {
  it('图例按实际画出的来源渲染，文案与时间轴转场行一致', async () => {
    provider.status = 'ok';
    renderMap({
      items: [item(), item({ id: 2, title: '豫园' })],
      focusDay: 1,
      routeLegs: [leg({ source: 'amap' }), leg({ sequence: 2, source: 'estimated' })],
    });

    const legend = await screen.findByTestId('map-legend');
    expect(legend.textContent).toContain('实测 · amap');
    expect(legend.textContent).toContain('估算 · estimated');
    expect(legend.querySelector('[data-source="amap"]')).toBeTruthy();
    expect(legend.querySelector('[data-source="estimated"]')).toBeTruthy();
  });

  it('只有估算段时不预告实测；没有路线段时整块图例不出现', async () => {
    provider.status = 'ok';
    const { rerender } = renderMap({
      items: [item(), item({ id: 2, title: '豫园' })],
      focusDay: 1,
      routeLegs: [leg({ source: 'estimated' })],
    });
    const legend = await screen.findByTestId('map-legend');
    expect(legend.textContent).toContain('估算 · estimated');
    expect(legend.textContent).not.toContain('实测');

    rerender(<MapView items={[item()]} focus={null} focusDay={1} routeLegs={[]} active />);
    expect(screen.queryByTestId('map-legend')).toBeNull();
  });

  it('地图不可用时不出图例（失败态由 RunStatus 独占，不叠装饰）', async () => {
    renderMap({ items: [item()], focusDay: 1, routeLegs: [leg({ source: 'amap' })] });

    await screen.findByTestId('map-unavailable');
    expect(screen.queryByTestId('map-legend')).toBeNull();
  });
});
