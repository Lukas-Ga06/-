import type { ItineraryStatus } from '../../../types/trip';

/** 移动端分段视图：默认「行程」，地图与 AI 是辅助视图（不能压在时间轴之前） */
export type MobileView = 'itinerary' | 'map' | 'ai';

/** 桌面端右侧面板：地图与 AI 共享同一块空间，由分段控件切换 */
export type SideView = 'map' | 'ai';

/** 一次状态变更的撤销窗口（§7.3 第 3 条） */
export interface UndoState {
  token: number;
  itemId: number;
  title: string;
  previous: ItineraryStatus;
  next: ItineraryStatus;
  message: string;
  detail: string;
}
