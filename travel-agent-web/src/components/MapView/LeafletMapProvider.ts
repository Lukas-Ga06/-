import L from 'leaflet';
import 'leaflet/dist/leaflet.css';
import type { ItineraryItem } from '../../types/trip';
import type { MapProvider } from './MapProvider';

const DAY_COLORS = ['#1677ff', '#52c41a', '#fa8c16', '#722ed1', '#eb2f96'];

/** 基于 Leaflet + OpenStreetMap 的实现（无需地图 Key） */
export class LeafletMapProvider implements MapProvider {
  private map: L.Map | null = null;
  private layer: L.LayerGroup | null = null;

  init(container: HTMLDivElement): void {
    this.map = L.map(container, { zoomControl: true }).setView([31.2304, 121.4737], 12);
    L.tileLayer('https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png', {
      maxZoom: 18,
      attribution: '© OpenStreetMap contributors',
    }).addTo(this.map);
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
    this.map?.remove();
    this.map = null;
    this.layer = null;
  }
}
