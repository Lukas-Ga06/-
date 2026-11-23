import { Button, Dropdown, Tooltip } from 'antd';
import type { MenuProps } from 'antd';
import {
  ArrowLeftOutlined,
  BarChartOutlined,
  BookOutlined,
  CloudSyncOutlined,
  MoreOutlined,
  ReadOutlined,
  UndoOutlined,
  WalletOutlined,
} from '@ant-design/icons';
import { daysOf, tripPhaseLabel, type ItineraryItem, type Trip } from '../../../types/trip';
import type { UndoState } from './types';

interface Props {
  tripId: number;
  currentTrip: Trip | null;
  items: ItineraryItem[];
  /** 已花金额（按当前叠加待同步状态后的行程项汇总） */
  total: number;
  budget: number | null;
  over: boolean;
  /** 行中阶段：一级动作给「记账」，行前给「预算分析」（P1-05 按阶段收敛页头） */
  ongoing: boolean;
  /** 离线待同步条数：>0 时页头给出总数 */
  pendingCount: number;
  /** 短时撤销窗口；null = 没有可撤销的操作 */
  undo: UndoState | null;
  undoing: boolean;
  onBack: () => void;
  onUndoStep: () => void;
  onUndoAiChange: () => void;
  onOpenReview: () => void;
  onExportGuidebook: () => void;
  onOpenBudget: () => void;
  onOpenExpense: () => void;
}

/**
 * 行程页头：返回 + 行程概要 + 阶段相关的一级动作。
 * 回顾、路书、撤销这类低频/危险动作收在「更多」菜单里，一级只留与当前阶段最相关的动作。
 */
export default function TripHeader({
  tripId,
  currentTrip,
  items,
  total,
  budget,
  over,
  ongoing,
  pendingCount,
  undo,
  undoing,
  onBack,
  onUndoStep,
  onUndoAiChange,
  onOpenReview,
  onExportGuidebook,
  onOpenBudget,
  onOpenExpense,
}: Props) {
  /**
   * 更多菜单：低频与危险动作收在这里。
   * 撤销在没有可撤销动作时保持可见但禁用，并写明原因 —— 用户不用点一下才知道。
   */
  const tripMenuItems: MenuProps['items'] = [
    {
      key: 'undo-step',
      icon: <UndoOutlined />,
      label: undo ? (
        `撤销上一步：${undo.message}`
      ) : (
        <span className="menu-with-hint">
          撤销上一步
          <span className="menu-hint">当前没有可撤销操作</span>
        </span>
      ),
      disabled: !undo || undoing,
      onClick: onUndoStep,
    },
    {
      key: 'undo',
      icon: <UndoOutlined />,
      label: '撤销上一次 AI 修改',
      disabled: undoing,
      onClick: onUndoAiChange,
    },
    {
      key: 'review',
      icon: <ReadOutlined />,
      label: '旅行回顾',
      disabled: !tripId || Number.isNaN(tripId),
      onClick: onOpenReview,
    },
    {
      key: 'guidebook',
      icon: <BookOutlined />,
      label: '导出路书 PDF',
      onClick: onExportGuidebook,
    },
    ongoing
      ? {
          key: 'budget',
          icon: <BarChartOutlined />,
          label: '预算分析',
          onClick: onOpenBudget,
        }
      : {
          key: 'expense',
          icon: <WalletOutlined />,
          label: '记账',
          onClick: onOpenExpense,
        },
  ];

  return (
    <div className="dash-greeting">
      <div className="dg-left">
        <Tooltip title="返回我的行程">
          <Button
            type="text"
            icon={<ArrowLeftOutlined />}
            onClick={onBack}
            aria-label="返回我的行程"
          />
        </Tooltip>
        <div>
          <h1>{currentTrip?.name ?? '行程'}</h1>
          <div className="dg-date">
            {currentTrip
              ? `${currentTrip.destination} · ${daysOf(currentTrip)} 天 · ${currentTrip.partySize ?? 1} 人 · ${tripPhaseLabel(currentTrip)} · ${items.length} 个安排`
              : '加载中…'}
          </div>
        </div>
      </div>
      <div className="page-actions">
        {pendingCount > 0 && (
          // 离线记录的状态不是「已完成」，只是「本机已记录」：页头先给一句总数
          <span className="chip chip-warn trip-pending-chip" data-testid="trip-pending-sync">
            <CloudSyncOutlined aria-hidden="true" /> {pendingCount} 项待同步
          </span>
        )}
        <span className={`chip ${over ? 'chip-warn' : 'chip-ok'}`}>
          已花 ¥{total.toFixed(0)} / 预算 ¥{budget ?? '-'}
        </span>
        {/* 一级只保留与当前阶段最相关的动作（P1-05）：行中先记账，行前先看预算 */}
        <Button
          type="primary"
          icon={ongoing ? <WalletOutlined /> : <BarChartOutlined />}
          onClick={ongoing ? onOpenExpense : onOpenBudget}
        >
          {ongoing ? '记账' : '预算分析'}
        </Button>
        <Tooltip title="更多行程操作">
          <Dropdown trigger={['click']} menu={{ items: tripMenuItems }}>
            <Button icon={<MoreOutlined />} aria-label="更多行程操作" />
          </Dropdown>
        </Tooltip>
      </div>
    </div>
  );
}
