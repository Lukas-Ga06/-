import L from 'leaflet';
import 'leaflet/dist/leaflet.css';
import type { ItineraryItem } from '../../types/trip';
import type { MapProvider } from './MapProvider';

const DAY_COLORS = ['#1677ff', '#52c41a', '#fa8c16', '#722ed1', '#eb2f96'];

/** 基于 Leaflet + OpenStreetMap 的实现（无需地图 Key） */
export class LeafletMapProvider implements MapProvider {
  private map: L.Map | null = null;
  private layer: L.LayerGroup | null = null;
  private tileOkCount = 0;
  private tileErrors = 0;
  private statusReported = false;
  private statusTimer: number | null = null;
  private statusCb?: (status: 'ok' | 'failed') => void;

  /** 与 MapLibre3DProvider 同一套判定：成功占多数算 ok，失败占多数或长时间无响应算 failed */
  private reportBaseMapStatus(timeout = false): void {
    if (this.statusReported) return;
    if (this.tileOkCount > 0 && this.tileErrors <= this.tileOkCount) {
      this.statusReported = true;
      this.clearStatusTimer();
      this.statusCb?.('ok');
    } else if ((timeout && this.tileOkCount === 0) || (this.tileErrors >= 5 && this.tileErrors > this.tileOkCount)) {
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

  init(container: HTMLDivElement, onBaseMapStatus?: (status: 'ok' | 'failed') => void): void {
    this.statusCb = onBaseMapStatus;
    this.map = L.map(container, { zoomControl: true }).setView([31.2304, 121.4737], 12);
    const tiles = L.tileLayer('https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png', {
      maxZoom: 18,
      attribution: '© OpenStreetMap contributors',
    })
      .addTo(this.map);
    tiles.on('tileload', () => {
      this.tileOkCount += 1;
      this.reportBaseMapStatus();
    });
    tiles.on('tileerror', () => {
      this.tileErrors += 1;
      this.reportBaseMapStatus();
    });
    // 挂起兜底：瓦片一直无响应（无成功也无失败事件）时按失败上报
    this.statusTimer = window.setTimeout(() => this.reportBaseMapStatus(true), 8000);
    this.layer = L.layerGroup().addTo(this.map);
  }

  render(items: ItineraryItem[], focusDay: number | null): void {
    if (!this.map || !this.layer) return;
    this.layer.clearLayers();
    const shown = focusDay ? items.filter((i) => i.dayIndex === focusDay) : items;
    const points: L.LatLngExpression[] = [];

    shown
      .filter((i) => i.latitude != null && i.longitude != null)
      .forEach((item) => {
        const color = DAY_COLORS[(item.dayIndex - 1) % DAY_COLORS.length];
        L.circleMarker([item.latitude as number, item.longitude as number], {
          radius: 8,
          color: '#ffffff',
          weight: 2,
          fillColor: color,
          fillOpacity: 1,
        })
          .bindTooltip(item.title, { direction: 'top' })
          .addTo(this.layer as L.LayerGroup);
        points.push([item.latitude as number, item.longitude as number]);
      });

    if (points.length > 1) {
      L.polyline(points, { color: '#1677ff', weight: 2, dashArray: '6 8', opacity: 0.7 })
        .addTo(this.layer);
    }

    if (points.length === 1) {
      this.map.setView(points[0], 14);
    } else if (points.length > 1) {
      this.map.fitBounds(L.latLngBounds(points).pad(0.25));
    }
  }

  flyTo(lat: number, lng: number, zoom = 15): void {
    this.map?.flyTo([lat, lng], zoom, { duration: 0.8 });
  }

  destroy(): void {
    this.clearStatusTimer();
    this.map?.remove();
    this.map = null;
    this.layer = null;
  }
}
