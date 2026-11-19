import type { ItineraryItem } from '../../types/trip';
import {
  formatPriceAmount,
  hasReferenceAmount,
  priceLinkText,
  priceSourceText,
  priceTagText,
  safePriceUrl,
  visiblePriceLinks,
} from './price';

const TYPE_CHIP: Record<string, { label: string; bg: string; color: string }> = {
  hotel: { label: '酒店', bg: 'rgba(175, 82, 222, 0.12)', color: '#8e44ad' },
  attraction: { label: '景点', bg: 'rgba(31, 111, 235, 0.1)', color: '#1f6feb' },
  restaurant: { label: '餐饮', bg: 'rgba(255, 149, 0, 0.12)', color: '#d48000' },
  transport: { label: '交通', bg: 'rgba(142, 142, 147, 0.14)', color: '#636366' },
};

interface Props {
  item: ItineraryItem;
  selected?: boolean;
  onClick?: () => void;
}

/**
 * 行程条目卡片：类型徽标 + 标题 + 花费 + 标签 + 简介。
 *
 * 价格部分三种形态：
 * 1. 有 `price`（新后端）：金额 +「参考价」小标签 + 来源小字 + 免责声明 + 深链；
 * 2. `price: null` / 旧后端没有该字段：与改动前完全一致，只显示金额（或「免费」）；
 * 3. `cost` 缺失：不显示金额。
 * `price` 的文案一律先过禁词兜底，UI 不出现「实时 / 可订 / 有房 / 立即预订」这类承诺。
 */
export default function ItineraryCard({ item, selected, onClick }: Props) {
  const chip = TYPE_CHIP[item.itemType] ?? TYPE_CHIP.attraction;
  const free = item.cost != null && item.cost === 0;
  const price = item.price ?? null;

  // 有参考金额时优先用 price.amount（含币种），否则退回旧的 cost 展示
  const hasAmount = hasReferenceAmount(price);
  const amountText = hasAmount
    ? formatPriceAmount(price.amount, price.currency)
    : free
      ? '免费'
      : item.cost != null
        ? `¥${item.cost}`
        : null;

  const sourceLabel = priceSourceText(price);
  const disclaimer = price?.disclaimer?.trim() ?? '';
  const links = visiblePriceLinks(price);
  const hasPriceDetails = sourceLabel !== '' || disclaimer !== '' || links.length > 0;

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
        {amountText != null && (
          <span className="tc-price">
            <span className={`tc-cost ${free && !hasAmount ? 'free' : ''}`}>{amountText}</span>
            {hasAmount && (
              <span className="mini-tag price-kind" data-testid="price-tag">
                {priceTagText(price)}
              </span>
            )}
          </span>
        )}
      </div>
      {hasPriceDetails && (
        <div className="tc-price-info">
          {sourceLabel !== '' && (
            <div className="tc-price-source" data-testid="price-source">
              价格来源：{sourceLabel}
            </div>
          )}
          {links.length > 0 && (
            <div className="tc-price-links">
              {links.map((link, index) => {
                const url = safePriceUrl(link.url);
                if (!url) return null;
                return (
                  <a
                    key={`${link.provider ?? 'link'}-${link.kind ?? 'other'}-${index}`}
                    className="tc-price-link"
                    data-testid="price-link"
                    data-kind={link.kind ?? 'other'}
                    href={url}
                    target="_blank"
                    rel="noopener noreferrer"
                    onClick={(event) => event.stopPropagation()}
                  >
                    {priceLinkText(link)}
                  </a>
                );
              })}
            </div>
          )}
          {/* 免责声明原样展示：它本身是「非实时」的否定表述，不改写、不折叠掉信息 */}
          {disclaimer !== '' && (
            <div className="tc-price-disclaimer" data-testid="price-disclaimer">
              {disclaimer}
            </div>
          )}
        </div>
      )}
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
