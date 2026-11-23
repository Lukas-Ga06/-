import { Button, Tooltip } from 'antd';
import {
  CalendarOutlined,
  CloseCircleFilled,
  CloseOutlined,
  CompassOutlined,
  EnvironmentOutlined,
  ReloadOutlined,
  WalletOutlined,
} from '@ant-design/icons';
import type { ExecutionModule, ExecutionSummary } from '../../types/agent';
import RunStatus, { type RunState } from '../RunStatus';

interface Props {
  execution: ExecutionSummary;
  /** 重试整批失败模块（后端只重试 FAILED 项，成功项不重复创建） */
  onRetry?: () => void;
  retrying?: boolean;
  /** 用户主动关闭面板：收到新 execution 前不再显示 */
  onClose?: () => void;
}

const MODULE_META: Record<string, { name: string; icon: React.ReactNode }> = {
  trip: { name: '行程', icon: <CompassOutlined aria-hidden="true" /> },
  calendar: { name: '日程', icon: <CalendarOutlined aria-hidden="true" /> },
  budget: { name: '预算', icon: <WalletOutlined aria-hidden="true" /> },
  footprint: { name: '足迹', icon: <EnvironmentOutlined aria-hidden="true" /> },
};

const STATUS_META: Record<string, { text: string; state: RunState; tone: 'ok' | 'fail' }> = {
  SUCCEEDED: { text: '全部同步完成', state: 'complete', tone: 'ok' },
  PARTIAL_FAILED: { text: '部分同步失败', state: 'partial', tone: 'fail' },
  FAILED: { text: '同步失败', state: 'failed', tone: 'fail' },
};

function renderModule(m: ExecutionModule, onRetry?: () => void, retrying?: boolean) {
  const meta = MODULE_META[m.module] ?? {
    name: m.module,
    icon: <CloseCircleFilled aria-hidden="true" />,
  };
  const failed = m.status === 'FAILED';
  return (
    <div
      key={m.module}
      className={`exec-module-row ${failed ? 'fail' : 'ok'}`}
      data-module={m.module}
    >
      <span className="em-icon">{meta.icon}</span>
      <span className="em-name">{meta.name}</span>
      <RunStatus
        status={failed ? 'failed' : 'complete'}
        text={failed ? '失败' : '已同步'}
        size="sm"
        showIcon
        className={`em-status ${failed ? 'fail' : 'ok'}`}
      />
      <span className="em-body">
        {m.detail && <span className="em-detail">{m.detail}</span>}
        {failed && m.error && <span className="em-error">{m.error}</span>}
        {failed && m.retryCount != null && m.retryCount > 0 && (
          <span className="em-retry-count">已重试 {m.retryCount} 次</span>
        )}
        {failed && (
          <span className="em-next">
            {onRetry ? '只重试这一批失败模块，成功项不会重复创建。' : '检查网络后可再次提交。'}
          </span>
        )}
        {failed && onRetry && (
          <Button
            size="small"
            icon={<ReloadOutlined />}
            loading={retrying}
            onClick={onRetry}
            className="em-retry-btn"
            title="只重试失败的模块，成功项不会重复创建"
          >
            重试
          </Button>
        )}
      </span>
    </div>
  );
}

/**
 * 确认提交后的同步结果面板（V2.2 §9.1）：行程 / 日程 / 预算 / 足迹各模块的
 * 成功、失败、可重试与部分成功展示。失败行都给出下一步操作。
 */
export default function ExecutionResultPanel({ execution, onRetry, retrying, onClose }: Props) {
  const modules: ExecutionModule[] = Array.isArray(execution.modules) ? execution.modules : [];
  const status = STATUS_META[execution.status] ?? {
    text: execution.status,
    state: 'failed' as RunState,
    tone: 'fail' as const,
  };
  const hasFailed = modules.some((m) => m.status === 'FAILED');

  return (
    <div className="exec-panel" data-testid="execution-result-panel">
      <div className="exec-head">
        <RunStatus
          status={status.state}
          text={status.text}
          size="md"
          className={`exec-status ${status.tone}`}
        />
        {onClose && (
          <Tooltip title="关闭（下次确认后重新出现）">
            <Button
              type="text"
              size="small"
              icon={<CloseOutlined />}
              onClick={onClose}
              aria-label="关闭同步结果面板"
            />
          </Tooltip>
        )}
      </div>
      <div className="exec-modules">{modules.map((m) => renderModule(m, onRetry, retrying))}</div>
      {status.tone === 'fail' && (
        <div className="exec-note">
          {hasFailed
            ? '部分模块同步失败：可对失败模块单独重试，成功项不会重复创建。'
            : '同步失败：可重试，成功项不会重复创建。'}
          {onRetry && (
            <Button
              size="small"
              type="link"
              icon={<ReloadOutlined />}
              loading={retrying}
              onClick={onRetry}
              className="exec-retry-all"
            >
              重试失败模块
            </Button>
          )}
          <span className="exec-extra">预算为计划快照，不会自动记账；足迹为计划标记。</span>
        </div>
      )}
    </div>
  );
}
