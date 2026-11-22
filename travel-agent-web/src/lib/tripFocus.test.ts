// @vitest-environment happy-dom
import { afterEach, describe, expect, it, vi } from 'vitest';
import dayjs from 'dayjs';
import type { ItineraryItem, Trip } from '../types/trip';
import {
  daysOfItems,
  nextFocusAfter,
  pickNextItem,
  resolveTodayDay,
  scrollToItem,
} from './tripFocus';

function trip(patch: Partial<Trip> = {}): Trip {
  return {
    id: 1,
    name: '上海 2 日游',
    destination: '上海',
    startDate: dayjs().format('YYYY-MM-DD'),
    endDate: dayjs().add(1, 'day').format('YYYY-MM-DD'),
    budget: 5000,
    status: 'CONFIRMED',
    phase: 'ONGOING',
    createdAt: '2026-11-01',
    ...patch,
  };
}

function item(patch: Partial<ItineraryItem> = {}): ItineraryItem {
  return {
    id: 1,
    dayIndex: 1,
    itemType: 'attraction',
    title: '外滩',
    startTime: '09:00',
    endTime: '10:00',
    latitude: 31.23,
    longitude: 121.47,
    cost: null,
    ...patch,
  };
}

afterEach(() => {
  vi.useRealTimers();
  document.body.innerHTML = '';
});

describe('tripFocus · 今天第几天', () => {
  it('今天在范围内时返回对应的 Day 序号', () => {
    expect(resolveTodayDay(trip(), [1, 2], 2)).toBe(1);
  });

  it('今天不在范围内时退回用户当前选中的 Day，不猜', () => {
    const past = trip({
      startDate: dayjs().subtract(10, 'day').format('YYYY-MM-DD'),
      endDate: dayjs().subtract(9, 'day').format('YYYY-MM-DD'),
    });
    expect(resolveTodayDay(past, [1, 2], 2)).toBe(2);
  });

  it('没有开始日期 / 日期无效 / 没有 Day 时都退回选中的 Day', () => {
    expect(resolveTodayDay(trip({ startDate: '' }), [1], 1)).toBe(1);
    expect(resolveTodayDay(trip({ startDate: 'not-a-date' }), [1], 3)).toBe(3);
    expect(resolveTodayDay(trip(), [], 3)).toBe(3);
    expect(resolveTodayDay(null, [1], 4)).toBe(4);
  });
});

describe('tripFocus · 下一项', () => {
  it('进行中的条目优先，哪怕它排在后面', () => {
    const items = [
      item({ id: 1, startTime: '09:00' }),
      item({ id: 2, startTime: '11:00', status: 'IN_PROGRESS' }),
    ];
    expect(pickNextItem(items, trip(), [1], 1)?.id).toBe(2);
  });

  it('没有进行中时取不早于今天的、时间最早的一条', () => {
    const items = [
      item({ id: 1, dayIndex: 1, startTime: '15:00' }),
      item({ id: 2, dayIndex: 1, startTime: '09:00' }),
      item({ id: 3, dayIndex: 2, startTime: '08:00' }),
    ];
    expect(pickNextItem(items, null, [1, 2], 1)?.id).toBe(2);
  });

  it('今天的都做完了才看后面几天', () => {
    const items = [
      item({ id: 1, dayIndex: 1, status: 'COMPLETED' }),
      item({ id: 2, dayIndex: 2, startTime: '10:00' }),
    ];
    expect(pickNextItem(items, null, [1, 2], 1)?.id).toBe(2);
  });

  it('全部完成 / 跳过时返回 null', () => {
    const items = [item({ id: 1, status: 'COMPLETED' }), item({ id: 2, status: 'SKIPPED' })];
    expect(pickNextItem(items, null, [1], 1)).toBeNull();
  });

  it('日期都过去了还有历史遗留的未开始条目时，回落到它', () => {
    const past = trip({
      startDate: dayjs().subtract(10, 'day').format('YYYY-MM-DD'),
      endDate: dayjs().subtract(9, 'day').format('YYYY-MM-DD'),
    });
    const items = [item({ id: 7, dayIndex: 1, status: 'PENDING' })];
    expect(pickNextItem(items, past, [1], 1)?.id).toBe(7);
  });

  it('daysOfItems 去重并升序', () => {
    expect(
      daysOfItems([item({ dayIndex: 2 }), item({ dayIndex: 1 }), item({ dayIndex: 2 })]),
    ).toEqual([1, 2]);
  });
});

describe('tripFocus · 完成一项后该滚到哪', () => {
  it('返回下一项，跳过刚刚变更的那一条', () => {
    const items = [item({ id: 1, status: 'COMPLETED' }), item({ id: 2, startTime: '11:00' })];
    const focus = nextFocusAfter(items, null, 1, 1);
    expect(focus.kind).toBe('move');
    expect(focus.kind === 'move' && focus.item.id).toBe(2);
  });

  it('刚变更的条目如果仍是下一项（例如「开始」后还是进行中）走 stay，不重复滚到它', () => {
    const items = [item({ id: 1, status: 'IN_PROGRESS' }), item({ id: 2, startTime: '11:00' })];
    // 进行中的条目优先，所以「下一项」还是它自己：页面不该滚走，也不该说「没有下一项」
    expect(nextFocusAfter(items, null, 1, 1)).toEqual({ kind: 'stay' });
  });

  it('全部处理完返回 done', () => {
    expect(nextFocusAfter([item({ id: 1, status: 'COMPLETED' })], null, 1, 1)).toEqual({
      kind: 'done',
    });
  });
});

describe('tripFocus · 滚动定位', () => {
  it('延时后滚动到对应卡片', () => {
    vi.useFakeTimers();
    const el = document.createElement('div');
    el.id = 'ti-42';
    const scrollIntoView = vi.fn();
    el.scrollIntoView = scrollIntoView;
    document.body.appendChild(el);

    scrollToItem(42, 100);
    expect(scrollIntoView).not.toHaveBeenCalled();

    vi.advanceTimersByTime(100);
    expect(scrollIntoView).toHaveBeenCalledTimes(1);
  });

  it('元素不存在或环境不支持 scrollIntoView 时静默跳过，不抛错', () => {
    vi.useFakeTimers();
    scrollToItem(999, 0);
    expect(() => vi.advanceTimersByTime(200)).not.toThrow();

    const el = document.createElement('div');
    el.id = 'ti-1';
    // 模拟不支持 scrollIntoView 的测试环境
    (el as unknown as { scrollIntoView?: unknown }).scrollIntoView = undefined;
    document.body.appendChild(el);
    expect(() => scrollToItem(1, 0)).not.toThrow();
  });
});
