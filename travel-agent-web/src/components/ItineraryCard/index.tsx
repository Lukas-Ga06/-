import { Button, Dropdown } from 'antd';
import { MoreOutlined } from '@ant-design/icons';
import dayjs from 'dayjs';
import {
  ITINERARY_STATUS_LABELS,
  type ItineraryItem,
  type ItineraryStatus,
} from '../../types/trip';
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

/** 执行状态徽标文案（P1）：未开始 / 进行中 / 已完成 / 已跳过 */
const STATUS_CLASS: Record<ItineraryStatus, string> = {
  PENDING: 'pending',
  IN_PROGRESS: 'in-progress',
  COMPLETED: 'completed',
  SKIPPED: 'skipped',
};

interface Props {
  item: ItineraryItem;
  selected?: boolean;
  onClick?: () => void;
  /** P1：状态操作（开始 / 完成 / 跳过 / 重置） */
  onStatusChange?: (status: ItineraryStatus) => void;
  statusBusy?: boolean;
  /**
   * 状态是离线本地记录的、还没被服务端确认（§7.3 第 4 条）。
   * 此时状态徽标旁必须有「待同步」，不能让用户以为已经写进服务端。
   */
  pendingSync?: boolean;
}

/**
 * 一级主动作由当前状态决定（P1-05）：未开始 → 开始；进行中 → 完成；已跳过 → 重置；
 * 已完成没有主动作。其余动作收进「更多」菜单，避免四个同权按钮平铺。
 */
function primaryAction(status: ItineraryStatus): { label: string; next: ItineraryStatus } | null {
  switch (status) {
    case 'PENDING':
      return { label: '开始', next: 'IN_PROGRESS' };
    case 'IN_PROGRESS':
      return { label: '完成', next: 'COMPLETED' };
    case 'SKIPPED':
      return { label: '重置', next: 'PENDING' };
    default:
      return null;
  }
}

/** 菜单里的次要动作：不与一级主动作重复 */
function secondaryActions(
  status: ItineraryStatus,
  primaryNext: ItineraryStatus | null,
): { key: ItineraryStatus; label: string }[] {
  const all: { key: ItineraryStatus; label: string }[] = [
    { key: 'COMPLETED', label: '直接完成' },
    { key: 'SKIPPED', label: '跳过' },
    { key: 'PENDING', label: '重置' },
  ];
  return all.filter((action) => {
    if (action.key === primaryNext) return false;
    if (action.key === 'COMPLETED') return status === 'PENDING' || status === 'SKIPPED';
    if (action.key === 'SKIPPED') return status === 'PENDING' || status === 'IN_PROGRESS';
    // 重置：除未开始外的任何状态都可用
    return status !== 'PENDING';
  });
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
export default function ItineraryCard({
  item,
  selected,
  onClick,
  onStatusChange,
  statusBusy,
  pendingSync,
}: Props) {
  const chip = TYPE_CHIP[item.itemType] ?? TYPE_CHIP.attraction;
  const free = item.cost != null && item.cost === 0;
  const price = item.price ?? null;
  const status = (item.status ?? 'PENDING') as ItineraryStatus;
  const statusLabel = ITINERARY_STATUS_LABELS[status] ?? ITINERARY_STATUS_LABELS.PENDING;
  const statusClass = STATUS_CLASS[status] ?? STATUS_CLASS.PENDING;
  const actualTime = item.completedAt ?? item.visitedAt ?? item.startedAt;
  const primary = primaryAction(status);
  const menu = secondaryActions(status, primary?.next ?? null);

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
    <div
      className={`t-card ${selected ? 'selected' : ''} ${status === 'COMPLETED' ? 'tc-done' : ''} ${
        status === 'SKIPPED' ? 'tc-skipped' : ''
      }`}
      data-status={status}
      data-pending={pendingSync ? 'true' : 'false'}
      onClick={onClick}
    >
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
          <span className={`tc-status ${statusClass}`} data-testid="item-status">
            {statusLabel}
          </span>
          {pendingSync && (
            <span
              className="tc-pending"
              data-testid="pending-sync"
              title="已在本机记录，联网后自动同步"
            >
              待同步
            </span>
          )}
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
      {actualTime && (
        <div className="tc-actual-time">
          {status === 'COMPLETED'
            ? `完成于 ${dayjs(actualTime).format('MM-DD HH:mm')}`
            : status === 'IN_PROGRESS'
              ? `开始于 ${dayjs(actualTime).format('MM-DD HH:mm')}`
              : `更新于 ${dayjs(actualTime).format('MM-DD HH:mm')}`}
        </div>
      )}
      {onStatusChange && (
        <div className="tc-actions" onClick={(event) => event.stopPropagation()}>
          {primary && (
            <Button
              size="small"
              type="primary"
              disabled={statusBusy}
              onClick={() => onStatusChange(primary.next)}
            >
              {primary.label}
            </Button>
          )}
          {menu.length > 0 && (
            <Dropdown
              trigger={['click']}
              menu={{
                items: menu.map((action) => ({
                  key: action.key,
                  label: action.label,
                  disabled: statusBusy,
                  onClick: () => onStatusChange(action.key),
                })),
              }}
            >
              <Button
                size="small"
                type="text"
                icon={<MoreOutlined />}
                aria-label="更多安排操作"
                disabled={statusBusy}
              />
            </Dropdown>
          )}
          {statusBusy && <span className="tc-status-busy">处理中…</span>}
        </div>
      )}
    </div>
  );
}
