import type { ItineraryItem, RouteLeg } from '../../types/trip';
import { toAmapLngLat } from '../../lib/coords';
import type { MapProvider } from './MapProvider';
import {
  loadAMap,
  type AMapLoader,
  type AMapMap,
  type AMapNamespace,
  type AMapOverlay,
  type AMapViewMode,
} from './amapLoader';
import { buildMarkerElement } from './markerElement';

export const DAY_COLORS = ['#007aff', '#34c759', '#ff9500', '#af52de', '#ff2d55'];

const DEFAULT_CENTER: [number, number] = toAmapLngLat(31.2304, 121.4737);
const BASE_MAP_TIMEOUT_MS = 12_000;

type BaseMapStatus = 'ok' | 'failed';
type FocusTarget = { lat: number; lng: number; zoom: number };
/** 一段 leg 交给高德画线的坐标：GCJ-02 的 `[lng, lat]` 点序列 */
type LegPath = [number, number][];

/** 几何摘要的坐标量化精度：1e-5° ≈ 1 米，更细的浮点尾差对画线无意义 */
const DIGEST_SCALE = 1e5;

/**
 * 真实几何摘要：点数 + 对每个点做整数滚动 hash（FNV-1a 变体）。
 *
 * 为什么不把整条 polyline 塞进 `lastRenderKey`：render 会被 focusDay / items 等
 * 高频变化触发，而真实道路几何是几百个点的坐标数组——每次 render 都
 * `JSON.stringify` 一遍再和上一串做字符串比较，等于反复分配、比较数 KB 的字符串，
 * 点越多越慢，缓存反而成了负担。这里只遍历数字做 `Math.imul` 乘异或：
 * 同样 O(n) 但零字符串分配、常数极小，且任意一个点变化都会以极大概率被感知
 * （1e-5° 量化把浮点尾差归并，避免同一条路线被判成「变了」而白白重绘）。
 * 32 位 hash 的碰撞概率 ≈ 1/2^32，对「要不要重绘」这个用途足够。
 */
function polylineDigest(polyline: [number, number][]): string {
  let hash = 0x811c9dc5;
  for (const point of polyline) {
    hash = Math.imul(hash ^ (Math.round(point[0] * DIGEST_SCALE) | 0), 0x01000193);
    hash = Math.imul(hash ^ (Math.round(point[1] * DIGEST_SCALE) | 0), 0x01000193);
  }
  return `${polyline.length}:${(hash >>> 0).toString(36)}`;
}

/**
 * 后端 polyline 的合法形态：数组、≥2 点、每点都是有限数字对。
 * 缺省（旧后端）/ 空数组 / 坏数据一律按「没有几何」处理，既不崩也不画假线。
 */
function validPolyline(leg: RouteLeg): [number, number][] | null {
  const polyline = leg.polyline;
  if (!Array.isArray(polyline) || polyline.length < 2) return null;
  for (const point of polyline) {
    if (!Array.isArray(point) || point.length < 2) return null;
    const [lat, lng] = point;
    if (!Number.isFinite(lat) || !Number.isFinite(lng)) return null;
  }
  return polyline;
}

/** 进 render key 的几何摘要；没有有效几何时为空串，与「无 polyline」的旧行为一致 */
function polylineKey(leg: RouteLeg): string {
  const polyline = validPolyline(leg);
  return polyline ? polylineDigest(polyline) : '';
}

/**
 * 计算一段 leg 的地图路径（高德需要的 GCJ-02 `[lng, lat]`）：
 * 1. 有真实 polyline（≥2 点）→ 逐点 WGS-84 → GCJ-02，按真实道路几何绘制；
 * 2. 否则回退两端点直连（ADR-009 的旧行为，旧后端与估算段没有几何）。
 * 返回 null 表示这段既没有几何、端点也不全，跳过。
 */
function legPath(leg: RouteLeg): LegPath | null {
  const polyline = validPolyline(leg);
  if (polyline) return polyline.map(([lat, lng]) => toAmapLngLat(lat, lng));
  if (
    leg.fromLatitude == null ||
    leg.fromLongitude == null ||
    leg.toLatitude == null ||
    leg.toLongitude == null
  ) {
    return null;
  }
  return [
    toAmapLngLat(leg.fromLatitude, leg.fromLongitude),
    toAmapLngLat(leg.toLatitude, leg.toLongitude),
  ];
}

interface DrawableLeg {
  leg: RouteLeg;
  /** 已转换的绘制路径；null = 这段没有可画坐标 */
  path: LegPath | null;
  /** 几何摘要（仅用于 render 缓存 key） */
  geometry: string;
}

/**
 * 高德 JS API 2.0 实现：2D/3D、分日路线、脉冲标记、定位与沿路线巡航。
 *
 * 坐标契约：`MapProvider` 的入参一律是 **WGS-84**（与 OSM/MapLibre/GPS 一致），
 * 这里在构造覆盖物与相机位置时统一转成高德需要的 GCJ-02，业务层不感知偏移。
 */
export class AMapProvider implements MapProvider {
  private AMap: AMapNamespace | null = null;
  private map: AMapMap | null = null;
  private overlays: AMapOverlay[] = [];
  private pendingItems: ItineraryItem[] = [];
  private pendingDay: number | null = null;
  private pendingLegs: RouteLeg[] = [];
  private pendingFocus: FocusTarget | null = null;
  private lastRenderKey = '';
  private didInitialFit = false;
  private tourTimer: number | null = null;
  private tourItems: ItineraryItem[] | null = null;
  private statusTimer: number | null = null;
  private status?: BaseMapStatus;
  private statusCb?: (status: BaseMapStatus) => void;
  private destroyed = false;

  constructor(
    private readonly viewMode: AMapViewMode = '3D',
    private readonly load: AMapLoader = loadAMap,
  ) {}

  init(container: HTMLDivElement, onBaseMapStatus?: (status: BaseMapStatus) => void): void {
    this.statusCb = onBaseMapStatus;
    this.statusTimer = window.setTimeout(() => {
      this.reportFailure(new Error('高德底图加载超时，请检查网络、Key 与安全密钥'));
    }, BASE_MAP_TIMEOUT_MS);

    void this.load()
      .then((AMap) => {
        if (this.destroyed) return;
        this.AMap = AMap;
        const pitched = this.viewMode === '3D';
        const map = new AMap.Map(container, {
          viewMode: this.viewMode,
          zoom: 11.2,
          zooms: [2, 20],
          center: DEFAULT_CENTER,
          pitch: pitched ? 55 : 0,
          rotation: pitched ? -18 : 0,
          mapStyle: 'amap://styles/normal',
          showLabel: true,
          showBuildingBlock: pitched,
          buildingAnimation: pitched,
        });
        this.map = map;
        map.on('complete', () => this.reportReady());
        map.on('error', (event) => this.reportFailure(event));

        if (AMap.Scale) map.addControl(new AMap.Scale());
        if (AMap.ToolBar) map.addControl(new AMap.ToolBar());
        if (pitched && AMap.ControlBar) {
          map.addControl(new AMap.ControlBar({ position: { top: '12px', right: '12px' } }));
        }

        // SDK 异步加载期间的数据与定位请求不能丢失。
        this.render(this.pendingItems, this.pendingDay, this.pendingLegs);
        if (this.pendingFocus) {
          const target = this.pendingFocus;
          this.pendingFocus = null;
          this.flyTo(target.lat, target.lng, target.zoom);
        }
        if (this.tourItems) this.startTour(this.tourItems);
      })
      .catch((error: unknown) => this.reportFailure(error));
  }

  private reportReady(): void {
    if (this.destroyed || this.status === 'ok') return;
    this.clearStatusTimer();
    this.status = 'ok';
    this.statusCb?.('ok');
  }

  private reportFailure(error: unknown): void {
    // 底图成功后忽略个别瓦片错误；失败状态下 complete 仍可恢复为 ok。
    if (this.destroyed || this.status === 'ok' || this.status === 'failed') return;
    this.clearStatusTimer();
    this.status = 'failed';
    console.error('[amap] 地图加载失败', error);
    this.statusCb?.('failed');
  }

  private clearStatusTimer(): void {
    if (this.statusTimer != null) {
      window.clearTimeout(this.statusTimer);
      this.statusTimer = null;
    }
  }

  render(items: ItineraryItem[], focusDay: number | null, routeLegs: RouteLeg[] = []): void {
    this.pendingItems = items;
    this.pendingDay = focusDay;
    this.pendingLegs = routeLegs;
    const AMap = this.AMap;
    const map = this.map;
    if (!AMap || !map) return;

    // 每段的绘制路径（GCJ-02）与几何摘要先算一次，key 与下面的绘制共用。
    const legEntries: DrawableLeg[] = routeLegs.map((leg) => ({
      leg,
      path: legPath(leg),
      geometry: polylineKey(leg),
    }));

    // id 不变但坐标、标题或时间被 Agent 修改时也必须重绘；
    // legs 一并进 key：确认提交后 itinerary 可能原样返回，只有转场段变了。
    const key = JSON.stringify([
      focusDay,
      items.map((item) => [
        item.id,
        item.title,
        item.description,
        item.itemType,
        item.dayIndex,
        item.latitude,
        item.longitude,
        item.startTime,
      ]),
      legEntries.map(({ leg, geometry }) => [
        leg.dayIndex,
        leg.sequence,
        leg.fromItemId,
        leg.toItemId,
        leg.fromLatitude,
        leg.fromLongitude,
        leg.toLatitude,
        leg.toLongitude,
        leg.source,
        // 真实几何变了也要重绘；只放「点数 + hash」摘要，不把整条坐标数组塞进 key（见 polylineDigest）。
        geometry,
      ]),
    ]);
    if (key === this.lastRenderKey) return;
    this.lastRenderKey = key;

    if (this.overlays.length > 0) map.remove(this.overlays);
    this.overlays = [];

    const addOverlay = (overlay: AMapOverlay) => {
      this.overlays.push(overlay);
      map.add(overlay);
    };

    const withGeo = items.filter((item) => item.latitude != null && item.longitude != null);

    // 有能画的路线段时逐段绘制：有真实 polyline 画真实几何，否则两端点直连（ADR-009）。
    const drawableLegs = legEntries.filter(
      (entry): entry is DrawableLeg & { path: LegPath } => entry.path != null,
    );

    if (drawableLegs.length > 0) {
      drawableLegs.forEach(({ leg, path }) => {
        const color = DAY_COLORS[(leg.dayIndex - 1) % DAY_COLORS.length];
        const focused = focusDay === leg.dayIndex;
        // estimated 段是本地估算走向，用虚线自报家门；amap 段是实测路径，实线。
        const strokeStyle = leg.source === 'amap' ? 'solid' : 'dashed';
        // 宽而透明的下层路线形成辉光；当前 Day 的上层路线加粗并提高不透明度。
        addOverlay(
          new AMap.Polyline({
            path,
            strokeColor: color,
            strokeWeight: focused ? 15 : 10,
            strokeOpacity: focused ? 0.3 : 0.14,
            strokeStyle,
            lineJoin: 'round',
            lineCap: 'round',
            zIndex: focused ? 20 : 10,
            bubble: true,
          }),
        );
        addOverlay(
          new AMap.Polyline({
            path,
            strokeColor: color,
            strokeWeight: focused ? 5 : 3,
            strokeOpacity: focused ? 1 : 0.68,
            strokeStyle,
            lineJoin: 'round',
            lineCap: 'round',
            zIndex: focused ? 30 : 21,
            bubble: true,
          }),
        );
      });
    } else {
      // 无 legs 数据（旧后端 / 拉取失败 / 空数组）时退回既有的「按天点位连线」逻辑。
      const pointsByDay = new Map<number, { color: string; points: [number, number][] }>();
      withGeo.forEach((item) => {
        const bucket = pointsByDay.get(item.dayIndex) ?? {
          color: DAY_COLORS[(item.dayIndex - 1) % DAY_COLORS.length],
          points: [],
        };
        bucket.points.push(toAmapLngLat(item.latitude as number, item.longitude as number));
        pointsByDay.set(item.dayIndex, bucket);
      });

      pointsByDay.forEach(({ color, points }, day) => {
        if (points.length < 2) return;
        const focused = focusDay === day;
        // 宽而透明的下层路线形成辉光；当前 Day 的上层路线加粗并提高不透明度。
        addOverlay(
          new AMap.Polyline({
            path: points,
            strokeColor: color,
            strokeWeight: focused ? 15 : 10,
            strokeOpacity: focused ? 0.3 : 0.14,
            lineJoin: 'round',
            lineCap: 'round',
            zIndex: focused ? 20 : 10,
            bubble: true,
          }),
        );
        addOverlay(
          new AMap.Polyline({
            path: points,
            strokeColor: color,
            strokeWeight: focused ? 5 : 3,
            strokeOpacity: focused ? 1 : 0.68,
            lineJoin: 'round',
            lineCap: 'round',
            zIndex: focused ? 30 : 21,
            bubble: true,
          }),
        );
      });
    }

    withGeo.forEach((item) => {
      addOverlay(
        new AMap.Marker({
          position: toAmapLngLat(item.latitude as number, item.longitude as number),
          content: buildMarkerElement(item, DAY_COLORS[(item.dayIndex - 1) % DAY_COLORS.length]),
          anchor: 'center',
          title: item.title,
          zIndex: focusDay === item.dayIndex ? 50 : 40,
        }),
      );
    });

    if (!this.didInitialFit && withGeo.length > 0) {
      this.didInitialFit = true;
      if (withGeo.length === 1) {
        map.setZoomAndCenter(
          14.6,
          toAmapLngLat(withGeo[0].latitude as number, withGeo[0].longitude as number),
          false,
          1200,
        );
      } else {
        map.setFitView(this.overlays, false, [72, 72, 72, 72], 14.6);
      }
    }
  }

  flyTo(lat: number, lng: number, zoom = 16): void {
    if (!this.map) {
      this.pendingFocus = { lat, lng, zoom };
      return;
    }
    if (this.viewMode === '3D') this.map.setPitch(65);
    this.map.setZoomAndCenter(zoom, toAmapLngLat(lat, lng), false, 1400);
  }

  startTour(items: ItineraryItem[]): void {
    this.tourItems = items;
    this.clearTourTimer();
    if (!this.map) return;

    const stops = items
      .filter((item) => item.latitude != null && item.longitude != null)
      .sort((a, b) => (a.startTime ?? '').localeCompare(b.startTime ?? ''));
    if (stops.length === 0) return;

    let index = 0;
    const step = () => {
      if (this.destroyed || !this.map) return;
      const stop = stops[index % stops.length];
      if (this.viewMode === '3D') {
        this.map.setPitch(66);
        this.map.setRotation(index % 2 === 0 ? 38 : -38);
      }
      this.map.setZoomAndCenter(
        16.8,
        toAmapLngLat(stop.latitude as number, stop.longitude as number),
        false,
        2200,
      );
      index += 1;
      this.tourTimer = window.setTimeout(step, 3000);
    };
    step();
  }

  private clearTourTimer(): void {
    if (this.tourTimer != null) {
      window.clearTimeout(this.tourTimer);
      this.tourTimer = null;
    }
  }

  stopTour(): void {
    this.tourItems = null;
    this.clearTourTimer();
  }

  setPitched(pitched: boolean): void {
    if (!this.map) return;
    this.map.setPitch(pitched ? 58 : 0);
    this.map.setRotation(pitched ? -16 : 0);
  }

  destroy(): void {
    this.destroyed = true;
    this.stopTour();
    this.clearStatusTimer();
    if (this.overlays.length > 0) this.map?.remove(this.overlays);
    this.overlays = [];
    this.map?.destroy();
    this.map = null;
    this.AMap = null;
    this.statusCb = undefined;
    this.pendingFocus = null;
  }
}
