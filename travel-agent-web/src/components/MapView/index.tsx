import { useEffect, useRef, useState } from 'react';
import { Tooltip } from 'antd';
import {
  AimOutlined,
  BorderOutlined,
  DeploymentUnitOutlined,
  PauseCircleOutlined,
  PlayCircleOutlined,
  ReloadOutlined,
} from '@ant-design/icons';
import type { ItineraryItem, RouteLeg } from '../../types/trip';
import type { AgentFocus } from '../../types/agent';
import type { MapProvider } from './MapProvider';
import { AMapProvider } from './AMapProvider';
import { useThemeStore } from '../../store/themeStore';
import RunStatus from '../RunStatus';
import { knownRunState } from '../../lib/runState';

interface Props {
  items: ItineraryItem[];
  focus: AgentFocus | null;
  focusDay: number | null;
  /** 每日相邻点的转场路线段（FR-07）；缺省时地图退回「按天点位连线」 */
  routeLegs?: RouteLeg[];
  /**
   * 是否处于可见面板（侧栏 keep-alive 用）。缺省视为 true。
   * 传 false 不会卸载地图——只是告诉它「现在被藏起来了」；
   * 重新可见时补一次 resize，保住相机与缩放。
   */
  active?: boolean;
  /** 地图不可用时，给用户一个不依赖地图的出口（切到时间轴） */
  onViewTimeline?: () => void;
  /** 技术排查入口：只在用户主动点击时打开诊断页，不在主界面暴露配置 */
  onOpenDiagnostics?: () => void;
}

type Engine = '3d' | '2d';

/**
 * 地图组件：统一使用高德 JS API，支持 3D/2D、辉光路线与沿行程巡航；
 * Agent Focus 事件驱动自动飞行定位。
 */
export default function MapView({
  items,
  focus,
  focusDay,
  routeLegs,
  active = true,
  onViewTimeline,
  onOpenDiagnostics,
}: Props) {
  const containerRef = useRef<HTMLDivElement | null>(null);
  const providerRef = useRef<MapProvider | null>(null);
  const [engine, setEngine] = useState<Engine>('3d');
  const [touring, setTouring] = useState(false);
  const [mapLoadFailed, setMapLoadFailed] = useState(false);
  const [retryKey, setRetryKey] = useState(0);
  const themeMode = useThemeStore((s) => s.mode);
  /** 地图加载失败是「资源不可用」而不是「没有地点」：统一走 resource→error（failed）这一档 */
  const mapFailedState = knownRunState('resource', 'error');

  // 初始化 / 视角切换。高德 SDK 只加载一次，Provider 负责挂起异步加载期间的渲染请求。
  // 主题不作为依赖：切主题只换底图样式，绝不重建地图（重建会丢相机与覆盖物）。
  useEffect(() => {
    if (!containerRef.current || providerRef.current) return;
    setMapLoadFailed(false);
    const provider: MapProvider = new AMapProvider(
      engine === '3d' ? '3D' : '2D',
      undefined,
      useThemeStore.getState().mode,
    );
    provider.init(containerRef.current, (status) => {
      setMapLoadFailed(status === 'failed');
    });
    providerRef.current = provider;
    return () => {
      provider.destroy();
      providerRef.current = null;
      setTouring(false);
    };
  }, [engine, retryKey]);

  // 主题切换：只让 Provider 换底图样式（SDK 未就绪时它会把请求挂起）。
  useEffect(() => {
    providerRef.current?.setTheme?.(themeMode);
  }, [themeMode]);

  // 侧栏 keep-alive：隐藏期间窗口可能变过尺寸，切回可见时校正一次画布（不重建地图）
  useEffect(() => {
    if (!active) return;
    providerRef.current?.resize?.();
  }, [active]);

  // 数据渲染（Provider 内部按完整行程内容去重；legs 内容变化也要重绘）
  useEffect(() => {
    providerRef.current?.render(items, focusDay, routeLegs);
  }, [items, focusDay, engine, routeLegs]);

  // 焦点、行程数据或视角变化都会打断正在跑的巡航。
  // 巡航状态在渲染期收敛（React 官方「prop 变化时调整 state」写法，避免多一轮提交）；
  // effect 里只停 Provider 的计时器——那是外部系统副作用，不写 React state。
  const [tourSeed, setTourSeed] = useState({
    focus,
    focusDay,
    items,
    engine,
  });
  if (
    tourSeed.focus !== focus ||
    tourSeed.focusDay !== focusDay ||
    tourSeed.items !== items ||
    tourSeed.engine !== engine
  ) {
    setTourSeed({ focus, focusDay, items, engine });
    setTouring(false);
  }
  useEffect(() => {
    providerRef.current?.stopTour?.();
  }, [focus, focusDay, items, engine]);

  // Agent Focus：切换 Day 后飞行定位
  useEffect(() => {
    const provider = providerRef.current;
    if (!focus || !provider) return;
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
        <div className="map-unavailable" role="alert" data-testid="map-unavailable">
          <RunStatus
            status={mapFailedState}
            text="地图暂时不可用"
            detail="行程时间轴与 AI 助手仍可正常使用。"
            actions={[
              {
                label: '重试地图',
                icon: <ReloadOutlined aria-hidden="true" />,
                onClick: () => {
                  setMapLoadFailed(false);
                  setRetryKey((v) => v + 1);
                },
                testId: 'map-retry',
              },
              ...(onViewTimeline
                ? [{ label: '查看时间轴', onClick: onViewTimeline, testId: 'map-view-timeline' }]
                : []),
              ...(onOpenDiagnostics
                ? [
                    {
                      label: '打开诊断',
                      onClick: onOpenDiagnostics,
                      testId: 'map-open-diagnostics',
                    },
                  ]
                : []),
            ]}
          />
        </div>
      )}
      {!mapLoadFailed && (
        <div className="map-controls" role="toolbar" aria-label="地图控制">
          <div className="map-ctl-group">
            <Tooltip title="3D 倾斜透视地图">
              <button
                type="button"
                className={`map-ctl-btn ${engine === '3d' ? 'active' : ''}`}
                onClick={() => setEngine('3d')}
                aria-label="3D 倾斜透视地图"
                aria-pressed={engine === '3d'}
              >
                <DeploymentUnitOutlined aria-hidden="true" /> 3D
              </button>
            </Tooltip>
            <Tooltip title="2D 平面地图">
              <button
                type="button"
                className={`map-ctl-btn ${engine === '2d' ? 'active' : ''}`}
                onClick={() => setEngine('2d')}
                aria-label="2D 平面地图"
                aria-pressed={engine === '2d'}
              >
                <BorderOutlined aria-hidden="true" /> 2D
              </button>
            </Tooltip>
          </div>
          {engine === '3d' && (
            <>
              <Tooltip title={touring ? '停止巡航' : '沿当天路线自动巡航'}>
                <button
                  type="button"
                  className={`map-ctl-btn tour ${touring ? 'active' : ''}`}
                  onClick={toggleTour}
                  aria-label={touring ? '停止巡航' : '沿路线巡航'}
                  aria-pressed={touring}
                >
                  {touring ? (
                    <>
                      <PauseCircleOutlined aria-hidden="true" /> 停止巡航
                    </>
                  ) : (
                    <>
                      <PlayCircleOutlined aria-hidden="true" /> 沿路线巡航
                    </>
                  )}
                </button>
              </Tooltip>
              <Tooltip title="切换为俯视角度">
                <button
                  type="button"
                  className="map-ctl-btn"
                  onClick={() => setPitched(false)}
                  aria-label="切换为俯视角度"
                >
                  <AimOutlined aria-hidden="true" /> 俯视
                </button>
              </Tooltip>
            </>
          )}
        </div>
      )}
    </>
  );
}
