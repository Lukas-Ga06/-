import { Tag } from 'antd';
import type { ItineraryItem } from '../../types/trip';

const TYPE_CHIP: Record<string, { label: string; bg: string; color: string }> = {
  hotel: { label: '酒店', bg: 'rgba(175, 82, 222, 0.12)', color: '#8e44ad' },
  attraction: { label: '景点', bg: 'rgba(0, 122, 255, 0.1)', color: '#007aff' },
  restaurant: { label: '餐饮', bg: 'rgba(255, 149, 0, 0.12)', color: '#d48000' },
  transport: { label: '交通', bg: 'rgba(142, 142, 147, 0.14)', color: '#636366' },
};

interface Props {
  item: ItineraryItem;
  selected?: boolean;
  onClick?: () => void;
}

/** 行程条目卡片：类型徽标 + 标题 + 花费 + 标签 + 简介 */
export default function ItineraryCard({ item, selected, onClick }: Props) {
  const chip = TYPE_CHIP[item.itemType] ?? TYPE_CHIP.attraction;
  const free = item.cost != null && item.cost === 0;

  return (
    <div className={`t-card ${selected ? 'selected' : ''}`} onClick={onClick}>
      <div className="tc-head">
        <div className="tc-title">
          <span
            style={{
              fontSize: 11,
              padding: '2px 8px',
              borderRadius: 7,
              background: chip.bg,
              color: chip.color,
              fontWeight: 700,
              flexShrink: 0,
            }}
          >
            {chip.label}
          </span>
          <span className="tt">{item.title}</span>
        </div>
        {item.cost != null && (
          <span className={`tc-cost ${free ? 'free' : ''}`}>
            {free ? '免费' : `¥${item.cost}`}
          </span>
        )}
      </div>
      {(item.tags?.length ?? 0) > 0 && (
        <div className="tc-tags">
          {item
            .tags!.filter((t) => t)
            .slice(0, 4)
            .map((t) => (
              <span
                key={t}
                className={`mini-tag ${t === '室内' ? 'indoor' : t === '室外' ? 'outdoor' : ''}`}
              >
                {t}
              </span>
            ))}
        </div>
      )}
      {item.description && <div className="tc-desc">{item.description}</div>}
    </div>
  );
}
