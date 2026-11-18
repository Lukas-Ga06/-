// @vitest-environment happy-dom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { ItineraryItem, RouteLeg } from '../../types/trip';
import { toAmapLngLat } from '../../lib/coords';
import { AMapProvider, DAY_COLORS } from './AMapProvider';
import { loadAMap, type AMapMap, type AMapNamespace } from './amapLoader';

function item(patch: Partial<ItineraryItem> = {}): ItineraryItem {
  return {
    id: 1,
    dayIndex: 1,
    itemType: 'attraction',
    title: '外滩',
    description: '江边散步',
    startTime: '09:00',
    endTime: '10:00',
    latitude: 31.23,
    longitude: 121.47,
    cost: null,
    ...patch,
  };
}

type MockMap = AMapMap & { emit(event: string, payload?: unknown): void };

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

function createMapMock(): MockMap {
  const handlers = new Map<string, (event?: unknown) => void>();
  const map: AMapMap = {
    on: vi.fn((event: string, handler: (event?: unknown) => void) => {
      handlers.set(event, handler);
    }),
    add: vi.fn(),
    remove: vi.fn(),
    addControl: vi.fn(),
    setFitView: vi.fn(),
    setZoomAndCenter: vi.fn(),
    setPitch: vi.fn(),
    setRotation: vi.fn(),
    getRotation: vi.fn(() => 0),
    destroy: vi.fn(),
  };
  return Object.assign(map, {
    emit(event: string, payload?: unknown) {
      handlers.get(event)?.(payload);
    },
  });
}

function createAMapMock(map: AMapMap) {
  // 这些 API 在生产中通过 new 调用，mock 也必须使用可构造的 function 实现。
  const Marker = vi.fn(function (options: object) { return { kind: 'marker', options }; });
  const Polyline = vi.fn(function (options: object) { return { kind: 'polyline', options }; });
  const ControlBar = vi.fn(function () { return { kind: 'control-bar' }; });
  const AMap = {
    Map: vi.fn(function () { return map; }),
    Marker,
    Polyline,
    ControlBar,
    Scale: vi.fn(function () { return { kind: 'scale' }; }),
    ToolBar: vi.fn(function () { return { kind: 'tool-bar' }; }),
  } as unknown as AMapNamespace;
  return { AMap, Marker, Polyline, ControlBar };
}

async function flushLoader() {
  await Promise.resolve();
  await Promise.resolve();
}

describe('AMapProvider', () => {
  let provider: AMapProvider | null = null;

  beforeEach(() => {
    vi.useFakeTimers();
  });

  afterEach(() => {
    provider?.destroy();
    provider = null;
    vi.clearAllTimers();
    vi.useRealTimers();
    vi.unstubAllEnvs();
    vi.restoreAllMocks();
  });

  it('使用高德 3D 渲染分日路线和 DOM 标记', async () => {
    const map = createMapMock();
    const { AMap, Marker, Polyline, ControlBar } = createAMapMock(map);
    const load = vi.fn(() => Promise.resolve(AMap));
    const status = vi.fn();
    provider = new AMapProvider('3D', load);

    provider.init(document.createElement('div'), status);
    // SDK 尚未 resolve 时提交的数据应在初始化后自动补绘。
    provider.render([
      item(),
      item({ id: 2, title: '南京路', latitude: 31.24, longitude: 121.48 }),
    ], 1);
    await flushLoader();

    expect(load).toHaveBeenCalledTimes(1);
    expect(AMap.Map).toHaveBeenCalledWith(
      expect.any(HTMLDivElement),
      expect.objectContaining({ viewMode: '3D', pitch: 55, rotation: -18 }),
    );
    expect(ControlBar).toHaveBeenCalledTimes(1);
    expect(Polyline).toHaveBeenCalledTimes(2);
    expect(Marker).toHaveBeenCalledTimes(2);
    // 业务坐标是 WGS-84，交给高德前必须换成 GCJ-02，否则国内会偏移几百米。
    expect(Marker).toHaveBeenCalledWith(
      expect.objectContaining({ position: toAmapLngLat(31.23, 121.47) }),
    );
    expect(Marker).toHaveBeenCalledWith(
      expect.objectContaining({ position: toAmapLngLat(31.24, 121.48) }),
    );
    expect(map.setFitView).toHaveBeenCalledTimes(1);

    map.emit('complete');
    expect(status).toHaveBeenCalledWith('ok');
  });

  it('同一 id 的坐标或标题变化也会触发重绘', async () => {
    const map = createMapMock();
    const { AMap, Marker } = createAMapMock(map);
    provider = new AMapProvider('2D', () => Promise.resolve(AMap));
    provider.init(document.createElement('div'));
    provider.render([item()], 1);
    await flushLoader();
    expect(Marker).toHaveBeenCalledTimes(1);

    provider.render([item({ title: '新名称', latitude: 31.3 })], 1);

    expect(Marker).toHaveBeenCalledTimes(2);
    expect(map.remove).toHaveBeenCalled();
    expect(Marker).toHaveBeenLastCalledWith(
      expect.objectContaining({ position: toAmapLngLat(31.3, 121.47), title: '新名称' }),
    );
  });

  it('传入路线段时按段绘制：estimated 虚线、amap 实线，当前天加粗', async () => {
    const map = createMapMock();
    const { AMap, Polyline, Marker } = createAMapMock(map);
    provider = new AMapProvider('3D', () => Promise.resolve(AMap));

    provider.init(document.createElement('div'));
    const legs = [
      leg({ dayIndex: 1, sequence: 1, source: 'estimated' }),
      leg({
        dayIndex: 2,
        sequence: 1,
        fromItemId: 3,
        toItemId: 4,
        fromLatitude: 31.3,
        fromLongitude: 121.5,
        toLatitude: 31.31,
        toLongitude: 121.51,
        source: 'amap',
      }),
    ];
    provider.render(
      [item(), item({ id: 2, title: '豫园', latitude: 31.24, longitude: 121.48 })],
      1,
      legs,
    );
    await flushLoader();

    // 每段是「辉光 + 主线」两条折线，两段共 4 条
    expect(Polyline).toHaveBeenCalledTimes(4);
    const options = Polyline.mock.calls.map(([opts]) => opts as Record<string, unknown>);
    const estimated = options.filter((o) => o.strokeStyle === 'dashed');
    const measured = options.filter((o) => o.strokeStyle === 'solid');
    expect(estimated).toHaveLength(2);
    expect(measured).toHaveLength(2);

    // 两点直连：只绘制端点，不伪造路径几何
    expect(estimated[0].path).toEqual([toAmapLngLat(31.23, 121.47), toAmapLngLat(31.24, 121.48)]);
    // 分段颜色仍按天取色
    expect(estimated[0].strokeColor).toBe(DAY_COLORS[0]);
    expect(measured[0].strokeColor).toBe(DAY_COLORS[1]);
    // 当前天（Day 1）加粗：辉光 15/0.3 + 主线 5/1；非当前天细淡：辉光 10/0.14 + 主线 3/0.68
    expect(estimated.some((o) => o.strokeWeight === 5 && o.strokeOpacity === 1)).toBe(true);
    expect(estimated.some((o) => o.strokeWeight === 15 && o.strokeOpacity === 0.3)).toBe(true);
    expect(measured.some((o) => o.strokeWeight === 3 && o.strokeOpacity === 0.68)).toBe(true);
    expect(measured.some((o) => o.strokeWeight === 10 && o.strokeOpacity === 0.14)).toBe(true);

    // 点位标记照常渲染
    expect(Marker).toHaveBeenCalledTimes(2);
  });

  it('legs 内容变化也会触发重绘（行程不变、只有转场段变了）', async () => {
    const map = createMapMock();
    const { AMap, Polyline } = createAMapMock(map);
    provider = new AMapProvider('2D', () => Promise.resolve(AMap));
    const items = [item(), item({ id: 2, title: '豫园', latitude: 31.24, longitude: 121.48 })];

    provider.init(document.createElement('div'));
    provider.render(items, 1);
    await flushLoader();
    // 无 legs：退回既有的按天点位连线（双线）
    expect(Polyline).toHaveBeenCalledTimes(2);

    provider.render(items, 1, [leg()]);
    expect(Polyline).toHaveBeenCalledTimes(4);
    expect(map.remove).toHaveBeenCalled();
  });

  it('2D 不加载 3D 控制条，定位与巡航复用高德相机方法', async () => {
    const map = createMapMock();
    const { AMap, ControlBar } = createAMapMock(map);
    provider = new AMapProvider('2D', () => Promise.resolve(AMap));
    provider.init(document.createElement('div'));
    await flushLoader();

    expect(AMap.Map).toHaveBeenCalledWith(
      expect.any(HTMLDivElement),
      expect.objectContaining({ viewMode: '2D', pitch: 0, rotation: 0 }),
    );
    expect(ControlBar).not.toHaveBeenCalled();

    provider.flyTo(31.23, 121.47, 16);
    expect(map.setPitch).not.toHaveBeenCalled();
    expect(map.setZoomAndCenter).toHaveBeenCalledWith(
      16,
      toAmapLngLat(31.23, 121.47),
      false,
      1400,
    );

    provider.startTour([item()]);
    expect(map.setZoomAndCenter).toHaveBeenLastCalledWith(
      16.8,
      toAmapLngLat(31.23, 121.47),
      false,
      2200,
    );
    vi.advanceTimersByTime(3000);
    expect(map.setZoomAndCenter).toHaveBeenCalledTimes(3);
    provider.stopTour();
    vi.advanceTimersByTime(6000);
    expect(map.setZoomAndCenter).toHaveBeenCalledTimes(3);
  });

  it('SDK 加载失败时上报失败，底图恢复后允许重新上报成功', async () => {
    vi.spyOn(console, 'error').mockImplementation(() => undefined);
    const status = vi.fn();
    provider = new AMapProvider('3D', () => Promise.reject(new Error('missing key')));
    provider.init(document.createElement('div'), status);
    await flushLoader();
    expect(status).toHaveBeenCalledWith('failed');
    provider.destroy();

    const map = createMapMock();
    const { AMap } = createAMapMock(map);
    provider = new AMapProvider('3D', () => Promise.resolve(AMap));
    provider.init(document.createElement('div'), status);
    await flushLoader();
    vi.advanceTimersByTime(12_000);
    expect(status).toHaveBeenCalledWith('failed');
    map.emit('complete');
    expect(status).toHaveBeenLastCalledWith('ok');
  });

  it('异步加载完成前销毁，不再创建地图', async () => {
    const map = createMapMock();
    const { AMap } = createAMapMock(map);
    let resolveLoader!: (value: AMapNamespace) => void;
    provider = new AMapProvider('3D', () => new Promise((resolve) => {
      resolveLoader = resolve;
    }));

    provider.init(document.createElement('div'));
    provider.destroy();
    resolveLoader(AMap);
    await flushLoader();

    expect(AMap.Map).not.toHaveBeenCalled();
  });

  it('缺少 Web 端 Key 时给出明确配置错误', async () => {
    vi.stubEnv('VITE_AMAP_KEY', '');
    await expect(loadAMap()).rejects.toThrow('VITE_AMAP_KEY');
  });
});
