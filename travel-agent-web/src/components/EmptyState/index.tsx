import type { ReactNode } from 'react';
import { Button } from 'antd';
import './empty-state.css';

/**
 * 共享空状态（体验方案阶段 E · §11 / §13）。
 *
 * 收敛规则：**一个空状态只表达一件事，只给一个主 CTA**。
 * 之前在攻略库、回顾、日程、足迹里各写了一份「Empty + 自定义按钮 + 页面文案」，
 * 同一屏出现两个竞争 CTA、或把加载失败伪装成空状态。这里统一结构：
 *   - title：一句话说明「现在没有什么」（必须具体到资源，不写“暂无数据”）
 *   - hint：说明「为什么空 / 怎么才会有」，可省略
 *   - action：唯一的主操作，可省略（页面本身已有主动作时不要再复制一个）
 * 错误态与加载态不走这个组件：失败要能重试，不能长得像空数据（P0-04）。
 */
interface EmptyStateProps {
  /** 装饰性图标/emoji；不参与可访问名称（标题负责语义） */
  icon?: ReactNode;
  title: string;
  hint?: string;
  action?: {
    label: string;
    onClick: () => void;
    testId?: string;
  };
  /** 紧凑场景：只占 80–96px，用于首页足迹这类「没有数据也不该撑满屏幕」的区块 */
  compact?: boolean;
  /** 外层 testid，便于页面断言「这是空态而不是错误态」 */
  testId?: string;
}

export default function EmptyState({
  icon,
  title,
  hint,
  action,
  compact = false,
  testId,
}: EmptyStateProps) {
  return (
    <div
      className={`empty-state ${compact ? 'empty-state--compact' : ''}`}
      data-testid={testId}
      role="status"
    >
      {icon && (
        <span className="es-icon" aria-hidden="true">
          {icon}
        </span>
      )}
      <div className="es-title">{title}</div>
      {hint && <div className="es-hint">{hint}</div>}
      {action && (
        <Button
          type="primary"
          className="es-action"
          data-testid={action.testId}
          onClick={action.onClick}
        >
          {action.label}
        </Button>
      )}
    </div>
  );
}
