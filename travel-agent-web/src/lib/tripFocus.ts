import dayjs from 'dayjs';
import type { ItineraryItem, Trip } from '../types/trip';

/**
 * 行程「下一项 / 今天第几天 / 滚动定位」的纯逻辑。
 *
 * 这些规则原先长在 Timeline 组件里，但行中执行流程（§7.3 第 3 条）要求
 * 「完成当前项后由页面滚动到下一项」，页面与时间轴必须用同一套判定，
 * 否则页面滚到的和用户看到的「下一项」可能是两个不同条目。
 */

/** 今天对应行程的第几天（1 起）。行程没开始 / 日期无效 / 今天不在范围内时，退回用户当前选中的 Day —— 不猜、不乱跳。 */
export function resolveTodayDay(trip: Trip | null, days: number[], selectedDay: number): number {
  if (!trip?.startDate || days.length === 0) return selectedDay;
  const start = dayjs(trip.startDate).startOf('day');
  if (!start.isValid()) return selectedDay;
  const index = dayjs().startOf('day').diff(start, 'day') + 1;
  return days.includes(index) ? index : selectedDay;
}

/** 行程里出现过的 Day 序号（升序去重） */
export function daysOfItems(items: ItineraryItem[]): number[] {
  const set = new Set(items.map((i) => i.dayIndex));
  return Array.from(set).sort((a, b) => a - b);
}

/**
 * 「下一项」：行中场景的第一信息（P1-04/§7.3）。
 * 进行中的条目优先（用户已经在做它）；否则取不早于今天的最早未开始条目；
 * 都做完则回落到最早的未开始条目（可能是历史遗留），没有则返回 null。
 */
export function pickNextItem(
  items: ItineraryItem[],
  trip: Trip | null,
  days: number[],
  selectedDay: number,
): ItineraryItem | null {
  const actionable = items.filter(
    (i) => !i.status || i.status === 'PENDING' || i.status === 'IN_PROGRESS',
  );
  const inProgress = actionable.find((i) => i.status === 'IN_PROGRESS');
  if (inProgress) return inProgress;
  const byTime = [...actionable].sort(
    (a, b) => a.dayIndex - b.dayIndex || (a.startTime ?? '').localeCompare(b.startTime ?? ''),
  );
  const today = resolveTodayDay(trip, days, selectedDay);
  return byTime.find((i) => i.dayIndex >= today) ?? byTime[0] ?? null;
}

/**
 * 滚动到某个行程项。
 *
 * 延时的原因：切 Day 会触发重渲染，元素要等下一帧才存在；
 * `scrollIntoView` 在测试环境（happy-dom）里可能缺失，缺失时静默跳过，
 * 滚动永远只是增强，不得让状态推进本身失败。
 */
export function scrollToItem(itemId: number, delayMs = 150): void {
  if (typeof document === 'undefined' || typeof window === 'undefined') return;
  const run = () => {
    const el = document.getElementById(`ti-${itemId}`);
    if (el && typeof el.scrollIntoView === 'function') {
      el.scrollIntoView({ behavior: 'smooth', block: 'center' });
    }
  };
  if (delayMs <= 0) {
    run();
    return;
  }
  window.setTimeout(run, delayMs);
}

/**
 * 完成一次状态变更后「该把注意力放到哪」的结果。
 *
 * 三种情况必须分开，否则文案会说谎：
 * - `move`：有明确的下一个条目（换 Day + 滚过去）；
 * - `stay`：刚变更的那一条**仍然**是下一项（点了「开始」，它自己还是进行中的那一条），原地不动；
 * - `done`：没有可执行的条目了。
 */
export type NextFocus = { kind: 'move'; item: ItineraryItem } | { kind: 'stay' } | { kind: 'done' };

/** 状态变更后应该滚到的目标：move=下一个条目 / stay=仍停在当前条目 / done=全部处理完 */
export function nextFocusAfter(
  items: ItineraryItem[],
  trip: Trip | null,
  selectedDay: number,
  justChangedId: number,
): NextFocus {
  const next = pickNextItem(items, trip, daysOfItems(items), selectedDay);
  if (!next) return { kind: 'done' };
  // 「开始」之后它自己还是进行中的那一条：不要滚到同一张卡片，也不要说「没有下一项」
  if (next.id == null || next.id === justChangedId) return { kind: 'stay' };
  return { kind: 'move', item: next };
}
