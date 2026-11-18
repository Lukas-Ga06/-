import { Fragment, useEffect, useMemo } from 'react';
import { Empty, Segmented } from 'antd';
import dayjs from 'dayjs';
import type { ItineraryItem, RouteLeg, Trip } from '../../types/trip';
import type { AgentFocus } from '../../types/agent';
import ItineraryCard from '../ItineraryCard';
import { useUIStore } from '../../store/uiStore';

interface Props {
  trip: Trip | null;
  items: ItineraryItem[];
  focus: AgentFocus | null;
  /** 每日相邻点的转场路线段（FR-07）；缺省/为空时时间线行为与原来完全一致 */
  routeLegs?: RouteLeg[];
}

/** 距离保留一位小数：4.20 与 4.2 显示一致，整数不带多余的 .0 */
function formatKm(km: number): string {
  const rounded = Math.round(km * 10) / 10;
  return Number.isInteger(rounded) ? String(rounded) : rounded.toFixed(1);
}

/**
 * 转场行单行文案：`4.2 km · 驾车 12 分钟 / 步行 56 分钟 · 约 ¥22`。
 * 时长为 0/缺失的那一段不显示；taxiCost 恒为估算，保留「约」字，不显示成实时报价。
 */
export function routeLegText(leg: RouteLeg): string {
  const parts: string[] = [];
  if (leg.distanceKm != null && leg.distanceKm > 0) parts.push(`${formatKm(leg.distanceKm)} km`);
  const durations: string[] = [];
  if (leg.driveMinutes != null && leg.driveMinutes > 0) durations.push(`驾车 ${leg.driveMinutes} 分钟`);
  if (leg.walkMinutes != null && leg.walkMinutes > 0) durations.push(`步行 ${leg.walkMinutes} 分钟`);
  if (durations.length > 0) parts.push(durations.join(' / '));
  if (leg.taxiCost != null && leg.taxiCost > 0) parts.push(`约 ¥${Math.round(leg.taxiCost)}`);
  return parts.join(' · ');
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
          🚕
        </span>
        <span className="t-leg-text">{routeLegText(leg)}</span>
        <span className={`mini-tag ${measured ? 'leg-amap' : 'leg-estimated'}`}>
          {measured ? '实测' : '估算'}
        </span>
      </div>
    </div>
  );
}

/** 时间轴：Day 分组切换，点击选中元素（多模态上下文），响应 Agent Focus 自动滚动 */
export default function Timeline({ trip, items, focus, routeLegs }: Props) {
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
  }, [focus]);

  if (items.length === 0) {
    return (
      <div className="tl-scroll" style={{ display: 'grid', placeItems: 'center' }}>
        <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="暂无行程安排，和 AI 助手聊聊吧" />
      </div>
    );
  }

  return (
    <>
      <div className="day-tabs">
        <Segmented
          block
          value={String(selectedDay)}
          onChange={(v) => setSelectedDay(Number(v))}
          options={days.map((d) => ({
            value: String(d),
            label: `Day ${d}${trip?.startDate ? ` · ${dayjs(trip.startDate).add(d - 1, 'day').format('MM月DD日')}` : ''}`,
          }))}
        />
      </div>
      <div className="tl-scroll">
        {dayItems.map((item, idx) => {
          const prev = idx > 0 ? dayItems[idx - 1] : null;
          // 只按「同一天相邻两条安排」的 id 配对；匹配不到就不渲染该段
          const leg =
            prev && prev.id != null && item.id != null
              ? legLookup.get(`${prev.id}->${item.id}`) ?? null
              : null;
          return (
            <Fragment key={item.id ?? item.title}>
              {leg && <RouteLegRow leg={leg} />}
              <div
                id={item.id ? `ti-${item.id}` : undefined}
                className={`t-row ${selectedElement?.id === item.id ? 'selected' : ''}`}
              >
                <div className="t-rail">
                  <div className="t-time">
                    {item.startTime ? dayjs(item.startTime).format('HH:mm') : '--:--'}
                  </div>
                  <div className="t-end">
                    {item.endTime ? dayjs(item.endTime).format('HH:mm') : ''}
                  </div>
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
                />
              </div>
            </Fragment>
          );
        })}
      </div>
    </>
  );
}
