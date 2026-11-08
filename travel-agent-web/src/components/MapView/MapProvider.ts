import type { ItineraryItem } from '../../types/trip';

/**
 * 地图抽象层：支持替换不同地图服务商与渲染引擎
 * （MapLibre 3D / Leaflet 2D / 高德等），只需实现该接口。
 */
export interface MapProvider {
  /**
   * 初始化地图。
   * onBaseMapStatus：底图瓦片加载结果的回调——收到 'ok' 后不再回调；
   * 'failed' 表示多次尝试均失败（弱网/外网受限），外层可据此提示降级。
   */
  init(container: HTMLDivElement, onBaseMapStatus?: (status: 'ok' | 'failed') => void): void;
  /** 渲染行程点位与路线；focusDay 为 null 时展示全部天 */
  render(items: ItineraryItem[], focusDay: number | null): void;
  flyTo(lat: number, lng: number, zoom?: number): void;
  destroy(): void;

  /** 可选扩展：3D 沿行程自动巡航（相机依次飞越当天各点） */
  startTour?(items: ItineraryItem[]): void;
  stopTour?(): void;
  /** 可选扩展：倾斜 3D 视角开关 */
  setPitched?(pitched: boolean): void;
}
