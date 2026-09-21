import { useEffect, useMemo } from 'react';
import { Empty, Segmented } from 'antd';
import dayjs from 'dayjs';
import type { ItineraryItem, Trip } from '../../types/trip';
import type { AgentFocus } from '../../types/agent';
import ItineraryCard from '../ItineraryCard';
import { useUIStore } from '../../store/uiStore';

interface Props {
  trip: Trip | null;
  items: ItineraryItem[];
  focus: AgentFocus | null;
}

/** 时间轴：Day 分组切换，点击选中元素（多模态上下文），响应 Agent Focus 自动滚动 */
export default function Timeline({ trip, items, focus }: Props) {
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
        {dayItems.map((item) => (
          <div
            key={item.id ?? item.title}
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
        ))}
      </div>
    </>
  );
}
