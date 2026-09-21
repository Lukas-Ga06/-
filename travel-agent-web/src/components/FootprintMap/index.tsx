import { useCallback, useEffect, useRef, useState } from 'react';
import maplibregl from 'maplibre-gl';
import 'maplibre-gl/dist/maplibre-gl.css';
import type { TripMapPoint } from '../../types/trip';
import { footprintColor, orderedFootprints, routeGeoJson, tripDays, tripRangeLabel } from './footprintData';

const CHINA_OVERVIEW: [number, number] = [110.5, 34.5];

/** 低于该缩放级别时隐藏城市标签：视野内多个城市时标签会互相压住，悬停仍会显示 */
const LABEL_MIN_ZOOM = 5.2;

const MAP_STYLE: maplibregl.StyleSpecification = {
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
        'raster-saturation': -0.18,
        'raster-brightness-max': 0.99,
        'raster-contrast': 0.04,
      },
    },
  ],
};

interface Props {
  points: TripMapPoint[];
  /** 点击足迹标记：进入对应行程 */
  onSelect?: (tripId: number) => void;
  showControls?: boolean;
  /** 飞入足迹后自动环绕地球（首页缩略图建议关闭） */
  autoSpin?: boolean;
  /** 外部指定要聚焦的行程（例如从行程列表点击） */
  focusTripId?: number | null;
  /** 自增信号：每次变化触发一次足迹回放 */
  playSignal?: number;
  onTourStateChange?: (touring: boolean) => void;
}

/**
 * 旅行足迹 3D 地图：球体投影 + 倾斜透视相机 + 辉光航线 + 按时间顺序回放。
 *
 * 安全说明：由用户输入产生的文本（行程名/目的地）一律通过 DOM API + textContent 写入，
 * 不使用 maplibre 的 HTML 字符串接口（如 Popup#setHTML），避免 HTML 注入。
 */
export default function FootprintMap({
  points,
  onSelect,
  showControls = true,
  autoSpin = false,
  focusTripId = null,
  playSignal = 0,
  onTourStateChange,
}: Props) {
  const containerRef = useRef<HTMLDivElement | null>(null);
  const mapRef = useRef<maplibregl.Map | null>(null);
  const markersRef = useRef<maplibregl.Marker[]>([]);
  const popupRef = useRef<maplibregl.Popup | null>(null);
  const tourTimerRef = useRef<number | null>(null);
  const spinTimerRef = useRef<number | null>(null);
  const introDoneRef = useRef(false);

  const pointsRef = useRef(points);
  const selectRef = useRef(onSelect);
  const tourStateRef = useRef(onTourStateChange);
  pointsRef.current = points;
  selectRef.current = onSelect;
  tourStateRef.current = onTourStateChange;

  const [engine, setEngine] = useState<'3d' | '2d'>('3d');
  const [spinning, setSpinning] = useState(false);
  const [touring, setTouring] = useState(false);
  const [tourIndex, setTourIndex] = useState(0);
  const [tourTotal, setTourTotal] = useState(0);
  const [globe, setGlobe] = useState(false);
  const [labelsOff, setLabelsOff] = useState(true);

  const setTour = useCallback((value: boolean) => {
    setTouring(value);
    tourStateRef.current?.(value);
  }, []);

  /** 气泡内容：用真实 DOM 构建，文本一律 textContent 写入 */
  const buildPopup = useCallback((point: TripMapPoint, color: string): HTMLElement => {
    const root = document.createElement('div');
    root.className = 'fp-popup-card';
    root.style.setProperty('--mc', color);

    const head = document.createElement('div');
    head.className = 'fp-popup-head';
    const title = document.createElement('b');
    title.textContent = point.name || point.destination || '未命名行程';
    const status = document.createElement('span');
    status.className = 'fp-popup-status';
    status.textContent = point.status === 'CONFIRMED' ? '已确认' : '规划中';
    head.append(title, status);

    const meta = document.createElement('div');
    meta.className = 'fp-popup-meta';
    [
      `📍 ${point.destination ?? '未设置目的地'}`,
      `🗓 ${tripRangeLabel(point)} · ${tripDays(point)} 天`,
      `💰 预算 ¥${(point.budget ?? 0).toFixed(0)}`,
    ].forEach((text) => {
      const line = document.createElement('span');
      line.textContent = text;
      meta.append(line);
    });

    const hint = document.createElement('div');
    hint.className = 'fp-popup-hint';
    hint.textContent = '点击标记进入行程 →';

    root.append(head, meta, hint);
    return root;
  }, []);

  const showPopup = useCallback(
    (point: TripMapPoint, color: string, map: maplibregl.Map) => {
      popupRef.current?.remove();
      popupRef.current = new maplibregl.Popup({
        offset: 26,
        closeButton: false,
        className: 'fp-popup',
        maxWidth: '260px',
      })
        .setLngLat([point.lng as number, point.lat as number])
        .setDOMContent(buildPopup(point, color))
        .addTo(map);
    },
    [buildPopup],
  );

  const draw = useCallback(() => {
    const map = mapRef.current;
    if (!map) return [];

    markersRef.current.forEach((marker) => marker.remove());
    markersRef.current = [];

    const located = orderedFootprints(pointsRef.current);
    const colorOf = new Map<number, string>();
    located.forEach((point, index) => colorOf.set(point.tripId, footprintColor(index)));

    // 航线：按时间顺序连线，再叠一层虚线形成「飞行轨迹」质感
    const route = routeGeoJson(pointsRef.current);
    const source = map.getSource('fp-route') as maplibregl.GeoJSONSource | undefined;
    source?.setData(
      route
        ? { type: 'FeatureCollection', features: [route] }
        : { type: 'FeatureCollection', features: [] },
    );

    located.forEach((point) => {
      const color = colorOf.get(point.tripId) ?? footprintColor(0);
      const el = document.createElement('button');
      el.type = 'button';
      el.className = 'fp-marker';
      el.style.setProperty('--mc', color);
      el.setAttribute('aria-label', `${point.name ?? point.destination ?? '行程'}，点击进入`);

      const ring = document.createElement('span');
      ring.className = 'fp-ring';
      const dot = document.createElement('span');
      dot.className = 'fp-dot';
      dot.textContent = (point.destination || point.name || '旅').slice(0, 1);
      const label = document.createElement('span');
      label.className = 'fp-label';
      label.textContent = point.destination || point.name || '未命名';
      el.append(ring, dot, label);

      el.addEventListener('mouseenter', () => showPopup(point, color, map));
      el.addEventListener('focus', () => showPopup(point, color, map));
      el.addEventListener('mouseleave', () => popupRef.current?.remove());
      el.addEventListener('click', () => selectRef.current?.(point.tripId));

      markersRef.current.push(
        new maplibregl.Marker({ element: el, anchor: 'center' })
          .setLngLat([point.lng as number, point.lat as number])
          .addTo(map),
      );
    });

    return located;
  }, [showPopup]);

  const fitAll = useCallback((duration = 2200) => {
    const map = mapRef.current;
    if (!map) return;
    const located = orderedFootprints(pointsRef.current);
    if (located.length === 0) {
      map.flyTo({ center: CHINA_OVERVIEW, zoom: 3.2, pitch: 0, duration });
      return;
    }
    if (located.length === 1) {
      map.flyTo({
        center: [located[0].lng as number, located[0].lat as number],
        zoom: 8.4,
        pitch: 55,
        bearing: -16,
        duration,
        essential: true,
      });
      return;
    }
    const bounds = new maplibregl.LngLatBounds();
    located.forEach((point) => bounds.extend([point.lng as number, point.lat as number]));
    map.fitBounds(bounds, { padding: 84, maxZoom: 9.5, duration, essential: true });
    map.easeTo({ pitch: 55, bearing: -16, duration: Math.round(duration * 0.8) });
  }, []);

  const flyToTrip = useCallback(
    (tripId: number) => {
      const map = mapRef.current;
      if (!map) return;
      const located = orderedFootprints(pointsRef.current);
      const index = located.findIndex((point) => point.tripId === tripId);
      if (index < 0) return;
      const point = located[index];
      map.flyTo({
        center: [point.lng as number, point.lat as number],
        zoom: 9.4,
        pitch: 60,
        bearing: index % 2 === 0 ? -18 : 24,
        duration: 2400,
        curve: 1.6,
        essential: true,
      });
      showPopup(point, footprintColor(index), map);
    },
    [showPopup],
  );

  const stopTour = useCallback(() => {
    if (tourTimerRef.current != null) {
      window.clearTimeout(tourTimerRef.current);
      tourTimerRef.current = null;
    }
    setTour(false);
  }, [setTour]);

  const startTour = useCallback(() => {
    const map = mapRef.current;
    const located = orderedFootprints(pointsRef.current);
    if (!map || located.length === 0) return;

    stopTour();
    setTourTotal(located.length);
    setTourIndex(0);
    setTour(true);

    let index = 0;
    const step = () => {
      const point = located[index];
      map.flyTo({
        center: [point.lng as number, point.lat as number],
        zoom: 8.8,
        pitch: 60,
        bearing: index % 2 === 0 ? -22 : 26,
        duration: 2200,
        curve: 1.5,
        essential: true,
      });
      showPopup(point, footprintColor(index), map);
      setTourIndex(index);
      index += 1;
      if (index < located.length) {
        tourTimerRef.current = window.setTimeout(step, 3000);
      } else {
        tourTimerRef.current = window.setTimeout(() => {
          setTour(false);
          fitAll(2600);
        }, 3200);
      }
    };
    step();
  }, [fitAll, setTour, showPopup, stopTour]);

  const stopSpin = useCallback(() => {
    if (spinTimerRef.current != null) {
      window.clearInterval(spinTimerRef.current);
      spinTimerRef.current = null;
    }
    setSpinning(false);
  }, []);

  const startSpin = useCallback(() => {
    const map = mapRef.current;
    if (!map) return;
    stopSpin();
    setSpinning(true);
    spinTimerRef.current = window.setInterval(() => {
      map.setBearing(map.getBearing() + 0.14);
    }, 40);
  }, [stopSpin]);

  const setPitched = useCallback((pitched: boolean) => {
    setEngine(pitched ? '3d' : '2d');
    mapRef.current?.easeTo({ pitch: pitched ? 55 : 0, bearing: pitched ? -16 : 0, duration: 900 });
  }, []);

  // 初始化地图（只执行一次）
  useEffect(() => {
    const container = containerRef.current;
    if (!container) return;

    const supportsGlobe =
      typeof (maplibregl.Map.prototype as unknown as { setProjection?: unknown }).setProjection === 'function';

    const map = new maplibregl.Map({
      container,
      style: MAP_STYLE,
      center: CHINA_OVERVIEW,
      zoom: 2.4,
      pitch: 0,
      bearing: 0,
      maxPitch: 78,
      minZoom: 1,
      attributionControl: false,
      ...(supportsGlobe ? { projection: 'globe' as const } : {}),
    });
    map.addControl(new maplibregl.AttributionControl({ compact: true }), 'bottom-right');
    mapRef.current = map;
    setGlobe(supportsGlobe);

    const syncLabels = () => setLabelsOff(map.getZoom() < LABEL_MIN_ZOOM);
    map.on('zoom', syncLabels);
    syncLabels();

    map.on('load', () => {
      map.addSource('fp-route', {
        type: 'geojson',
        data: { type: 'FeatureCollection', features: [] },
      });
      map.addLayer({
        id: 'fp-route-glow',
        type: 'line',
        source: 'fp-route',
        layout: { 'line-cap': 'round', 'line-join': 'round' },
        paint: { 'line-color': '#007aff', 'line-width': 14, 'line-blur': 12, 'line-opacity': 0.24 },
      });
      map.addLayer({
        id: 'fp-route-core',
        type: 'line',
        source: 'fp-route',
        layout: { 'line-cap': 'round', 'line-join': 'round' },
        paint: { 'line-color': '#007aff', 'line-width': 2.6, 'line-opacity': 0.72 },
      });
      map.addLayer({
        id: 'fp-route-dash',
        type: 'line',
        source: 'fp-route',
        layout: { 'line-cap': 'butt', 'line-join': 'round' },
        paint: {
          'line-color': '#ffffff',
          'line-width': 1.6,
          'line-opacity': 0.85,
          'line-dasharray': [1.1, 2.2],
        },
      });

      const located = draw();
      // 从地球视角飞入首个足迹：先环绕地球，再落到行程范围
      if (located.length > 0 && !introDoneRef.current) {
        introDoneRef.current = true;
        map.flyTo({ center: CHINA_OVERVIEW, zoom: supportsGlobe ? 1.9 : 3, duration: 2400, essential: true });
        window.setTimeout(() => {
          fitAll(2600);
          if (autoSpin) {
            startSpin();
          }
        }, 2500);
      }
    });

    // 用户手动操作时停止环绕，避免「抢镜头」
    const stopOnInteract = () => stopSpin();
    map.on('dragstart', stopOnInteract);
    map.on('rotatestart', stopOnInteract);
    map.on('zoomstart', stopOnInteract);

    return () => {
      stopTour();
      stopSpin();
      popupRef.current?.remove();
      popupRef.current = null;
      markersRef.current = [];
      map.remove();
      mapRef.current = null;
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  // 数据变化时重绘
  useEffect(() => {
    const map = mapRef.current;
    if (!map) return;
    if (map.isStyleLoaded()) {
      draw();
    } else {
      map.once('load', () => draw());
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [points]);

  // 外部指定聚焦行程
  useEffect(() => {
    if (focusTripId != null) {
      flyToTrip(focusTripId);
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [focusTripId]);

  // 回放信号
  useEffect(() => {
    if (playSignal > 0) {
      startTour();
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [playSignal]);

  return (
    <div className={`fp-canvas ${labelsOff ? 'labels-off' : ''}`}>
      <div ref={containerRef} className="fp-map" />
      {globe && <div className="fp-globe-hint">🌐 球体视角</div>}
      {showControls && (
        <div className="fp-controls">
          <div className="fp-ctl-group">
            <button
              type="button"
              className={`fp-ctl ${engine === '3d' ? 'active' : ''}`}
              onClick={() => setPitched(true)}
              title="倾斜透视视角"
            >
              🏔 3D
            </button>
            <button
              type="button"
              className={`fp-ctl ${engine === '2d' ? 'active' : ''}`}
              onClick={() => setPitched(false)}
              title="平面俯视视角"
            >
              🗺 2D
            </button>
          </div>
          <button type="button" className="fp-ctl" onClick={() => fitAll()} title="回到全部足迹">
            🧭 复位
          </button>
          <button
            type="button"
            className={`fp-ctl ${spinning ? 'active' : ''}`}
            onClick={() => (spinning ? stopSpin() : startSpin())}
            title="环绕地球"
          >
            🌀 环绕
          </button>
          <button
            type="button"
            className={`fp-ctl tour ${touring ? 'active' : ''}`}
            onClick={() => (touring ? stopTour() : startTour())}
          >
            {touring ? `⏹ 回放中 ${tourIndex + 1}/${tourTotal}` : '▶ 回放足迹'}
          </button>
        </div>
      )}
    </div>
  );
}
