import type { ReactNode } from 'react';
import { Button } from 'antd';
import {
  CheckCircleFilled,
  CloseCircleFilled,
  ExclamationCircleFilled,
  InboxOutlined,
  LoadingOutlined,
  StopOutlined,
  SyncOutlined,
  WarningFilled,
} from '@ant-design/icons';

/**
 * 统一运行状态（P1-E）：
 * loading / empty / degraded / partial / failed / cancelled / complete。
 *
 * 规则：
 * - 每种状态都有图标 + 文字，颜色只做辅助（不能只靠红绿区分）；
 * - 失败、降级、部分成功必须能接下一步操作（重试 / 查看时间线 / 返回修改 / 打开诊断）；
 * - 文案由调用方给出：同一个状态在不同模块的含义不同（同步失败 vs 生成失败）。
 */
export type RunState =
  'loading' | 'empty' | 'degraded' | 'partial' | 'failed' | 'cancelled' | 'complete';

interface StatusAction {
  label: string;
  onClick: () => void;
  icon?: ReactNode;
  testId?: string;
}

interface Props {
  status: RunState;
  /** 主文案；缺省用状态的标准名称 */
  text?: string;
  /** 补充说明：为什么失败 / 哪些已保存 / 能做什么 */
  detail?: string;
  /** 下一步操作；失败与降级状态应至少给一个 */
  actions?: StatusAction[];
  size?: 'sm' | 'md';
  /** 纯文字场景（例如文案里已有 ⚠ 符号）可以关掉图标 */
  showIcon?: boolean;
  className?: string;
  testId?: string;
}

const META: Record<
  RunState,
  { label: string; tone: 'ok' | 'warn' | 'danger' | 'muted' | 'info'; icon: ReactNode }
> = {
  loading: { label: '进行中', tone: 'info', icon: <LoadingOutlined spin aria-hidden="true" /> },
  empty: { label: '暂无内容', tone: 'muted', icon: <InboxOutlined aria-hidden="true" /> },
  degraded: { label: '已降级', tone: 'warn', icon: <WarningFilled aria-hidden="true" /> },
  partial: {
    label: '部分成功',
    tone: 'warn',
    icon: <ExclamationCircleFilled aria-hidden="true" />,
  },
  failed: { label: '失败', tone: 'danger', icon: <CloseCircleFilled aria-hidden="true" /> },
  cancelled: { label: '已取消', tone: 'muted', icon: <StopOutlined aria-hidden="true" /> },
  complete: { label: '完成', tone: 'ok', icon: <CheckCircleFilled aria-hidden="true" /> },
};

/** 进度类文案用旋转图标；其余状态用固定语义图标 */
export default function RunStatus({
  status,
  text,
  detail,
  actions,
  size = 'md',
  showIcon = true,
  className = '',
  testId,
}: Props) {
  const meta = META[status] ?? META.empty;
  const icon = status === 'loading' ? <SyncOutlined spin aria-hidden="true" /> : meta.icon;

  return (
    <span
      className={`run-status run-status--${meta.tone} run-status--${size} ${className}`.trim()}
      data-status={status}
      data-testid={testId}
    >
      {showIcon && <span className="rs-icon">{icon}</span>}
      <span className="rs-text">{text ?? meta.label}</span>
      {detail && <span className="rs-detail">{detail}</span>}
      {actions && actions.length > 0 && (
        <span className="rs-actions">
          {actions.map((action) => (
            <Button
              key={action.label}
              type="link"
              size="small"
              icon={action.icon}
              onClick={action.onClick}
              data-testid={action.testId}
              className="rs-action"
            >
              {action.label}
            </Button>
          ))}
        </span>
      )}
    </span>
  );
}
