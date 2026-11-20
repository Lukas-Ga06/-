import { Button } from 'antd';
import { CloseOutlined, ReloadOutlined } from '@ant-design/icons';
import type { ExecutionModule, ExecutionSummary } from '../../types/agent';

interface Props {
  execution: ExecutionSummary;
  /** 重试整批失败模块（后端只重试 FAILED 项，成功项不重复创建） */
  onRetry?: () => void;
  retrying?: boolean;
  /** 用户主动关闭面板：收到新 execution 前不再显示 */
  onClose?: () => void;
}

const MODULE_META: Record<string, { name: string; icon: string }> = {
  calendar: { name: '日程', icon: '📅' },
  budget: { name: '预算', icon: '💰' },
  footprint: { name: '足迹', icon: '📍' },
};

const STATUS_TEXT: Record<string, { text: string; tone: 'ok' | 'fail' }> = {
  SUCCEEDED: { text: '全部同步完成', tone: 'ok' },
  PARTIAL_FAILED: { text: '部分同步失败', tone: 'fail' },
  FAILED: { text: '同步失败', tone: 'fail' },
};

function renderModule(m: ExecutionModule, onRetry?: () => void, retrying?: boolean) {
  const meta = MODULE_META[m.module] ?? { name: m.module, icon: '•' };
  const failed = m.status === 'FAILED';
  return (
    <div
      key={m.module}
      className={`exec-module-row ${failed ? 'fail' : 'ok'}`}
      data-module={m.module}
    >
      <span className="em-icon">{meta.icon}</span>
      <span className="em-name">{meta.name}</span>
      <span className={`em-status ${failed ? 'fail' : 'ok'}`}>{failed ? '失败' : '已同步'}</span>
      <span className="em-body">
        {m.detail && <span className="em-detail">{m.detail}</span>}
        {failed && m.error && <span className="em-error">{m.error}</span>}
        {failed && m.retryCount != null && m.retryCount > 0 && (
          <span className="em-retry-count">已重试 {m.retryCount} 次</span>
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
 * 确认提交后的同步结果面板（V2.2 §9.1）：日程 / 预算 / 足迹 三模块的
 * 模块级成功与失败展示 + 失败重试入口。挂在 ChatPanel 助手消息之后。
 */
export default function ExecutionResultPanel({ execution, onRetry, retrying, onClose }: Props) {
  const modules: ExecutionModule[] = Array.isArray(execution.modules) ? execution.modules : [];
  const status = STATUS_TEXT[execution.status] ?? { text: execution.status, tone: 'fail' as const };
  const hasFailed = modules.some((m) => m.status === 'FAILED');

  return (
    <div className="exec-panel" data-testid="execution-result-panel">
      <div className="exec-head">
        <span className={`exec-status ${status.tone}`}>
          {/* 图标与文案分属两个节点：语义色只给文案，读屏也只念文案 */}
          <span className="exec-status-icon" aria-hidden="true">
            {status.tone === 'ok' ? '✅' : '⚠️'}
          </span>{' '}
          <span className="exec-status-text">{status.text}</span>
        </span>
        {onClose && (
          <Button
            type="text"
            size="small"
            icon={<CloseOutlined />}
            onClick={onClose}
            aria-label="关闭同步结果面板"
            title="关闭（下次确认后重新出现）"
          />
        )}
      </div>
      <div className="exec-modules">{modules.map((m) => renderModule(m, onRetry, retrying))}</div>
      {status.tone === 'fail' && (
        <div className="exec-note">
          {hasFailed
            ? '部分模块同步失败：可对失败模块单独重试，成功项不会重复创建。'
            : '同步失败：可重试，成功项不会重复创建。'}
          <span className="exec-extra">预算为计划快照，不会自动记账；足迹为计划标记。</span>
        </div>
      )}
    </div>
  );
}
