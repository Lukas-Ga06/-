import { describe, expect, it } from 'vitest';
import { remindersToNotify, reminderKey } from './reminderNotify';
import type { ReminderItem } from '../api/reminder';

function item(overrides: Partial<ReminderItem> = {}): ReminderItem {
  return {
    type: 'SCHEDULE',
    refId: 1,
    title: '组会',
    detail: '2026-10-20 10:20',
    remindAt: '2026-10-20T09:50:00',
    eventAt: '2026-10-20T10:20:00',
    overdue: false,
    minutesUntil: 20,
    ...overrides,
  };
}

describe('remindersToNotify', () => {
  it('已授权 + 页面在后台 → 需要通知', () => {
    expect(remindersToNotify([item()], new Set(), 'granted', 'hidden')).toHaveLength(1);
  });

  it('页面在前台时不弹通知（铃铛已经能看到）', () => {
    expect(remindersToNotify([item()], new Set(), 'granted', 'visible')).toHaveLength(0);
  });

  it('未授权或浏览器不支持时不弹', () => {
    expect(remindersToNotify([item()], new Set(), 'default', 'hidden')).toHaveLength(0);
    expect(remindersToNotify([item()], new Set(), 'denied', 'hidden')).toHaveLength(0);
    expect(remindersToNotify([item()], new Set(), 'unsupported', 'hidden')).toHaveLength(0);
  });

  it('同一会话内同一条提醒只通知一次', () => {
    const first = item();
    expect(remindersToNotify([first], new Set([reminderKey(first)]), 'granted', 'hidden')).toHaveLength(0);
  });

  it('key 区分类型与 id', () => {
    expect(reminderKey(item())).toBe('SCHEDULE-1');
    expect(reminderKey(item({ type: 'TRIP', refId: 2 }))).toBe('TRIP-2');
  });
});
