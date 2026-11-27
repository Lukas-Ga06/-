export type AMapViewMode = '2D' | '3D';
export type AMapLngLat = [number, number];

/** 仅声明本项目使用到的高德 JS API，避免把完整 SDK 类型引入首屏。 */
export interface AMapOverlay {
  readonly __amapOverlay?: unique symbol;
}

export interface AMapMap {
  on(event: string, handler: (event?: unknown) => void): void;
  add(overlays: AMapOverlay | AMapOverlay[]): void;
  remove(overlays: AMapOverlay | AMapOverlay[]): void;
  addControl(control: object): void;
  setFitView(
    overlays?: AMapOverlay[],
    immediately?: boolean,
    avoid?: [number, number, number, number],
    maxZoom?: number,
  ): void;
  setZoomAndCenter(
    zoom: number,
    center: AMapLngLat,
    immediately?: boolean,
    duration?: number,
  ): void;
  setPitch(pitch: number): void;
  setRotation(rotation: number): void;
  getRotation(): number;
  /** 以下方法只在 POC / 高级视角控制里使用，旧版本 SDK 可能没有 */
  getZoom?(): number;
  setZoom?(zoom: number): void;
  setCenter?(center: AMapLngLat): void;
  /** 容器尺寸变化后重算画布；旧版本 SDK 可能没有 */
  resize?(): void;
  setMapStyle?(style: string): void;
  destroy(): void;
}

export interface AMapNamespace {
  Map: new (
    container: HTMLDivElement,
    options: {
      viewMode: AMapViewMode;
      zoom: number;
      zooms?: [number, number];
      center: AMapLngLat;
      pitch?: number;
      rotation?: number;
      mapStyle?: string;
      showLabel?: boolean;
      showBuildingBlock?: boolean;
      buildingAnimation?: boolean;
      /** 世界地图：需要在高德控制台开通「世界地图」权限，否则海外区域可能空白 */
      showOversea?: boolean;
      resizeEnable?: boolean;
    },
  ) => AMapMap;
  Marker: new (options: {
    position: AMapLngLat;
    content: HTMLElement;
    anchor?: 'center';
    title?: string;
    zIndex?: number;
  }) => AMapOverlay;
  Polyline: new (options: {
    path: AMapLngLat[];
    strokeColor: string;
    strokeWeight: number;
    strokeOpacity: number;
    strokeStyle?: 'solid' | 'dashed';
    strokeDasharray?: number[];
    lineJoin?: 'round';
    lineCap?: 'round';
    zIndex?: number;
    bubble?: boolean;
  }) => AMapOverlay;
  Scale?: new () => object;
  ToolBar?: new () => object;
  ControlBar?: new (options?: object) => object;
}

declare global {
  interface Window {
    AMap?: AMapNamespace;
    _AMapSecurityConfig?: {
      securityJsCode: string;
    };
  }
}

export type AMapLoader = () => Promise<AMapNamespace>;

export const AMAP_CONFIG_HINT =
  '请在 travel-agent-web/.env.local 配置 VITE_AMAP_KEY 和 VITE_AMAP_SECURITY_CODE';

const SCRIPT_ID = 'travel-agent-amap-js-api';
const LOAD_TIMEOUT_MS = 15_000;
const PLUGINS = ['AMap.Scale', 'AMap.ToolBar', 'AMap.ControlBar'];

let pendingLoad: Promise<AMapNamespace> | null = null;

/**
 * 按需加载高德 JS API 2.0。脚本只创建一次，2D/3D 引擎切换共享同一个 SDK 实例。
 * Key 使用「Web 端(JS API)」类型，与后端 Web 服务的 AMAP_KEY 不通用。
 */
export const loadAMap: AMapLoader = () => {
  if (typeof window === 'undefined' || typeof document === 'undefined') {
    return Promise.reject(new Error('高德 JS API 只能在浏览器环境中加载'));
  }

  const key = import.meta.env.VITE_AMAP_KEY?.trim();
  const securityJsCode = import.meta.env.VITE_AMAP_SECURITY_CODE?.trim() ?? '';
  // 页面里已经有 SDK（宿主预加载 / 本页先前已加载）时直接复用，不再重复下载，也不要求 Key；
  // 只有需要从 CDN 拉取时才必须有 Key。
  if (window.AMap) return Promise.resolve(window.AMap);
  if (!key) {
    return Promise.reject(new Error(`缺少高德 Web 端(JS API) Key，${AMAP_CONFIG_HINT}`));
  }
  if (pendingLoad) return pendingLoad;

  // 必须在高德脚本执行前设置安全密钥。
  window._AMapSecurityConfig = { securityJsCode };

  pendingLoad = new Promise<AMapNamespace>((resolve, reject) => {
    // HMR 后模块内的 pendingLoad 会重置，但旧 script 可能仍在；先清理再按当前配置加载。
    document.getElementById(SCRIPT_ID)?.remove();
    const script = document.createElement('script');
    script.id = SCRIPT_ID;
    script.async = true;
    const query = new URLSearchParams({
      v: '2.0',
      key,
      plugin: PLUGINS.join(','),
    });
    script.src = `https://webapi.amap.com/maps?${query.toString()}`;

    let settled = false;
    const cleanup = () => {
      window.clearTimeout(timeout);
      script.removeEventListener('load', handleLoad);
      script.removeEventListener('error', handleError);
    };
    const fail = (message: string) => {
      if (settled) return;
      settled = true;
      cleanup();
      script.remove();
      pendingLoad = null;
      reject(new Error(message));
    };
    const handleLoad = () => {
      if (settled) return;
      const amap = window.AMap;
      if (!amap) {
        fail('高德脚本已加载，但 window.AMap 不可用，请检查 Key 与安全密钥');
        return;
      }
      settled = true;
      cleanup();
      resolve(amap);
    };
    const handleError = () => {
      fail(`高德 JS API 加载失败，请检查网络、Key 与安全密钥。${AMAP_CONFIG_HINT}`);
    };
    const timeout = window.setTimeout(() => {
      fail(`高德 JS API 加载超时，请检查网络。${AMAP_CONFIG_HINT}`);
    }, LOAD_TIMEOUT_MS);

    script.addEventListener('load', handleLoad, { once: true });
    script.addEventListener('error', handleError, { once: true });
    document.head.appendChild(script);
  });

  return pendingLoad;
};
