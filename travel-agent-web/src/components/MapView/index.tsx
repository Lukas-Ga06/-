import { useEffect, useRef, useState } from 'react';
import type { ItineraryItem, RouteLeg } from '../../types/trip';
import type { AgentFocus } from '../../types/agent';
import type { MapProvider } from './MapProvider';
import { AMapProvider } from './AMapProvider';

interface Props {
  items: ItineraryItem[];
  focus: AgentFocus | null;
  focusDay: number | null;
  /** 每日相邻点的转场路线段（FR-07）；缺省时地图退回「按天点位连线」 */
  routeLegs?: RouteLeg[];
}

type Engine = '3d' | '2d';

/**
 * 地图组件：统一使用高德 JS API，支持 3D/2D、辉光路线与沿行程巡航；
 * Agent Focus 事件驱动自动飞行定位。
 */
export default function MapView({ items, focus, focusDay, routeLegs }: Props) {
  const containerRef = useRef<HTMLDivElement | null>(null);
  const providerRef = useRef<MapProvider | null>(null);
  const [engine, setEngine] = useState<Engine>('3d');
  const [touring, setTouring] = useState(false);
  const [mapLoadFailed, setMapLoadFailed] = useState(false);

  // 初始化 / 视角切换。高德 SDK 只加载一次，Provider 负责挂起异步加载期间的渲染请求。
  useEffect(() => {
    if (!containerRef.current || providerRef.current) return;
    setMapLoadFailed(false);
    const provider: MapProvider = new AMapProvider(engine === '3d' ? '3D' : '2D');
    provider.init(containerRef.current, (status) => {
      setMapLoadFailed(status === 'failed');
    });
    providerRef.current = provider;
    return () => {
      provider.destroy();
      providerRef.current = null;
      setTouring(false);
    };
  }, [engine]);

  // 数据渲染（Provider 内部按完整行程内容去重；legs 内容变化也要重绘）
  useEffect(() => {
    providerRef.current?.render(items, focusDay, routeLegs);
  }, [items, focusDay, engine, routeLegs]);

  // Agent Focus：切换 Day 后飞行定位
  useEffect(() => {
    const provider = providerRef.current;
    if (!focus || !provider) return;
    if (touring) {
      provider.stopTour?.();
      setTouring(false);
    }
    let target: { lat: number; lng: number } | null =
      focus.latitude != null && focus.longitude != null
        ? { lat: focus.latitude, lng: focus.longitude }
        : null;
    if (!target && focus.itemId) {
      const item = items.find((i) => i.id === focus.itemId);
      if (item?.latitude != null && item.longitude != null) {
        target = { lat: item.latitude, lng: item.longitude };
      }
    }
    if (target) provider.flyTo(target.lat, target.lng, 16);
  }, [focus, items, engine]);

  // 切换 Day 时停止巡航
  useEffect(() => {
    if (touring) {
      providerRef.current?.stopTour?.();
      setTouring(false);
    }
  }, [focusDay]);

  useEffect(
    () => () => {
      providerRef.current?.stopTour?.();
    },
    [],
  );

  const toggleTour = () => {
    const provider = providerRef.current;
    if (!provider?.startTour) return;
    if (touring) {
      provider.stopTour?.();
      setTouring(false);
    } else {
      const stops = focusDay == null ? items : items.filter((i) => i.dayIndex === focusDay);
      if (!stops.some((i) => i.latitude != null && i.longitude != null)) return;
      provider.startTour(stops);
      setTouring(true);
    }
  };

  const setPitched = (pitched: boolean) => {
    providerRef.current?.setPitched?.(pitched);
  };

  return (
    <>
      <div ref={containerRef} style={{ width: '100%', height: '100%' }} />
      {mapLoadFailed && (
        <div className="map-tile-hint" role="alert">
          高德地图加载失败，请检查 VITE_AMAP_KEY、VITE_AMAP_SECURITY_CODE 和网络连接
        </div>
      )}
      <div className="map-controls">
        <div className="map-ctl-group">
          <button
            className={`map-ctl-btn ${engine === '3d' ? 'active' : ''}`}
            onClick={() => setEngine('3d')}
            title="3D 倾斜透视地图"
          >
            🏔 3D
          </button>
          <button
            className={`map-ctl-btn ${engine === '2d' ? 'active' : ''}`}
            onClick={() => setEngine('2d')}
            title="2D 平面地图"
          >
            🗺 2D
          </button>
        </div>
        {engine === '3d' && (
          <>
            <button className={`map-ctl-btn tour ${touring ? 'active' : ''}`} onClick={toggleTour}>
              {touring ? '⏹ 停止巡航' : '▶ 沿路线巡航'}
            </button>
            <button
              className="map-ctl-btn"
              onClick={() => setPitched(false)}
              title="切换为俯视角度"
            >
              ⬛ 俯视
            </button>
          </>
        )}
      </div>
    </>
  );
}
