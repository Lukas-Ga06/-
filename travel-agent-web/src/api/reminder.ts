import { request } from './client';

export type ReminderType = 'SCHEDULE' | 'TRIP';

export interface ReminderItem {
  type: ReminderType;
  refId: number;
  title: string;
  detail: string;
  remindAt: string;
  eventAt: string;
  overdue: boolean;
  minutesUntil: number;
}

export interface ReminderBoard {
  items: ReminderItem[];
  count: number;
  serverTime: string;
}

export const getReminders = () => request<ReminderBoard>({ url: '/reminders' });

export function dismissReminder(item: ReminderItem): Promise<void> {
  const kind = item.type === 'TRIP' ? 'trip' : 'schedule';
  return request<void>({ url: `/reminders/${kind}/${item.refId}/dismiss`, method: 'post' });
}
