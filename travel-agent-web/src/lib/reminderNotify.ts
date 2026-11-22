import type { ReminderItem } from '../api/reminder';

const NOTIFIED_KEY = 'travel-agent-notified-reminders';

/** 同一条提醒的唯一标识（类型 + 业务 id） */
export function reminderKey(item: ReminderItem): string {
  return `${item.type}-${item.refId}`;
}

/**
 * 挑出需要发系统通知的提醒：
 * - 必须已授权；
 * - 只在页面处于后台时发（前台已有铃铛，不必再弹）；
 * - 本次会话内同一条提醒只通知一次。
 */
export function remindersToNotify(
  items: ReminderItem[],
  notified: Set<string>,
  permission: NotificationPermission | 'unsupported',
  visibility: DocumentVisibilityState,
): ReminderItem[] {
  if (permission !== 'granted' || visibility !== 'hidden') {
    return [];
  }
  return items.filter((item) => !notified.has(reminderKey(item)));
}

/** 本次浏览器会话内已通知过的提醒（sessionStorage，刷新后仍去重） */
export function loadNotified(): Set<string> {
  try {
    const raw = globalThis.sessionStorage?.getItem(NOTIFIED_KEY);
    return new Set<string>(raw ? (JSON.parse(raw) as string[]) : []);
  } catch {
    return new Set<string>();
  }
}

export function saveNotified(notified: Set<string>): void {
  try {
    globalThis.sessionStorage?.setItem(NOTIFIED_KEY, JSON.stringify(Array.from(notified)));
  } catch {
    // 隐私模式下不可写时忽略：只是本次会话会重复通知
  }
}
