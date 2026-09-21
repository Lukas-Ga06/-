import { useEffect, useRef } from 'react';
import maplibregl from 'maplibre-gl';
import 'maplibre-gl/dist/maplibre-gl.css';
import type { TripMapPoint } from '../../types/trip';

const COLORS = ['#007aff', '#34c759', '#ff9500', '#af52de', '#ff2d55', '#5ac8fa'];

interface Props {
  points: TripMapPoint[];
  onSelect: (tripId: number) => void;
}

/** 首页足迹地图：所有行程目的地 3D 俯瞰，点击标记进入行程 */
export default function FootprintMap({ points, onSelect }: Props) {
  const containerRef = useRef<HTMLDivElement>(null);
  const mapRef = useRef<maplibregl.Map | null>(null);
  const markersRef = useRef<maplibregl.Marker[]>([]);
  const pointsRef = useRef(points);
  const selectRef = useRef(onSelect);
  pointsRef.current = points;
  selectRef.current = onSelect;

  const draw = () => {
    const map = mapRef.current;
    if (!map) return;
    markersRef.current.forEach((m) => m.remove());
    markersRef.current = [];

    const located = pointsRef.current.filter((p) => p.lat != null && p.lng != null);
    located.forEach((p, idx) => {
      const el = document.createElement('div');
      el.className = 'fp-marker';
      el.style.setProperty('--mc', COLORS[idx % COLORS.length]);
      el.innerHTML = `<span class="fp-dot">${(p.destination || p.name || '旅')[0]}</span>`;
      el.title = `${p.name} · ${p.destination} · ¥${p.budget}`;
      el.onclick = () => selectRef.current(p.tripId);
      const marker = new maplibregl.Marker({ element: el, anchor: 'center' })
        .setLngLat([p.lng as number, p.lat as number])
        .addTo(map);
      markersRef.current.push(marker);
    });

    const coords = located.map((p) => [p.lng as number, p.lat as number] as [number, number]);
    if (coords.length > 1) {
      const bounds = new maplibregl.LngLatBounds();
      coords.forEach((c) => bounds.extend(c));
      map.fitBounds(bounds, { padding: 64, pitch: 42, duration: 2200, maxZoom: 8.5, essential: true });
    } else if (coords.length === 1) {
      map.flyTo({ center: coords[0], zoom: 9, pitch: 45, duration: 1800 });
    }
  };

  useEffect(() => {
    if (!containerRef.current || mapRef.current) return;
    const map = new maplibregl.Map({
      container: containerRef.current,
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
            paint: { 'raster-saturation': -0.12, 'raster-brightness-max': 0.98 },
          },
        ],
      },
      center: [110.5, 34.5],
      zoom: 4.2,
      pitch: 40,
      bearing: 0,
      maxPitch: 75,
      attributionControl: false,
    });
    map.addControl(new maplibregl.NavigationControl({ visualizePitch: true }), 'top-right');
    map.addControl(new maplibregl.AttributionControl({ compact: true }), 'bottom-right');
    map.on('load', draw);
    mapRef.current = map;
    return () => {
      map.remove();
      mapRef.current = null;
      markersRef.current = [];
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  // points 更新时重绘（含首次 load 完成前的挂起）
  const loadedRef = useRef(false);
  useEffect(() => {
    const map = mapRef.current;
    if (!map) return;
    if (map.loaded() || loadedRef.current) {
      loadedRef.current = true;
      draw();
    } else {
      map.once('load', draw);
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [points]);

  return <div ref={containerRef} style={{ width: '100%', height: '100%' }} />;
}
