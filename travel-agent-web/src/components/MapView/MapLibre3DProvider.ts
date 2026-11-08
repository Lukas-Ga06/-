import * as maplibregl from 'maplibre-gl';
import 'maplibre-gl/dist/maplibre-gl.css';
import type { ItineraryItem } from '../../types/trip';
import type { MapProvider } from './MapProvider';
import { buildMarkerElement } from './markerElement';

export const DAY_COLORS = ['#007aff', '#34c759', '#ff9500', '#af52de', '#ff2d55'];

const DEFAULT_CENTER: [number, number] = [121.4737, 31.2304];

/**
 * MapLibre GL 3D 实现：倾斜透视相机、辉光路线、脉冲标记、沿行程自动巡航。
 * 使用 OSM 栅瓦片（免费无 Key），瓦片轻微降饱和以贴合 Apple 浅色主题。
 */
export class MapLibre3DProvider implements MapProvider {
  private map: maplibregl.Map | null = null;
  private markers: maplibregl.Marker[] = [];
  private loaded = false;
  private pendingItems: ItineraryItem[] = [];
  private pendingDay: number | null = null;
  private lastRenderKey = '';
  private didInitialFit = false;
  private tourTimer: number | null = null;
  private tileOkCount = 0;
  private tileErrors = 0;
  private statusReported = false;
  private statusTimer: number | null = null;
  private statusCb?: (status: 'ok' | 'failed') => void;

  init(container: HTMLDivElement, onBaseMapStatus?: (status: 'ok' | 'failed') => void): void {
    this.statusCb = onBaseMapStatus;
    this.map = new maplibregl.Map({
      container,
      style: {
        version: 8,
        sources: {
          osm: {
            type: 'raster',
            tiles: ['https://tile.openstreetmap.org/{z}/{x}/{y}.png'],
            tileSize: 256,
            attribution: '© OpenStreetMap contributors',
          },
        },
        layers: [
          {
            id: 'osm',
            type: 'raster',
            source: 'osm',
            paint: {
              'raster-saturation': -0.12,
              'raster-brightness-max': 0.98,
              'raster-contrast': 0.02,
            },
          },
        ],
      },
      center: DEFAULT_CENTER,
      zoom: 11.2,
      pitch: 55,
      bearing: -18,
      maxPitch: 75,
      attributionControl: false,
    });
    this.map.addControl(new maplibregl.NavigationControl({ visualizePitch: true }), 'top-right');
    this.map.addControl(new maplibregl.AttributionControl({ compact: true }), 'bottom-right');

    // 底图瓦片成败探测：成功占多数 → ok；失败占多数（弱网/外网受限）→ failed，各最多回调一次
    this.map.on('sourcedata', (e) => {
      if ((e as { tile?: unknown }).tile && e.sourceId === 'osm' && e.isSourceLoaded) {
        this.tileOkCount += 1;
        this.reportBaseMapStatus();
      }
    });
    this.map.on('error', (e) => {
      const err = e?.error as { status?: number; message?: string } | undefined;
      const ajaxLike = err != null && (err.status !== undefined || /fetch|network|ajax/i.test(err.message ?? ''));
      if (ajaxLike) {
        this.tileErrors += 1;
        this.reportBaseMapStatus();
      }
    });
    this.map.on('load', () => {
      if (!this.map) return;
      this.map.addSource('routes', { type: 'geojson', data: { type: 'FeatureCollection', features: [] } });
      this.map.addSource('routes-focus', { type: 'geojson', data: { type: 'FeatureCollection', features: [] } });
      this.map.addLayer({
        id: 'route-glow',
        type: 'line',
        source: 'routes',
        layout: { 'line-cap': 'round', 'line-join': 'round' },
        paint: { 'line-color': ['get', 'color'], 'line-width': 13, 'line-blur': 9, 'line-opacity': 0.3 },
      });
      this.map.addLayer({
        id: 'route-core',
        type: 'line',
        source: 'routes',
        layout: { 'line-cap': 'round', 'line-join': 'round' },
        paint: { 'line-color': ['get', 'color'], 'line-width': 3.6, 'line-opacity': 0.8 },
      });
      this.map.addLayer({
        id: 'route-glow-focus',
        type: 'line',
        source: 'routes-focus',
        layout: { 'line-cap': 'round', 'line-join': 'round' },
        paint: { 'line-color': ['get', 'color'], 'line-width': 18, 'line-blur': 12, 'line-opacity': 0.42 },
      });
      this.map.addLayer({
        id: 'route-core-focus',
        type: 'line',
        source: 'routes-focus',
        layout: { 'line-cap': 'round', 'line-join': 'round' },
        paint: { 'line-color': ['get', 'color'], 'line-width': 4.6, 'line-opacity': 1 },
      });
      this.loaded = true;
      this.render(this.pendingItems, this.pendingDay);
    });
    // 挂起兜底必须在 init 时启动：'load' 要等首个视觉完整渲染，
    // 瓦片全部挂起时 'load' 永不触发，计时器放在 load 回调里就永远启动不了
    this.statusTimer = window.setTimeout(() => this.reportBaseMapStatus(true), 8000);
  }

  /** 瓦片成败统计出来后回调一次：成功占多数算 ok，否则算 failed */
  private reportBaseMapStatus(timeout = false): void {
    if (this.statusReported) return;
    if (this.tileOkCount > 0 && this.tileErrors <= this.tileOkCount) {
      this.statusReported = true;
      this.clearStatusTimer();
      this.statusCb?.('ok');
    } else if ((timeout && this.tileOkCount === 0) || (this.tileErrors >= 5 && this.tileErrors > this.tileOkCount)) {
      // timeout：请求长时间挂起（无成功也无失败）也算底图不可用
      this.statusReported = true;
      this.clearStatusTimer();
      this.statusCb?.('failed');
    }
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
    if (!this.map || !this.loaded) {
      return;
    }
    const key = `${items.map((i) => `${i.id ?? i.title}@${i.dayIndex}`).join('|')}#${focusDay ?? ''}`;
    if (key === this.lastRenderKey) return;
    this.lastRenderKey = key;

    const withGeo = items.filter((i) => i.latitude != null && i.longitude != null);
    const features = new Map<number, { color: string; points: [number, number][] }>();
    withGeo.forEach((item) => {
      const bucket = features.get(item.dayIndex) ?? {
        color: DAY_COLORS[(item.dayIndex - 1) % DAY_COLORS.length],
        points: [],
      };
      bucket.points.push([item.longitude as number, item.latitude as number]);
      features.set(item.dayIndex, bucket);
    });

    const routeFeatures: GeoJSON.Feature[] = [];
    features.forEach((bucket, day) => {
      if (bucket.points.length < 2) return;
      routeFeatures.push({
        type: 'Feature',
        properties: { day, color: bucket.color },
        geometry: { type: 'LineString', coordinates: bucket.points },
      });
    });
    const focusColor = focusDay
      ? DAY_COLORS[(focusDay - 1) % DAY_COLORS.length]
      : DAY_COLORS[0];
    const focusFeature = routeFeatures.filter((f) => f.properties?.day === focusDay);
    (this.map.getSource('routes') as maplibregl.GeoJSONSource | undefined)?.setData({
      type: 'FeatureCollection',
      features: routeFeatures,
    });
    (this.map.getSource('routes-focus') as maplibregl.GeoJSONSource | undefined)?.setData({
      type: 'FeatureCollection',
      features: focusFeature,
    });
    if (this.map.getLayer('route-glow-focus') && focusFeature.length > 0) {
      this.map.setPaintProperty('route-glow-focus', 'line-color', focusColor);
      this.map.setPaintProperty('route-core-focus', 'line-color', focusColor);
    }

    // 标记：玻璃光点 + 类型图标 + 名称
    this.markers.forEach((m) => m.remove());
    this.markers = [];
    withGeo.forEach((item) => {
      const el = buildMarkerElement(item, DAY_COLORS[(item.dayIndex - 1) % DAY_COLORS.length]);
      const marker = new maplibregl.Marker({ element: el, anchor: 'center' })
        .setLngLat([item.longitude as number, item.latitude as number])
        .addTo(this.map as maplibregl.Map);
      this.markers.push(marker);
    });

    // 首次拿到行程点：自动飞到行程范围（3D 视角）
    if (!this.didInitialFit && withGeo.length > 0 && this.map) {
      this.didInitialFit = true;
      const bounds = new maplibregl.LngLatBounds();
      withGeo.forEach((item) =>
        bounds.extend([item.longitude as number, item.latitude as number]),
      );
      this.map.fitBounds(bounds, {
        padding: 72,
        pitch: 55,
        bearing: -18,
        duration: 2400,
        maxZoom: 14.6,
        essential: true,
      });
    }
  }

  flyTo(lat: number, lng: number, zoom = 16): void {
    this.map?.flyTo({
      center: [lng, lat],
      zoom,
      pitch: 65,
      bearing: this.map.getBearing(),
      duration: 2400,
      curve: 1.6,
      essential: true,
    });
  }

  startTour(items: ItineraryItem[]): void {
    if (!this.map) return;
    this.stopTour();
    const stops = items
      .filter((i) => i.latitude != null && i.longitude != null)
      .sort((a, b) => (a.startTime ?? '').localeCompare(b.startTime ?? ''));
    if (stops.length === 0) return;

    let index = 0;
    const step = () => {
      const stop = stops[index % stops.length];
      this.map?.flyTo({
        center: [stop.longitude as number, stop.latitude as number],
        zoom: 16.8,
        pitch: 66,
        bearing: index % 2 === 0 ? 38 : -38,
        duration: 2800,
        curve: 1.55,
        essential: true,
      });
      index += 1;
      this.tourTimer = window.setTimeout(step, 3000);
    };
    step();
  }

  stopTour(): void {
    if (this.tourTimer != null) {
      window.clearTimeout(this.tourTimer);
      this.tourTimer = null;
    }
  }

  setPitched(pitched: boolean): void {
    this.map?.easeTo({ pitch: pitched ? 58 : 0, duration: 700 });
  }

  destroy(): void {
    this.stopTour();
    this.clearStatusTimer();
    this.map?.remove();
    this.map = null;
    this.loaded = false;
    this.markers = [];
  }
}
