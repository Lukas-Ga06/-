import { useEffect, useRef, useState } from 'react';
import type { ItineraryItem } from '../../types/trip';
import type { AgentFocus } from '../../types/agent';
import type { MapProvider } from './MapProvider';
import { LeafletMapProvider } from './LeafletMapProvider';
import { MapLibre3DProvider } from './MapLibre3DProvider';

interface Props {
  items: ItineraryItem[];
  focus: AgentFocus | null;
  focusDay: number | null;
}

type Engine = '3d' | '2d';

/**
 * 地图组件：默认 MapLibre 3D（倾斜透视 + 辉光路线 + 沿行程巡航），
 * 可一键切回 Leaflet 2D；Agent Focus 事件驱动自动飞行定位。
 */
export default function MapView({ items, focus, focusDay }: Props) {
  const containerRef = useRef<HTMLDivElement | null>(null);
  const providerRef = useRef<MapProvider | null>(null);
  const renderedKeyRef = useRef('');
  const [engine, setEngine] = useState<Engine>('3d');
  const [touring, setTouring] = useState(false);
  /** 底图瓦片加载失败（弱网/外网受限）：标记与路线仍可用，给出降级提示 */
  const [tileFailed, setTileFailed] = useState(false);

  // 初始化 / 引擎切换
  useEffect(() => {
    if (!containerRef.current || providerRef.current) return;
    setTileFailed(false);
    const provider: MapProvider =
      engine === '3d' ? new MapLibre3DProvider() : new LeafletMapProvider();
    provider.init(containerRef.current, (status) => {
      if (status === 'failed') setTileFailed(true);
    });
    providerRef.current = provider;
    renderedKeyRef.current = '';
    return () => {
      provider.destroy();
      providerRef.current = null;
      setTouring(false);
    };
  }, [engine]);

  // 数据渲染（MapLibre 首帧 load 后才注册图层，Provider 内部已做挂起处理）
  useEffect(() => {
    const provider = providerRef.current;
    if (!provider) return;
    const key = `${items.map((i) => `${i.id ?? i.title}@${i.dayIndex}`).join('|')}#${focusDay ?? ''}`;
    if (renderedKeyRef.current === key) return;
    renderedKeyRef.current = key;
    provider.render(items, focusDay);
  }, [items, focusDay, engine]);

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
  }, [focus, items]);

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
      provider.startTour(items.filter((i) => i.dayIndex === focusDay));
      setTouring(true);
    }
  };

  const setPitched = (pitched: boolean) => {
    providerRef.current?.setPitched?.(pitched);
  };

  return (
    <>
      <div ref={containerRef} style={{ width: '100%', height: '100%' }} />
      {tileFailed && (
        <div className="map-tile-hint" role="status">
          ⚠️ 底图瓦片加载失败或不稳定，地图可能显示不全
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
