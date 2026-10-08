import { useCallback, useEffect, useRef, useState } from 'react';
import maplibregl from 'maplibre-gl';
import 'maplibre-gl/dist/maplibre-gl.css';
import type { TripMapPoint } from '../../types/trip';
import {
  cityFootprints,
  footprintColor,
  orderedFootprints,
  routeCoordinates,
  tripDays,
  tripRangeLabel,
} from './footprintData';

const CHINA_OVERVIEW: [number, number] = [110.5, 34.5];

/** 低于该缩放级别时隐藏城市标签：视野内多个城市时标签会互相压住，悬停仍会显示 */
const LABEL_MIN_ZOOM = 5.2;

export type FootprintTheme = 'light' | 'dark';
export type FootprintMode = 'trip' | 'city';

/** 免 Key 的深色矢量底图（OpenFreeMap）；不可达时会自动退回「压暗的 OSM 栅格」 */
const DARK_STYLE_URL = 'https://tiles.openfreemap.org/styles/dark';

function buildRasterStyle(theme: FootprintTheme): maplibregl.StyleSpecification {
  const dark = theme === 'dark';
  return {
    version: 8,
    sources: {
      base: {
        type: 'raster',
        tiles: ['https://tile.openstreetmap.org/{z}/{x}/{y}.png'],
        tileSize: 256,
        attribution: '© OpenStreetMap contributors',
      },
    },
    layers: [
      {
        // 纸底：瓦片未加载或留白处也是"纸"的颜色，而不是刺眼的纯白
        id: 'paper',
        type: 'background',
        paint: { 'background-color': dark ? '#12151b' : '#f3ead8' },
      },
      {
        id: 'base',
        type: 'raster',
        source: 'base',
        paint: dark
          ? {
              // 深色兜底：不做反转，只压暗并降饱和，配合深色面板形成 20% 的深色块
              'raster-saturation': -0.4,
              'raster-brightness-min': 0.02,
              'raster-brightness-max': 0.42,
              'raster-contrast': 0.18,
            }
          : {
              // 纸质地图观感：强降饱和 + 略降对比 + 提亮暗部，把底图退成一张描线纸
              'raster-saturation': -0.45,
              'raster-brightness-min': 0.08,
              'raster-brightness-max': 1,
              'raster-contrast': -0.12,
            },
      },
    ],
  };
}

interface Props {
  points: TripMapPoint[];
  /** trip：每个行程一个节点；city：按城市聚合，节点上显示到访次数 */
  mode?: FootprintMode;
  theme?: FootprintTheme;
  /** trip 模式点击节点：进入对应行程 */
  onSelect?: (tripId: number) => void;
  /** city 模式点击节点：打开该城市详情 */
  onCityClick?: (city: string) => void;
  showControls?: boolean;
  /** 飞入足迹后自动环绕地球（首页缩略图建议关闭） */
  autoSpin?: boolean;
  /** 外部指定要聚焦的行程（例如从列表点击） */
  focusTripId?: number | null;
  /** 自增信号：每次变化触发一次足迹回放 */
  playSignal?: number;
  onTourStateChange?: (touring: boolean) => void;
  /** 初始俯仰角（度）：首页缩略图给一个倾斜视角会更立体，默认 0 = 正俯视 */
  pitch?: number;
  /** 初始方位角（度） */
  bearing?: number;
  /** 初始缩放：调小一点能看到球体边缘，"球"的感觉更明确 */
  zoom?: number;
}

/**
 * 旅行足迹 3D 地图：球体投影 + 倾斜透视相机 + 辉光航线 + 按时间顺序回放。
 *
 * 安全说明：由用户输入产生的文本（行程名/目的地）一律通过 DOM API + textContent 写入，
 * 不使用 maplibre 的 HTML 字符串接口（如 Popup#setHTML），避免 HTML 注入。
 */
export default function FootprintMap({
  points,
  mode = 'trip',
  theme = 'light',
  onSelect,
  onCityClick,
  showControls = true,
  autoSpin = false,
  focusTripId = null,
  playSignal = 0,
  onTourStateChange,
  pitch: initialPitch = 0,
  bearing: initialBearing = 0,
  zoom: initialZoom = 2.4,
}: Props) {
  const containerRef = useRef<HTMLDivElement | null>(null);
  const mapRef = useRef<maplibregl.Map | null>(null);
  const markersRef = useRef<maplibregl.Marker[]>([]);
  const popupRef = useRef<maplibregl.Popup | null>(null);
  const tourTimerRef = useRef<number | null>(null);
  const spinTimerRef = useRef<number | null>(null);
  const introDoneRef = useRef(false);

  const pointsRef = useRef(points);
  const modeRef = useRef(mode);
  const selectRef = useRef(onSelect);
  const cityClickRef = useRef(onCityClick);
  const tourStateRef = useRef(onTourStateChange);
  pointsRef.current = points;
  modeRef.current = mode;
  selectRef.current = onSelect;
  cityClickRef.current = onCityClick;
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

  /** 一行元信息（都是纯文本，安全写入） */
  const metaLine = (text: string) => {
    const line = document.createElement('span');
    line.textContent = text;
    return line;
  };

  const popupShell = (titleText: string, badgeText: string, color: string) => {
    const root = document.createElement('div');
    root.className = 'fp-popup-card';
    root.style.setProperty('--mc', color);

    const head = document.createElement('div');
    head.className = 'fp-popup-head';
    const title = document.createElement('b');
    title.textContent = titleText;
    const badge = document.createElement('span');
    badge.className = 'fp-popup-status';
    badge.textContent = badgeText;
    head.append(title, badge);

    const meta = document.createElement('div');
    meta.className = 'fp-popup-meta';
    root.append(head, meta);
    return { root, meta };
  };

  const tripPopup = useCallback((point: TripMapPoint, color: string): HTMLElement => {
    const { root, meta } = popupShell(
      point.name || point.destination || '未命名行程',
      point.status === 'CONFIRMED' ? '已确认' : '规划中',
      color,
    );
    meta.append(
      metaLine(`📍 ${point.destination ?? '未设置目的地'}`),
      metaLine(`🗓 ${tripRangeLabel(point)} · ${tripDays(point)} 天`),
      metaLine(`💰 预算 ¥${(point.budget ?? 0).toFixed(0)}`),
    );
    const hint = document.createElement('div');
    hint.className = 'fp-popup-hint';
    hint.textContent = '点击标记进入行程 →';
    root.append(hint);
    return root;
  }, []);

  const cityPopup = useCallback((city: string, trips: TripMapPoint[], color: string): HTMLElement => {
    const visits = trips.length;
    const days = trips.reduce((sum, point) => sum + tripDays(point), 0);
    const budget = trips.reduce((sum, point) => sum + (point.budget ?? 0), 0);
    const { root, meta } = popupShell(city, `${visits} 次到访`, color);
    meta.append(
      metaLine(`🗓 累计 ${days} 天`),
      metaLine(`💰 预算合计 ¥${budget.toFixed(0)}`),
      metaLine(`🕒 最近 ${trips.map((t) => t.startDate).filter(Boolean).sort().reverse()[0] ?? '—'}`),
    );
    const hint = document.createElement('div');
    hint.className = 'fp-popup-hint';
    hint.textContent = '点击查看该城市足迹 →';
    root.append(hint);
    return root;
  }, []);

  const showPopupAt = useCallback(
    (content: HTMLElement, lng: number, lat: number, map: maplibregl.Map) => {
      popupRef.current?.remove();
      popupRef.current = new maplibregl.Popup({
        offset: 26,
        closeButton: false,
        className: 'fp-popup',
        maxWidth: '260px',
      })
        .setLngLat([lng, lat])
        .setDOMContent(content)
        .addTo(map);
    },
    [],
  );

  /** 标记元素：城市节点 / 行程节点共用，city 模式额外显示到访次数 */
  const markerElement = (label: string, initial: string, color: string, visits?: number) => {
    const el = document.createElement('button');
    el.type = 'button';
    el.className = `fp-marker ${theme === 'dark' ? 'fp-dark' : ''}`;
    el.style.setProperty('--mc', color);
    el.setAttribute('aria-label', visits && visits > 1 ? `${label}，${visits} 次到访` : label);

    const ring = document.createElement('span');
    ring.className = 'fp-ring';
    const dot = document.createElement('span');
    dot.className = 'fp-dot';
    dot.textContent = initial;
    const text = document.createElement('span');
    text.className = 'fp-label';
    text.textContent = label;
    el.append(ring, dot, text);
    if (visits && visits > 1) {
      const badge = document.createElement('span');
      badge.className = 'fp-visits';
      badge.textContent = `×${visits}`;
      el.append(badge);
    }
    return el;
  };

  /** 航线坐标：city 模式按首次到访顺序串城市，trip 模式按行程时间顺序 */
  const routeOf = useCallback((located: TripMapPoint[]): [number, number][] => {
    if (modeRef.current !== 'city') {
      return routeCoordinates(pointsRef.current);
    }
    return cityFootprints(pointsRef.current)
      .slice()
      .sort((a, b) => (a.firstVisit ?? '').localeCompare(b.firstVisit ?? ''))
      .map((c) => [c.lng, c.lat] as [number, number]);
  }, []);

  const draw = useCallback(() => {
    const map = mapRef.current;
    if (!map) return [] as TripMapPoint[];

    markersRef.current.forEach((marker) => marker.remove());
    markersRef.current = [];

    const cityMode = modeRef.current === 'city';
    const located = orderedFootprints(pointsRef.current);

    // 航线：先按时间顺序连线，再叠加一层虚线形成「飞行轨迹」质感
    const coordinates = routeOf(located);
    const source = map.getSource('fp-route') as maplibregl.GeoJSONSource | undefined;
    source?.setData(
      coordinates.length > 1
        ? {
            type: 'FeatureCollection',
            features: [{ type: 'Feature', properties: {}, geometry: { type: 'LineString', coordinates } }],
          }
        : { type: 'FeatureCollection', features: [] },
    );

    if (cityMode) {
      const cities = cityFootprints(pointsRef.current)
        .slice()
        .sort((a, b) => (a.firstVisit ?? '').localeCompare(b.firstVisit ?? ''));
      cities.forEach((city, index) => {
        const color = footprintColor(index);
        const el = markerElement(city.city, city.city.slice(0, 1), color, city.visits);
        el.addEventListener('mouseenter', () =>
          showPopupAt(cityPopup(city.city, city.trips, color), city.lng, city.lat, map),
        );
        el.addEventListener('focus', () =>
          showPopupAt(cityPopup(city.city, city.trips, color), city.lng, city.lat, map),
        );
        el.addEventListener('mouseleave', () => popupRef.current?.remove());
        el.addEventListener('click', () => cityClickRef.current?.(city.city));
        markersRef.current.push(
          new maplibregl.Marker({ element: el, anchor: 'center' })
            .setLngLat([city.lng, city.lat])
            .addTo(map),
        );
      });
      return located;
    }

    located.forEach((point, index) => {
      const color = footprintColor(index);
      const el = markerElement(
        point.destination || point.name || '未命名',
        (point.destination || point.name || '旅').slice(0, 1),
        color,
      );
      el.addEventListener('mouseenter', () =>
        showPopupAt(tripPopup(point, color), point.lng as number, point.lat as number, map),
      );
      el.addEventListener('focus', () =>
        showPopupAt(tripPopup(point, color), point.lng as number, point.lat as number, map),
      );
      el.addEventListener('mouseleave', () => popupRef.current?.remove());
      el.addEventListener('click', () => selectRef.current?.(point.tripId));
      markersRef.current.push(
        new maplibregl.Marker({ element: el, anchor: 'center' })
          .setLngLat([point.lng as number, point.lat as number])
          .addTo(map),
      );
    });

    return located;
  }, [cityPopup, routeOf, showPopupAt, theme, tripPopup]);

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
      showPopupAt(tripPopup(point, footprintColor(index)), point.lng as number, point.lat as number, map);
    },
    [showPopupAt, tripPopup],
  );

  const stopTour = useCallback(() => {
    if (tourTimerRef.current != null) {
      window.clearTimeout(tourTimerRef.current);
      tourTimerRef.current = null;
    }
    setTour(false);
  }, [setTour]);

  /** 回放站点：city 模式按城市，trip 模式按行程 */
  const tourStops = useCallback(() => {
    if (modeRef.current === 'city') {
      const cities = cityFootprints(pointsRef.current)
        .slice()
        .sort((a, b) => (a.firstVisit ?? '').localeCompare(b.firstVisit ?? ''));
      return cities.map((city, index) => ({
        lng: city.lng,
        lat: city.lat,
        color: footprintColor(index),
        popup: () => cityPopup(city.city, city.trips, footprintColor(index)),
      }));
    }
    return orderedFootprints(pointsRef.current).map((point, index) => ({
      lng: point.lng as number,
      lat: point.lat as number,
      color: footprintColor(index),
      popup: () => tripPopup(point, footprintColor(index)),
    }));
  }, [cityPopup, tripPopup]);

  const startTour = useCallback(() => {
    const map = mapRef.current;
    const stops = tourStops();
    if (!map || stops.length === 0) return;

    stopTour();
    setTourTotal(stops.length);
    setTourIndex(0);
    setTour(true);

    let index = 0;
    const step = () => {
      const stop = stops[index];
      map.flyTo({
        center: [stop.lng, stop.lat],
        zoom: modeRef.current === 'city' ? 4.6 : 8.8,
        pitch: modeRef.current === 'city' ? 45 : 60,
        bearing: index % 2 === 0 ? -22 : 26,
        duration: 2200,
        curve: 1.5,
        essential: true,
      });
      showPopupAt(stop.popup(), stop.lng, stop.lat, map);
      setTourIndex(index);
      index += 1;
      if (index < stops.length) {
        tourTimerRef.current = window.setTimeout(step, 3000);
      } else {
        tourTimerRef.current = window.setTimeout(() => {
          setTour(false);
          fitAll(2600);
        }, 3200);
      }
    };
    step();
  }, [fitAll, setTour, showPopupAt, stopTour, tourStops]);

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

  // 初始化地图（主题变化时重建）
  useEffect(() => {
    const container = containerRef.current;
    if (!container) return;

    const supportsGlobe =
      typeof (maplibregl.Map.prototype as unknown as { setProjection?: unknown }).setProjection === 'function';

    const map = new maplibregl.Map({
      container,
      // 深色主题优先用免 Key 的矢量样式；加载失败会退回压暗的 OSM 栅格
      style: theme === 'dark' ? DARK_STYLE_URL : buildRasterStyle('light'),
      center: CHINA_OVERVIEW,
      zoom: initialZoom,
      pitch: initialPitch,
      bearing: initialBearing,
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

    let styleFallbackApplied = false;
    map.on('error', (event) => {
      if (theme !== 'dark' || styleFallbackApplied) {
        return;
      }
      const message = String((event as unknown as { error?: { message?: string } })?.error?.message ?? '');
      if (/style|fetch|load|network/i.test(message)) {
        styleFallbackApplied = true;
        console.warn('[footprint] 深色底图加载失败，已退回压暗的 OSM 栅格底图');
        map.setStyle(buildRasterStyle('dark'));
      }
    });

    // style.load 在每次样式加载完成后都会触发（含上面的兜底切换），因此图层在这里注册
    map.on('style.load', () => {
      if (map.getSource('fp-route')) {
        draw();
        return;
      }
      map.addSource('fp-route', {
        type: 'geojson',
        data: { type: 'FeatureCollection', features: [] },
      });
      const routeColor = theme === 'dark' ? '#4da3ff' : '#1f6feb';
      map.addLayer({
        id: 'fp-route-glow',
        type: 'line',
        source: 'fp-route',
        layout: { 'line-cap': 'round', 'line-join': 'round' },
        paint: { 'line-color': routeColor, 'line-width': 14, 'line-blur': 12, 'line-opacity': 0.26 },
      });
      map.addLayer({
        id: 'fp-route-core',
        type: 'line',
        source: 'fp-route',
        layout: { 'line-cap': 'round', 'line-join': 'round' },
        paint: { 'line-color': routeColor, 'line-width': 2.6, 'line-opacity': 0.75 },
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
    // 初始视角只在首次初始化时生效；pitch/bearing 变化会重建地图（首页给的是固定值）
  }, [theme, initialPitch, initialBearing, initialZoom]);

  // 数据或模式变化时重绘
  useEffect(() => {
    const map = mapRef.current;
    if (!map) return;
    if (map.isStyleLoaded()) {
      draw();
    } else {
      map.once('load', () => draw());
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [points, mode]);

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
    <div className={`fp-canvas ${labelsOff ? 'labels-off' : ''} ${theme === 'dark' ? 'fp-theme-dark' : ''}`}>
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
