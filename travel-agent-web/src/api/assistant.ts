import { request } from './client';

// ---------- 日程（旅行域，保留） ----------
export interface ScheduleItem {
  id: number;
  title: string;
  date: string;
  time?: string | null;
  note?: string | null;
  done: boolean;
  /** NONE / DAILY / WEEKLY / MONTHLY */
  recurrence?: string | null;
  repeatUntil?: string | null;
  nextOccurrence?: string | null;
}

export const listSchedule = () => request<ScheduleItem[]>({ url: '/schedule' });
export const addSchedule = (data: {
  title: string;
  date: string;
  time?: string;
  note?: string;
  recurrence?: string;
  repeatUntil?: string;
}) => request<ScheduleItem>({ url: '/schedule', method: 'post', data });
export const toggleSchedule = (id: number) =>
  request<ScheduleItem>({ url: `/schedule/${id}/toggle`, method: 'post' });
export const deleteSchedule = (id: number) =>
  request<void>({ url: `/schedule/${id}`, method: 'delete' });

// ---------- 系统运行能力（设置页/运行自检共用） ----------
export interface SystemStatus {
  llmConfigured: boolean;
  /**
   * 最近一次真实 LLM 调用是否成功（V2.2 P5）。
   * 旧后端没有该字段：调用方必须按 undefined 处理，不能当成「已验证」。
   */
  llmVerified?: boolean;
  llmModel?: string | null;
  llmHint: string;
  amapConfigured: boolean;
  geoProvider: string;
  geoHint: string;
  timezone: string;
  /** 现行路线口径：amap | estimated（V2.2 FR-05） */
  routeProvider?: string;
  /** 是否配置了路线 Provider（后端 routeConfigured；旧后端缺省时回退 amapConfigured） */
  routeConfigured?: boolean;
  /** 最近一次真实路径调用是否成功（V2.2 P2）；旧后端缺省按 undefined 处理 */
  amapVerified?: boolean;
  /** 最近一次真实路径调用失败原因（含 infocode，不含 Key） */
  routeLastError?: string | null;
  routeHint?: string;
  /** 每日 LLM 配额：仅在配置了上限（>0）时返回 */
  llmQuotaUsed?: number;
  llmQuotaLimit?: number;
}

export const systemStatus = () => request<SystemStatus>({ url: '/system/status' });
