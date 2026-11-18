import type { ItineraryItem, RouteLeg } from '../../types/trip';

/**
 * 地图抽象层：行程地图当前由高德 JS API 实现。
 * 若需替换地图服务商，只需实现该接口。
 *
 * 坐标契约：所有实现接收并回传 **WGS-84**（与 OSM/MapLibre/GPS 一致）；
 * 服务商自己的坐标系（如高德 GCJ-02）在实现内部转换，调用方无需感知。
 */
export interface MapProvider {
  /**
   * 初始化地图。
   * onBaseMapStatus：底图瓦片加载结果的回调——收到 'ok' 后不再回调；
   * 'failed' 表示多次尝试均失败（弱网/外网受限），外层可据此提示降级。
   */
  init(container: HTMLDivElement, onBaseMapStatus?: (status: 'ok' | 'failed') => void): void;
  /** 渲染行程点位与路线；focusDay 为 null 时展示全部天 */
  render(items: ItineraryItem[], focusDay: number | null, routeLegs?: RouteLeg[]): void;
  flyTo(lat: number, lng: number, zoom?: number): void;
  destroy(): void;

  /** 可选扩展：3D 沿行程自动巡航（相机依次飞越当天各点） */
  startTour?(items: ItineraryItem[]): void;
  stopTour?(): void;
  /** 可选扩展：倾斜 3D 视角开关 */
  setPitched?(pitched: boolean): void;
}
