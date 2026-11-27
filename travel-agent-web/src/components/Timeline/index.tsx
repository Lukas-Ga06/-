import { Fragment, useEffect, useMemo } from 'react';
import { Button, Empty, Segmented } from 'antd';
import { CarOutlined } from '@ant-design/icons';
import dayjs from 'dayjs';
import type { ItineraryItem, ItineraryStatus, RouteLeg, Trip } from '../../types/trip';
import type { AgentFocus } from '../../types/agent';
import ItineraryCard from '../ItineraryCard';
import { useUIStore } from '../../store/uiStore';
import { pickNextItem, scrollToItem } from '../../lib/tripFocus';
import { formatClock, routeLegText } from './timelineText';

interface Props {
  trip: Trip | null;
  items: ItineraryItem[];
  focus: AgentFocus | null;
  /** 每日相邻点的转场路线段（FR-07）；缺省/为空时时间线行为与原来完全一致 */
  routeLegs?: RouteLeg[];
  /** P1：行程项状态操作；缺省时卡片不显示操作按钮（既有用法零变化） */
  onItemStatusChange?: (itemId: number, status: ItineraryStatus) => void;
  /** 正在提交状态变更的条目 id（按钮禁用 + 处理中提示） */
  statusBusyId?: number | null;
  /** 离线记录、尚未被服务端确认的条目 id（§7.3 第 4 条：显示「待同步」而不是「已完成」） */
  pendingSyncIds?: Set<number>;
}

/**
 * 转场行：纯展示，不参与元素选中、不可点击。
 * source=amap 显示「实测」，其余一律显示「估算」——估算不得被显示成实时数据。
 */
function RouteLegRow({ leg }: { leg: RouteLeg }) {
  const measured = leg.source === 'amap';
  return (
    <div className="t-leg" data-testid="route-leg" data-source={leg.source}>
      <div className="t-leg-rail" aria-hidden />
      <div className="t-leg-line" aria-hidden />
      <div className="t-leg-body">
        <span className="t-leg-icon" aria-hidden>
          <CarOutlined />
        </span>
        <span className="t-leg-text">{routeLegText(leg)}</span>
        <span
          className={`mini-tag ${measured ? 'leg-amap' : 'leg-estimated'}`}
          title={
            measured
              ? '路线来源：amap（高德实测，可复核）'
              : '路线来源：estimated（按距离估算，可能受路况影响）'
          }
        >
          {measured ? '实测 · amap' : '估算 · estimated'}
        </span>
      </div>
    </div>
  );
}

/**
 * 时间轴：Day 分组切换，点击选中元素（多模态上下文），响应 Agent Focus 自动滚动
 */
export default function Timeline({
  trip,
  items,
  focus,
  routeLegs,
  onItemStatusChange,
  statusBusyId,
  pendingSyncIds,
}: Props) {
  const selectedDay = useUIStore((s) => s.selectedDay);
  const setSelectedDay = useUIStore((s) => s.setSelectedDay);
  const selectedElement = useUIStore((s) => s.selectedElement);
  const setSelectedElement = useUIStore((s) => s.setSelectedElement);

  const days = useMemo(() => {
    const set = new Set(items.map((i) => i.dayIndex));
    return Array.from(set).sort((a, b) => a - b);
  }, [items]);

  const dayItems = useMemo(
    () =>
      items
        .filter((i) => i.dayIndex === selectedDay)
        .sort((a, b) => (a.startTime ?? '').localeCompare(b.startTime ?? '')),
    [items, selectedDay],
  );

  /** (fromItemId -> toItemId) → 当天的转场段；重复时取 sequence 更小的一段 */
  const legLookup = useMemo(() => {
    const map = new Map<string, RouteLeg>();
    for (const leg of routeLegs ?? []) {
      if (leg.dayIndex !== selectedDay) continue;
      if (leg.fromItemId == null || leg.toItemId == null) continue;
      const key = `${leg.fromItemId}->${leg.toItemId}`;
      const prev = map.get(key);
      if (!prev || (leg.sequence ?? 0) < (prev.sequence ?? 0)) map.set(key, leg);
    }
    return map;
  }, [routeLegs, selectedDay]);

  useEffect(() => {
    if (!focus || focus.type !== 'itinerary' || focus.day == null) return;
    setSelectedDay(focus.day);
    setTimeout(() => {
      const el = focus.itemId ? document.getElementById(`ti-${focus.itemId}`) : null;
      el?.scrollIntoView({ behavior: 'smooth', block: 'center' });
    }, 200);
  }, [focus, setSelectedDay]);

  const nextItem = useMemo(
    () => pickNextItem(items, trip, days, selectedDay),
    [items, trip, days, selectedDay],
  );

  /** 下一项与它前一项之间的转场段（距离 / 交通时间），没有就整行不显示 */
  const nextItemLeg = useMemo(() => {
    if (!nextItem) return null;
    const sameDay = items
      .filter((i) => i.dayIndex === nextItem.dayIndex)
      .sort((a, b) => (a.startTime ?? '').localeCompare(b.startTime ?? ''));
    const index = sameDay.findIndex((i) => i.id === nextItem.id);
    if (index <= 0) return null;
    const prev = sameDay[index - 1];
    return (
      (routeLegs ?? []).find(
        (leg) =>
          leg.dayIndex === nextItem.dayIndex &&
          leg.fromItemId === prev.id &&
          leg.toItemId === nextItem.id,
      ) ?? null
    );
  }, [nextItem, items, routeLegs]);

  /** 下一项的开始时间（ISO 与裸 HH:mm 都吃，解析不了就不显示） */
  const nextClock = formatClock(nextItem?.startTime);

  /** 点「下一项」定位到它所在的那一天并滚到卡片（与页面「完成后滚到下一项」同一实现） */
  const locate = (item: ItineraryItem) => {
    setSelectedDay(item.dayIndex);
    scrollToItem(item.id as number, 150);
  };

  if (items.length === 0) {
    return (
      <div className="tl-scroll" style={{ display: 'grid', placeItems: 'center' }}>
        <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="暂无行程安排，和 AI 助手聊聊吧" />
      </div>
    );
  }

  return (
    <>
      {/* 行中第一信息：下一项 + 它的主动作（P1-04）。手机首屏先看到这个，而不是地图 */}
      {nextItem && (
        <div className="next-up" data-testid="next-up">
          <button type="button" className="nu-main" onClick={() => locate(nextItem)}>
            <span className="nu-label">
              下一项安排 · Day {nextItem.dayIndex}
              {nextClock ? ` · ${nextClock}` : ''}
            </span>
            <span className="nu-title">{nextItem.title}</span>
            {nextItemLeg && (
              <span className="nu-sub">
                {routeLegText(nextItemLeg)} · {nextItemLeg.source === 'amap' ? '实测' : '估算'}
              </span>
            )}
          </button>
          {onItemStatusChange && nextItem.id != null && (
            <Button
              size="small"
              type="primary"
              loading={statusBusyId === nextItem.id}
              onClick={() =>
                onItemStatusChange(
                  nextItem.id as number,
                  nextItem.status === 'IN_PROGRESS' ? 'COMPLETED' : 'IN_PROGRESS',
                )
              }
            >
              {nextItem.status === 'IN_PROGRESS' ? '完成' : '开始'}
            </Button>
          )}
        </div>
      )}
      <div className="day-tabs">
        <Segmented
          block
          value={String(selectedDay)}
          onChange={(v) => setSelectedDay(Number(v))}
          options={days.map((d) => ({
            value: String(d),
            label: `Day ${d}${
              trip?.startDate
                ? ` · ${dayjs(trip.startDate)
                    .add(d - 1, 'day')
                    .format('MM月DD日')}`
                : ''
            }`,
          }))}
        />
      </div>
      <div className="tl-scroll">
        {dayItems.map((item, idx) => {
          const prev = idx > 0 ? dayItems[idx - 1] : null;
          // 只按「同一天相邻两条安排」的 id 配对；匹配不到就不渲染该段
          const leg =
            prev && prev.id != null && item.id != null
              ? (legLookup.get(`${prev.id}->${item.id}`) ?? null)
              : null;
          return (
            <Fragment key={item.id ?? item.title}>
              {leg && <RouteLegRow leg={leg} />}
              <div
                id={item.id ? `ti-${item.id}` : undefined}
                className={`t-row ${selectedElement?.id === item.id ? 'selected' : ''}`}
              >
                <div className="t-rail">
                  <div className="t-time">{formatClock(item.startTime) || '--:--'}</div>
                  <div className="t-end">{formatClock(item.endTime)}</div>
                </div>
                <div className="t-line">
                  <span className={`t-dot ${item.itemType}`} />
                </div>
                <ItineraryCard
                  item={item}
                  selected={selectedElement?.id === item.id}
                  onClick={() =>
                    setSelectedElement(
                      item.id ? { type: item.itemType, id: item.id, title: item.title } : null,
                    )
                  }
                  onStatusChange={
                    item.id != null && onItemStatusChange
                      ? (status) => onItemStatusChange(item.id as number, status)
                      : undefined
                  }
                  statusBusy={item.id != null && statusBusyId === item.id}
                  pendingSync={item.id != null && pendingSyncIds?.has(item.id) === true}
                />
              </div>
            </Fragment>
          );
        })}
      </div>
    </>
  );
}
