import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { Tooltip } from 'antd';
import {
  AimOutlined,
  BorderOutlined,
  DeploymentUnitOutlined,
  GlobalOutlined,
  PauseCircleOutlined,
  PlayCircleOutlined,
  ReloadOutlined,
  SyncOutlined,
} from '@ant-design/icons';
import 'maplibre-gl/dist/maplibre-gl.css';
import type { GeoJSONSource, Map, Marker, Popup } from 'maplibre-gl';
import { loadMaplibre, type MapLibre } from './maplibreLoader';
import type { TripMapPoint } from '../../types/trip';
import { wgs84ToGcj02 } from '../../lib/coords';
import {
  buildAmapRasterStyle,
  buildOsmRasterStyle,
  defaultFootprintBasemap,
  type FootprintBasemap,
} from './basemap';
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

/**
 * 业务坐标是 WGS-84；高德瓦片是 GCJ-02。
 * 只在「底图 = 高德」时把展示坐标偏移一次，业务数据本身不被改写。
 */
function toDisplayPoint(point: TripMapPoint): TripMapPoint {
  if (point.lat == null || point.lng == null) {
    return point;
  }
  const [lat, lng] = wgs84ToGcj02(point.lat, point.lng);
  return { ...point, lat, lng };
}

interface Props {
  points: TripMapPoint[];
  /** trip：每个行程一个节点；city：按城市聚合，节点上显示到访次数 */
  mode?: FootprintMode;
  theme?: FootprintTheme;
  /** 底图：默认高德栅格；`osm` 为原 OSM 底图（含 OpenFreeMap 深色矢量） */
  basemap?: FootprintBasemap;
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
  /**
   * 地图引擎不可用（加载失败 / 无网络）时通知父级。
   * 父级据此渲染与「没有足迹」不同的紧凑错误态，而不是把错误伪装成空数据。
   */
  onErrorChange?: (unavailable: boolean) => void;
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
  basemap = defaultFootprintBasemap(),
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
  onErrorChange,
}: Props) {
  const containerRef = useRef<HTMLDivElement | null>(null);
  /** 运行时按需加载的 maplibre 模块；加载完成前所有地图操作都是 no-op */
  const engineRef = useRef<MapLibre | null>(null);
  const mapRef = useRef<Map | null>(null);
  const markersRef = useRef<Marker[]>([]);
  const popupRef = useRef<Popup | null>(null);
  const tourTimerRef = useRef<number | null>(null);
  const spinTimerRef = useRef<number | null>(null);
  const introDoneRef = useRef(false);
  const [activeBasemap, setActiveBasemap] = useState<FootprintBasemap>(basemap);
  const [engineLoading, setEngineLoading] = useState(true);
  const [engineError, setEngineError] = useState<string | null>(null);
  /** 递增触发重新加载地图引擎（首次失败后给用户「重试」按钮） */
  const [loadAttempt, setLoadAttempt] = useState(0);

  // 高德底图用 GCJ-02，OSM 用 WGS-84；业务 points 始终是 WGS-84。
  const geoPoints = useMemo(
    () => (activeBasemap === 'amap' ? points.map(toDisplayPoint) : points),
    [points, activeBasemap],
  );

  // 渲染期不写 ref：把最新 props 镜像进 ref 供地图事件/定时器等异步回调读取。
  // 本 effect 必须声明在下面所有依赖这些 ref 的 effect 之前，同一轮提交里才先更新镜像再使用。
  const pointsRef = useRef(geoPoints);
  const modeRef = useRef(mode);
  const selectRef = useRef(onSelect);
  const cityClickRef = useRef(onCityClick);
  const tourStateRef = useRef(onTourStateChange);
  useEffect(() => {
    pointsRef.current = geoPoints;
    modeRef.current = mode;
    selectRef.current = onSelect;
    cityClickRef.current = onCityClick;
    tourStateRef.current = onTourStateChange;
  });

  // activeBasemap 是 props.basemap 的可写镜像（瓦片不可达时会被降级成 osm），
  // 因此用「prop 变化时在渲染期收敛」而不是 effect + setState，避免多跑一轮提交。
  const [basemapSeed, setBasemapSeed] = useState(basemap);
  if (basemapSeed !== basemap) {
    setBasemapSeed(basemap);
    setActiveBasemap(basemap);
  }

  // 把「地图不可用」上报给父级：父级用它区分「没有足迹」与「地图加载失败」
  useEffect(() => {
    onErrorChange?.(Boolean(engineError));
  }, [engineError, onErrorChange]);

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
      metaLine(`目的地：${point.destination ?? '未设置'}`),
      metaLine(`日期：${tripRangeLabel(point)} · ${tripDays(point)} 天`),
      metaLine(`预算：¥${(point.budget ?? 0).toFixed(0)}`),
    );
    const hint = document.createElement('div');
    hint.className = 'fp-popup-hint';
    hint.textContent = '点击标记进入行程 →';
    root.append(hint);
    return root;
  }, []);

  const cityPopup = useCallback(
    (city: string, trips: TripMapPoint[], color: string): HTMLElement => {
      const visits = trips.length;
      const days = trips.reduce((sum, point) => sum + tripDays(point), 0);
      const budget = trips.reduce((sum, point) => sum + (point.budget ?? 0), 0);
      const { root, meta } = popupShell(city, `${visits} 次到访`, color);
      meta.append(
        metaLine(`累计 ${days} 天`),
        metaLine(`预算合计 ¥${budget.toFixed(0)}`),
        metaLine(
          `最近 ${
            trips
              .map((t) => t.startDate)
              .filter(Boolean)
              .sort()
              .reverse()[0] ?? '—'
          }`,
        ),
      );
      const hint = document.createElement('div');
      hint.className = 'fp-popup-hint';
      hint.textContent = '点击查看该城市足迹 →';
      root.append(hint);
      return root;
    },
    [],
  );

  const showPopupAt = useCallback((content: HTMLElement, lng: number, lat: number, map: Map) => {
    const engine = engineRef.current;
    if (!engine) return;
    popupRef.current?.remove();
    popupRef.current = new engine.Popup({
      offset: 26,
      closeButton: false,
      className: 'fp-popup',
      maxWidth: '260px',
    })
      .setLngLat([lng, lat])
      .setDOMContent(content)
      .addTo(map);
  }, []);

  /** 标记元素：城市节点 / 行程节点共用，city 模式额外显示到访次数 */
  const markerElement = useCallback(
    (label: string, initial: string, color: string, visits?: number) => {
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
    },
    [theme],
  );

  /** 航线坐标：city 模式按首次到访顺序串城市，trip 模式按行程时间顺序 */
  const routeOf = useCallback((): [number, number][] => {
    if (modeRef.current !== 'city') {
      return routeCoordinates(pointsRef.current);
    }
    return cityFootprints(pointsRef.current)
      .slice()
      .sort((a, b) => (a.firstVisit ?? '').localeCompare(b.firstVisit ?? ''))
      .map((c) => [c.lng, c.lat] as [number, number]);
  }, []);

  const draw = useCallback(() => {
    const engine = engineRef.current;
    const map = mapRef.current;
    if (!engine || !map) return [] as TripMapPoint[];

    markersRef.current.forEach((marker) => marker.remove());
    markersRef.current = [];

    const cityMode = modeRef.current === 'city';
    const located = orderedFootprints(pointsRef.current);

    // 航线：先按时间顺序连线，再叠加一层虚线形成「飞行轨迹」质感
    const coordinates = routeOf();
    const source = map.getSource('fp-route') as GeoJSONSource | undefined;
    source?.setData(
      coordinates.length > 1
        ? {
            type: 'FeatureCollection',
            features: [
              { type: 'Feature', properties: {}, geometry: { type: 'LineString', coordinates } },
            ],
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
          new engine.Marker({ element: el, anchor: 'center' })
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
        new engine.Marker({ element: el, anchor: 'center' })
          .setLngLat([point.lng as number, point.lat as number])
          .addTo(map),
      );
    });

    return located;
  }, [cityPopup, markerElement, routeOf, showPopupAt, tripPopup]);

  const fitAll = useCallback((duration = 2200) => {
    const engine = engineRef.current;
    const map = mapRef.current;
    if (!map || !engine) return;
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
    const bounds = new engine.LngLatBounds();
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
      showPopupAt(
        tripPopup(point, footprintColor(index)),
        point.lng as number,
        point.lat as number,
        map,
      );
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

  // 初始化地图：先按需加载地图引擎（~1.1 MB，不在 bundle 里），加载完成后再创建 Map。
  // 主题/底图/初始视角变化会重建地图；卸载或重建时如果加载还没完成，用 cancelled 兜住、不 setState。
  useEffect(() => {
    const container = containerRef.current;
    if (!container) return;

    let cancelled = false;
    let dispose: (() => void) | null = null;
    setEngineError(null);
    setEngineLoading(true);

    loadMaplibre()
      .then((maplibregl) => {
        if (cancelled) return;

        engineRef.current = maplibregl;
        const supportsGlobe =
          typeof (maplibregl.Map.prototype as unknown as { setProjection?: unknown })
            .setProjection === 'function';

        const map = new maplibregl.Map({
          container,
          // 高德底图用栅格样式以保留球体投影；OSM 深色仍优先用免 Key 的矢量样式
          style:
            activeBasemap === 'amap'
              ? buildAmapRasterStyle(theme)
              : theme === 'dark'
                ? DARK_STYLE_URL
                : buildOsmRasterStyle('light'),
          center: CHINA_OVERVIEW,
          zoom: initialZoom,
          pitch: initialPitch,
          bearing: initialBearing,
          maxPitch: 78,
          minZoom: 1,
          attributionControl: false,
          ...(supportsGlobe ? { projection: 'globe' as const } : {}),
        });
        mapRef.current = map;
        // 先把清理函数挂好：后面任何一步抛错（例如 WebGL 初始化失败）也不会漏掉 map.remove()
        dispose = () => {
          stopTour();
          stopSpin();
          popupRef.current?.remove();
          popupRef.current = null;
          markersRef.current = [];
          map.remove();
          mapRef.current = null;
        };

        map.addControl(new maplibregl.AttributionControl({ compact: true }), 'bottom-right');
        setGlobe(supportsGlobe);
        setEngineLoading(false);

        const syncLabels = () => setLabelsOff(map.getZoom() < LABEL_MIN_ZOOM);
        map.on('zoom', syncLabels);
        syncLabels();

        let styleFallbackApplied = false;
        map.on('error', (event: unknown) => {
          const message = String(
            (event as unknown as { error?: { message?: string } })?.error?.message ?? '',
          );
          if (!/style|fetch|load|network|tile/i.test(message)) {
            return;
          }
          // 高德瓦片不可达时整块回退到 OSM，避免首页只看到纸底。
          if (activeBasemap === 'amap' && !styleFallbackApplied) {
            styleFallbackApplied = true;
            console.warn('[footprint] 高德底图加载失败，已回退 OSM 底图');
            setActiveBasemap('osm');
            return;
          }
          // OSM 深色矢量样式不可达时，退回压暗的 OSM 栅格。
          if (activeBasemap !== 'osm' || theme !== 'dark' || styleFallbackApplied) {
            return;
          }
          styleFallbackApplied = true;
          console.warn('[footprint] 深色底图加载失败，已退回压暗的 OSM 栅格底图');
          map.setStyle(buildOsmRasterStyle('dark'));
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
            paint: {
              'line-color': routeColor,
              'line-width': 14,
              'line-blur': 12,
              'line-opacity': 0.26,
            },
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
            map.flyTo({
              center: CHINA_OVERVIEW,
              zoom: supportsGlobe ? 1.9 : 3,
              duration: 2400,
              essential: true,
            });
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
      })
      .catch((error: unknown) => {
        if (cancelled) return;
        setEngineLoading(false);
        setEngineError(error instanceof Error ? error.message : String(error));
      });

    return () => {
      cancelled = true;
      dispose?.();
    };
    // 初始视角只在首次初始化时生效：下面的重建键就是全部触发条件。
    // draw/fitAll/startSpin/stopSpin/stopTour 每次渲染都会重建引用，进依赖数组会把
    // 「数据变化」变成「重建地图」（丢相机与覆盖物），因此只按这四个键重建。
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [theme, activeBasemap, initialPitch, initialBearing, initialZoom, loadAttempt]);

  // 数据或模式变化时重绘
  useEffect(() => {
    const map = mapRef.current;
    if (!map) return;
    if (map.isStyleLoaded()) {
      draw();
    } else {
      map.once('load', () => draw());
    }
    // draw 依赖 geoPoints/mode，后两者已在依赖数组里；显式只按数据键重绘
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [geoPoints, mode]);

  // 外部指定聚焦行程
  useEffect(() => {
    if (focusTripId != null) {
      flyToTrip(focusTripId);
    }
    // 只在外部信号变化时飞行一次；flyToTrip 会随主题/数据重建，进依赖会重复飞行
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [focusTripId]);

  // 回放信号
  useEffect(() => {
    if (playSignal > 0) {
      startTour();
    }
    // playSignal 是自增信号，只在变化时触发一次回放；startTour 每次渲染都会重建
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [playSignal]);

  return (
    <div
      className={`fp-canvas ${labelsOff ? 'labels-off' : ''} ${theme === 'dark' ? 'fp-theme-dark' : ''}`}
    >
      <div ref={containerRef} className="fp-map" />
      {engineLoading && !engineError && (
        <div
          className="fp-engine-status"
          style={{
            position: 'absolute',
            inset: 0,
            zIndex: 2,
            display: 'flex',
            alignItems: 'center',
            justifyContent: 'center',
            pointerEvents: 'none',
            color: 'var(--ink-3, #666)',
            fontSize: 13,
          }}
        >
          正在加载地图引擎…
        </div>
      )}
      {engineError && (
        <div className="fp-engine-error" role="alert">
          <b>地图暂时不可用</b>
          <span className="fp-engine-hint">足迹列表与行程时间轴仍可正常查看。</span>
          <button
            type="button"
            className="fp-ctl"
            onClick={() => setLoadAttempt((value) => value + 1)}
            aria-label="重试加载地图"
          >
            <ReloadOutlined aria-hidden="true" /> 重试地图
          </button>
        </div>
      )}
      {globe && (
        <div className="fp-globe-hint">
          <GlobalOutlined aria-hidden="true" /> 球体视角
        </div>
      )}
      {showControls && !engineError && (
        <div className="fp-controls">
          <div className="fp-ctl-group">
            <Tooltip title="倾斜透视视角">
              <button
                type="button"
                className={`fp-ctl ${engine === '3d' ? 'active' : ''}`}
                onClick={() => setPitched(true)}
                aria-label="倾斜透视视角"
                aria-pressed={engine === '3d'}
              >
                <DeploymentUnitOutlined aria-hidden="true" /> 3D
              </button>
            </Tooltip>
            <Tooltip title="平面俯视视角">
              <button
                type="button"
                className={`fp-ctl ${engine === '2d' ? 'active' : ''}`}
                onClick={() => setPitched(false)}
                aria-label="平面俯视视角"
                aria-pressed={engine === '2d'}
              >
                <BorderOutlined aria-hidden="true" /> 2D
              </button>
            </Tooltip>
          </div>
          <Tooltip title="回到全部足迹">
            <button
              type="button"
              className="fp-ctl"
              onClick={() => fitAll()}
              aria-label="回到全部足迹"
            >
              <AimOutlined aria-hidden="true" /> 复位
            </button>
          </Tooltip>
          <Tooltip title={spinning ? '停止环绕' : '环绕地球'}>
            <button
              type="button"
              className={`fp-ctl ${spinning ? 'active' : ''}`}
              onClick={() => (spinning ? stopSpin() : startSpin())}
              aria-label={spinning ? '停止环绕地球' : '环绕地球'}
              aria-pressed={spinning}
            >
              <SyncOutlined spin={spinning} aria-hidden="true" /> 环绕
            </button>
          </Tooltip>
          <Tooltip title={touring ? '停止回放' : '按时间顺序回放足迹'}>
            <button
              type="button"
              className={`fp-ctl tour ${touring ? 'active' : ''}`}
              onClick={() => (touring ? stopTour() : startTour())}
              aria-label={touring ? '停止回放足迹' : '回放足迹'}
              aria-pressed={touring}
            >
              {touring ? (
                <>
                  <PauseCircleOutlined aria-hidden="true" /> 回放中 {tourIndex + 1}/{tourTotal}
                </>
              ) : (
                <>
                  <PlayCircleOutlined aria-hidden="true" /> 回放足迹
                </>
              )}
            </button>
          </Tooltip>
        </div>
      )}
    </div>
  );
}
