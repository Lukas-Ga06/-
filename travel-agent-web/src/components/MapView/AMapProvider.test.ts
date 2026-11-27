// @vitest-environment happy-dom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { ItineraryItem, RouteLeg } from '../../types/trip';
import { toAmapLngLat } from '../../lib/coords';
import { AMapProvider, AMAP_STYLES, DAY_COLORS } from './AMapProvider';
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
    getZoom: vi.fn(() => 9.5),
    setCenter: vi.fn(),
    setMapStyle: vi.fn(),
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
  const Marker = vi.fn(function (options: object) {
    return { kind: 'marker', options };
  });
  const Polyline = vi.fn(function (options: object) {
    return { kind: 'polyline', options };
  });
  const ControlBar = vi.fn(function () {
    return { kind: 'control-bar' };
  });
  const AMap = {
    Map: vi.fn(function () {
      return map;
    }),
    Marker,
    Polyline,
    ControlBar,
    Scale: vi.fn(function () {
      return { kind: 'scale' };
    }),
    ToolBar: vi.fn(function () {
      return { kind: 'tool-bar' };
    }),
  } as unknown as AMapNamespace;
  return { AMap, Marker, Polyline, ControlBar };
}

async function flushLoader() {
  await Promise.resolve();
  await Promise.resolve();
}

/** 两个 describe 共用同一个被测实例与计时器/环境清理 */
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

describe('AMapProvider', () => {
  it('使用高德 3D 渲染分日路线和 DOM 标记', async () => {
    const map = createMapMock();
    const { AMap, Marker, Polyline, ControlBar } = createAMapMock(map);
    const load = vi.fn(() => Promise.resolve(AMap));
    const status = vi.fn();
    provider = new AMapProvider('3D', load);

    provider.init(document.createElement('div'), status);
    // SDK 尚未 resolve 时提交的数据应在初始化后自动补绘。
    provider.render(
      [item(), item({ id: 2, title: '南京路', latitude: 31.24, longitude: 121.48 })],
      1,
    );
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

  it('leg 带真实 polyline 时逐点转 GCJ-02 并按几何绘制（amap 实线）', async () => {
    const map = createMapMock();
    const { AMap, Polyline } = createAMapMock(map);
    provider = new AMapProvider('3D', () => Promise.resolve(AMap));
    // 后端几何是 WGS-84、纬度在前；3 个点才能区分「逐点转换」与「只转首尾」
    const polyline: [number, number][] = [
      [31.23, 121.47],
      [31.235, 121.475],
      [31.24, 121.48],
    ];

    provider.init(document.createElement('div'));
    await flushLoader();
    provider.render(
      [item(), item({ id: 2, title: '豫园', latitude: 31.24, longitude: 121.48 })],
      1,
      [leg({ source: 'amap', polyline })],
    );

    // 每段仍是「辉光 + 主线」两条折线
    expect(Polyline).toHaveBeenCalledTimes(2);
    const options = Polyline.mock.calls.map(([opts]) => opts as Record<string, unknown>);
    const expected = polyline.map(([lat, lng]) => toAmapLngLat(lat, lng));
    // 不再只画端点：3 个点逐点转成 GCJ-02 的 [lng, lat]
    expect(options[0].path).toEqual(expected);
    expect(options[0].path).toHaveLength(3);
    expect((options[0].path as [number, number][])[1]).toEqual(toAmapLngLat(31.235, 121.475));
    // 辉光层与主线层共用同一条真实几何
    expect(options[1].path).toEqual(expected);
    // source=amap 仍是实线，样式语义不变
    expect(options.every((o) => o.strokeStyle === 'solid')).toBe(true);
  });

  it('polyline 缺省 / 空数组 / 少于 2 点时回退两端点直连', async () => {
    const endpoints: [number, number][] = [
      toAmapLngLat(31.23, 121.47),
      toAmapLngLat(31.24, 121.48),
    ];
    // 旧后端（无字段）/ 后端空数组 / 后端只回 1 个点，都必须回退且不崩
    for (const polyline of [undefined, [], [[31.235, 121.475]]] as (
      [number, number][] | undefined
    )[]) {
      const map = createMapMock();
      const { AMap, Polyline } = createAMapMock(map);
      provider?.destroy();
      provider = new AMapProvider('2D', () => Promise.resolve(AMap));
      provider.init(document.createElement('div'));
      await flushLoader();
      provider.render(
        [item(), item({ id: 2, title: '豫园', latitude: 31.24, longitude: 121.48 })],
        1,
        [leg({ source: 'amap', polyline })],
      );

      const options = Polyline.mock.calls.map(([opts]) => opts as Record<string, unknown>);
      expect(options).toHaveLength(2);
      expect(options[0].path).toEqual(endpoints);
      expect(options[1].path).toEqual(endpoints);
    }
  });

  it('estimated 段即使带 polyline 也画虚线（不把估算画成实测）', async () => {
    const map = createMapMock();
    const { AMap, Polyline } = createAMapMock(map);
    provider = new AMapProvider('3D', () => Promise.resolve(AMap));
    const polyline: [number, number][] = [
      [31.23, 121.47],
      [31.235, 121.475],
      [31.24, 121.48],
    ];

    provider.init(document.createElement('div'));
    await flushLoader();
    provider.render(
      [item(), item({ id: 2, title: '豫园', latitude: 31.24, longitude: 121.48 })],
      1,
      [leg({ source: 'estimated', polyline })],
    );

    const options = Polyline.mock.calls.map(([opts]) => opts as Record<string, unknown>);
    expect(options).toHaveLength(2);
    expect(options.every((o) => o.strokeStyle === 'dashed')).toBe(true);
    expect(options[0].path).toEqual(polyline.map(([lat, lng]) => toAmapLngLat(lat, lng)));
  });

  it('polyline 几何变化（端点/来源不变）也会触发重绘，几何相同则不重绘', async () => {
    const map = createMapMock();
    const { AMap, Polyline } = createAMapMock(map);
    provider = new AMapProvider('3D', () => Promise.resolve(AMap));
    const items = [item(), item({ id: 2, title: '豫园', latitude: 31.24, longitude: 121.48 })];
    const first: [number, number][] = [
      [31.23, 121.47],
      [31.235, 121.475],
      [31.24, 121.48],
    ];
    // 端点相同、只有中段几何不同：缓存 key 必须靠几何摘要感知到
    const second: [number, number][] = [
      [31.23, 121.47],
      [31.231, 121.469],
      [31.245, 121.482],
      [31.24, 121.48],
    ];

    provider.init(document.createElement('div'));
    await flushLoader();
    provider.render(items, 1, [leg({ source: 'amap', polyline: first })]);
    expect(Polyline).toHaveBeenCalledTimes(2);

    // 同一几何换个数组实例：摘要相同 → 不重绘（缓存不是按引用比对的）
    provider.render(items, 1, [leg({ source: 'amap', polyline: [...first] })]);
    expect(Polyline).toHaveBeenCalledTimes(2);

    // 几何变了 → 重绘，且新路径按新几何逐点转换
    provider.render(items, 1, [leg({ source: 'amap', polyline: second })]);
    expect(Polyline).toHaveBeenCalledTimes(4);
    const lastCall = Polyline.mock.calls[Polyline.mock.calls.length - 1];
    const latest = lastCall[0] as Record<string, unknown>;
    expect(latest.path).toEqual(second.map(([lat, lng]) => toAmapLngLat(lat, lng)));
    expect(map.remove).toHaveBeenCalled();
  });

  it('端点缺失但有真实几何时仍按几何绘制（geometry-first）', async () => {
    const map = createMapMock();
    const { AMap, Polyline } = createAMapMock(map);
    provider = new AMapProvider('3D', () => Promise.resolve(AMap));
    const polyline: [number, number][] = [
      [31.23, 121.47],
      [31.24, 121.48],
    ];

    provider.init(document.createElement('div'));
    await flushLoader();
    provider.render([], 1, [
      leg({
        fromLatitude: null,
        fromLongitude: null,
        toLatitude: null,
        toLongitude: null,
        polyline,
      }),
    ]);

    const options = Polyline.mock.calls.map(([opts]) => opts as Record<string, unknown>);
    expect(options).toHaveLength(2);
    expect(options[0].path).toEqual(polyline.map(([lat, lng]) => toAmapLngLat(lat, lng)));
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
    expect(map.setZoomAndCenter).toHaveBeenCalledWith(16, toAmapLngLat(31.23, 121.47), false, 1400);

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
    provider = new AMapProvider(
      '3D',
      () =>
        new Promise((resolve) => {
          resolveLoader = resolve;
        }),
    );

    provider.init(document.createElement('div'));
    provider.destroy();
    resolveLoader(AMap);
    await flushLoader();

    expect(AMap.Map).not.toHaveBeenCalled();
  });

  it('初始主题为暗色时直接用高德 dark 底图样式构造地图', async () => {
    const map = createMapMock();
    const { AMap } = createAMapMock(map);
    const container = document.createElement('div');
    provider = new AMapProvider('3D', () => Promise.resolve(AMap), 'dark');

    provider.init(container);
    await flushLoader();

    expect(AMap.Map).toHaveBeenCalledTimes(1);
    expect(AMap.Map).toHaveBeenCalledWith(
      container,
      expect.objectContaining({ mapStyle: AMAP_STYLES.dark }),
    );
    // 可观测：容器上能读到当前请求的底图样式（验收脚本据此断言，不靠肉眼）
    expect(container.dataset.mapStyle).toBe(AMAP_STYLES.dark);
    expect(container.dataset.mapTheme).toBe('dark');
  });

  it('主题切换调用 setMapStyle 且不重建地图', async () => {
    const map = createMapMock();
    const { AMap } = createAMapMock(map);
    const container = document.createElement('div');
    provider = new AMapProvider('3D', () => Promise.resolve(AMap), 'light');
    provider.init(container);
    await flushLoader();
    provider.render([item()], 1);

    provider.setTheme('dark');

    expect(map.setMapStyle).toHaveBeenCalledTimes(1);
    expect(map.setMapStyle).toHaveBeenCalledWith(AMAP_STYLES.dark);
    // 不重建：Map 只构造过一次，也没有销毁
    expect(AMap.Map).toHaveBeenCalledTimes(1);
    expect(map.destroy).not.toHaveBeenCalled();
    expect(container.dataset.mapStyle).toBe(AMAP_STYLES.dark);
  });

  it('SDK 未就绪时的主题请求不会丢：加载完成后按暗色创建地图', async () => {
    const map = createMapMock();
    const { AMap } = createAMapMock(map);
    let resolveLoader!: (value: AMapNamespace) => void;
    const container = document.createElement('div');
    provider = new AMapProvider(
      '3D',
      () =>
        new Promise((resolve) => {
          resolveLoader = resolve;
        }),
      'light',
    );
    provider.init(container);

    // SDK 还没 resolve：主题请求先挂起
    provider.setTheme('dark');
    expect(container.dataset.mapStyle).toBe(AMAP_STYLES.dark);
    expect(map.setMapStyle).not.toHaveBeenCalled();

    resolveLoader(AMap);
    await flushLoader();

    // 加载完成后用挂起的主题构造地图，而不是回落 light
    expect(AMap.Map).toHaveBeenCalledWith(
      container,
      expect.objectContaining({ mapStyle: AMAP_STYLES.dark }),
    );
    expect(container.dataset.mapStyle).toBe(AMAP_STYLES.dark);
  });

  it('主题变化参与 render 缓存 key：同一批数据会按新主题重绘一次', async () => {
    const map = createMapMock();
    const { AMap, Marker } = createAMapMock(map);
    provider = new AMapProvider('3D', () => Promise.resolve(AMap), 'light');
    provider.init(document.createElement('div'));
    await flushLoader();
    provider.render([item()], 1);
    expect(Marker).toHaveBeenCalledTimes(1);

    // 主题变了：即便 items 原样，也必须重绘（key 里有 theme）
    provider.setTheme('dark');
    expect(Marker).toHaveBeenCalledTimes(2);
    expect(map.remove).toHaveBeenCalled();

    // key 已被新主题更新：同主题下重复 render 不再重绘
    provider.render([item()], 1);
    expect(Marker).toHaveBeenCalledTimes(2);

    // 再切回浅色又是一次有效变化
    provider.setTheme('light');
    expect(Marker).toHaveBeenCalledTimes(3);
  });

  it('缺少 Web 端 Key 时给出明确配置错误', async () => {
    vi.stubEnv('VITE_AMAP_KEY', '');
    await expect(loadAMap()).rejects.toThrow('VITE_AMAP_KEY');
  });

  it('resize 只重算画布：不重建地图、不动相机（侧栏 keep-alive 切回可见时用）', async () => {
    const map = createMapMock();
    const resize = vi.fn();
    (map as AMapMap & { resize?: () => void }).resize = resize;
    const { AMap } = createAMapMock(map);
    provider = new AMapProvider('3D', () => Promise.resolve(AMap), 'light');
    provider.init(document.createElement('div'));
    await flushLoader();

    provider.resize();

    expect(resize).toHaveBeenCalledTimes(1);
    // 只调用 resize：地图实例没有被销毁重建，相机也没有被挪动
    expect(map.destroy).not.toHaveBeenCalled();
    expect(map.setZoomAndCenter).not.toHaveBeenCalled();
    expect(AMap.Map).toHaveBeenCalledTimes(1);
  });

  it('旧版 SDK 没有 resize 时静默跳过，不抛错', async () => {
    const map = createMapMock();
    const { AMap } = createAMapMock(map);
    provider = new AMapProvider('3D', () => Promise.resolve(AMap), 'light');
    provider.init(document.createElement('div'));
    await flushLoader();

    expect(() => provider?.resize()).not.toThrow();
  });
});

describe('AMapProvider · 时间线 ↔ 地图选中联动', () => {
  /** 标记的 content 是应用自己构建的 DOM（伪造/真实 SDK 都一样），断言直接读它 */
  function markerElements(calls: unknown[][]): HTMLElement[] {
    return calls.map(([options]) => (options as { content: HTMLElement }).content);
  }

  it('select 只改标记的选中类并平移：不重绘、不碰缩放', async () => {
    const map = createMapMock();
    const { AMap, Marker } = createAMapMock(map);
    provider = new AMapProvider('3D', () => Promise.resolve(AMap));
    provider.init(document.createElement('div'));
    await flushLoader();
    provider.render(
      [item(), item({ id: 2, title: '豫园', latitude: 31.24, longitude: 121.48 })],
      1,
    );
    expect(Marker).toHaveBeenCalledTimes(2);

    provider.select(2);

    const [first, second] = markerElements(Marker.mock.calls as unknown[][]);
    expect(second.dataset.selected).toBe('true');
    expect(second.classList.contains('m3d-selected')).toBe(true);
    expect(first.dataset.selected).toBe('false');
    expect(first.classList.contains('m3d-selected')).toBe(false);

    // 相机只平移：真的定位到这条安排，但缩放一律不碰（用户调过的缩放是他们的上下文）
    expect(map.setCenter).toHaveBeenCalledTimes(1);
    expect(map.setCenter).toHaveBeenCalledWith(toAmapLngLat(31.24, 121.48));
    expect(map.setZoomAndCenter).not.toHaveBeenCalled();

    // 不重绘：没有重建覆盖物，也没有把标记全部移除再来一遍
    expect(Marker).toHaveBeenCalledTimes(2);
    expect(map.remove).not.toHaveBeenCalled();
  });

  it('select(null) 清掉高亮但不移动相机', async () => {
    const map = createMapMock();
    const { AMap, Marker } = createAMapMock(map);
    provider = new AMapProvider('3D', () => Promise.resolve(AMap));
    provider.init(document.createElement('div'));
    await flushLoader();
    provider.render([item()], 1);
    provider.select(1);
    expect(map.setCenter).toHaveBeenCalledTimes(1);

    provider.select(null);

    expect(markerElements(Marker.mock.calls as unknown[][])[0].dataset.selected).toBe('false');
    expect(map.setCenter).toHaveBeenCalledTimes(1);
  });

  it('旧版 SDK 没有 setCenter 时取当前缩放回填，仍是「只平移不改缩放」', async () => {
    const map = createMapMock();
    delete (map as { setCenter?: unknown }).setCenter;
    const { AMap } = createAMapMock(map);
    provider = new AMapProvider('3D', () => Promise.resolve(AMap));
    provider.init(document.createElement('div'));
    await flushLoader();
    provider.render([item()], 1);

    provider.select(1);

    expect(map.getZoom).toHaveBeenCalled();
    expect(map.setZoomAndCenter).toHaveBeenCalledWith(9.5, toAmapLngLat(31.23, 121.47));
  });

  it('重绘后按最近一次选中恢复高亮（数据变化不丢选中态）', async () => {
    const map = createMapMock();
    const { AMap, Marker } = createAMapMock(map);
    provider = new AMapProvider('3D', () => Promise.resolve(AMap));
    provider.init(document.createElement('div'));
    await flushLoader();
    provider.render(
      [item(), item({ id: 2, title: '豫园', latitude: 31.24, longitude: 121.48 })],
      1,
    );
    provider.select(2);

    // 标题变化触发整批重建：新的标记必须继承当前选中
    provider.render(
      [item(), item({ id: 2, title: '豫园（改名）', latitude: 31.24, longitude: 121.48 })],
      1,
    );

    const elements = markerElements(Marker.mock.calls as unknown[][]);
    expect(elements).toHaveLength(4);
    expect(elements[3].dataset.selected).toBe('true');
    expect(elements[2].dataset.selected).toBe('false');
  });

  it('点击标记回调 onMarkerSelect（键盘 Enter/Space 等价），没有 id 的条目不可选中', async () => {
    const map = createMapMock();
    const { AMap, Marker } = createAMapMock(map);
    provider = new AMapProvider('3D', () => Promise.resolve(AMap));
    const picked: ItineraryItem[] = [];
    provider.onMarkerSelect = (clicked) => picked.push(clicked);
    provider.init(document.createElement('div'));
    await flushLoader();
    provider.render([item(), { ...item({ title: '临时点' }), id: null }], 1);

    const [first, noId] = markerElements(Marker.mock.calls as unknown[][]);
    first.dispatchEvent(new MouseEvent('click', { bubbles: true }));
    first.dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter', bubbles: true }));
    first.dispatchEvent(new KeyboardEvent('keydown', { key: ' ', bubbles: true }));
    expect(picked).toHaveLength(3);
    expect(picked[0].id).toBe(1);

    // 无 id = 没有时间线锚点：不挂 role、不挂键盘，点了也不回调
    expect(noId.getAttribute('role')).toBeNull();
    expect(noId.dataset.itemId).toBeUndefined();
    noId.dispatchEvent(new MouseEvent('click', { bubbles: true }));
    expect(picked).toHaveLength(3);
  });

  it('SDK 就绪前收到的选中请求不会丢：建图后按选中态渲染标记', async () => {
    const map = createMapMock();
    const { AMap, Marker } = createAMapMock(map);
    let resolveLoader!: (value: AMapNamespace) => void;
    provider = new AMapProvider(
      '3D',
      () =>
        new Promise((resolve) => {
          resolveLoader = resolve;
        }),
    );
    provider.init(document.createElement('div'));
    provider.render([item()], 1);
    provider.select(1);

    resolveLoader(AMap);
    await flushLoader();

    expect(markerElements(Marker.mock.calls as unknown[][])[0].dataset.selected).toBe('true');
  });

  it('标记自带路线来源标签：amap 实测 / estimated 估算，进入该点的段才算', async () => {
    const map = createMapMock();
    const { AMap, Marker } = createAMapMock(map);
    provider = new AMapProvider('3D', () => Promise.resolve(AMap));
    provider.init(document.createElement('div'));
    await flushLoader();
    // 只有「外滩 → 豫园」这一段，来源 amap：标签落在豫园上，外滩没有进入段
    provider.render(
      [item(), item({ id: 2, title: '豫园', latitude: 31.24, longitude: 121.48 })],
      1,
      [leg({ source: 'amap' })],
    );

    const [first, second] = markerElements(Marker.mock.calls as unknown[][]);
    expect(first.querySelector('.m3d-src')).toBeNull();
    const tag = second.querySelector('.m3d-src') as HTMLElement;
    expect(tag.dataset.source).toBe('amap');
    expect(tag.textContent).toBe('实测');
  });
});
