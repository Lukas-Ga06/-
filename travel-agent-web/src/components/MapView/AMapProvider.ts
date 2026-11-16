import type { ItineraryItem } from '../../types/trip';
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
        this.render(this.pendingItems, this.pendingDay);
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

  render(items: ItineraryItem[], focusDay: number | null): void {
    this.pendingItems = items;
    this.pendingDay = focusDay;
    const AMap = this.AMap;
    const map = this.map;
    if (!AMap || !map) return;

    // id 不变但坐标、标题或时间被 Agent 修改时也必须重绘。
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
    ]);
    if (key === this.lastRenderKey) return;
    this.lastRenderKey = key;

    if (this.overlays.length > 0) map.remove(this.overlays);
    this.overlays = [];

    const withGeo = items.filter((item) => item.latitude != null && item.longitude != null);
    const pointsByDay = new Map<number, { color: string; points: [number, number][] }>();
    withGeo.forEach((item) => {
      const bucket = pointsByDay.get(item.dayIndex) ?? {
        color: DAY_COLORS[(item.dayIndex - 1) % DAY_COLORS.length],
        points: [],
      };
      bucket.points.push(toAmapLngLat(item.latitude as number, item.longitude as number));
      pointsByDay.set(item.dayIndex, bucket);
    });

    const addOverlay = (overlay: AMapOverlay) => {
      this.overlays.push(overlay);
      map.add(overlay);
    };

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
