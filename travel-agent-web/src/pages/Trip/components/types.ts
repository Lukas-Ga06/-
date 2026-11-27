import type { ItineraryStatus } from '../../../types/trip';

/** 右侧面板：地图与 AI 共享同一块空间，由分段控件切换 */
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
