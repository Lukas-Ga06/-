import { useCallback, useEffect, useState } from 'react';
import { Button, Drawer, Empty, Spin } from 'antd';
import { ArrowLeftOutlined, ReloadOutlined } from '@ant-design/icons';
import { getRunDetail, getRuns } from '../../api/agent';
import type { RunDetail, RunSummary, TaskView, ToolCallView } from '../../types/agent';

interface Props {
  open: boolean;
  onClose: () => void;
}

/** 运行状态徽标文案（成功绿 / 失败红 / 运行中蓝 / 取消灰） */
function runStatusMeta(status: string): { text: string; tone: 'ok' | 'fail' | 'run' | 'muted' } {
  switch (status) {
    case 'SUCCEEDED':
      return { text: '成功', tone: 'ok' };
    case 'FAILED':
      return { text: '失败', tone: 'fail' };
    case 'RUNNING':
      return { text: '运行中', tone: 'run' };
    default:
      return { text: '已取消', tone: 'muted' };
  }
}

function taskStatusMeta(status: string): { text: string; tone: 'ok' | 'fail' | 'run' } {
  switch (status) {
    case 'SUCCEEDED':
      return { text: '成功', tone: 'ok' };
    case 'FAILED':
      return { text: '失败', tone: 'fail' };
    // Batch 15：前置未完成、任务被拒绝启动
    case 'CONFLICT':
      return { text: '冲突', tone: 'fail' };
    default:
      return { text: '运行中', tone: 'run' };
  }
}

function formatDuration(ms?: number | null): string {
  if (ms == null || !Number.isFinite(ms)) return '—';
  if (ms < 1000) return `${Math.round(ms)}ms`;
  if (ms < 60000) return `${(ms / 1000).toFixed(1)}s`;
  return `${Math.floor(ms / 60000)}m${Math.round((ms % 60000) / 1000)}s`;
}

function formatClock(iso?: string | null): string {
  if (!iso) return '—';
  const d = new Date(iso);
  return Number.isNaN(d.getTime()) ? '—' : d.toLocaleTimeString('zh-CN', { hour12: false });
}

/** digest 是后端脱敏摘要：只截断展示，不解析也不渲染原始参数 */
function truncateDigest(digest?: string | null, max = 24): string | null {
  if (!digest) return null;
  return digest.length > max ? `${digest.slice(0, max)}…` : digest;
}

function RunRow({ run, onClick }: { run: RunSummary; onClick: () => void }) {
  const meta = runStatusMeta(run.status);
  return (
    <button type="button" className="run-row" onClick={onClick} data-testid={`agent-run-${run.runId}`}>
      <span className={`run-badge ${meta.tone}`}>{meta.text}</span>
      <span className="run-body">
        <span className="run-title">{run.intent || run.message || '（无意图记录）'}</span>
        <span className="run-sub">
          {formatClock(run.startedAt)} · 耗时 {formatDuration(run.durationMs)}
          {run.tripId != null ? ` · 行程 #${run.tripId}` : ''}
        </span>
        {run.error && <span className="run-error">{run.error}</span>}
      </span>
    </button>
  );
}

function TaskRow({ task }: { task: TaskView }) {
  const meta = taskStatusMeta(task.status);
  // Batch 15 任务 DAG：依赖数 / 重派次数 / 是否替代前序任务（旧后端字段缺失时整行不出现）
  const dependsOn = Array.isArray(task.dependsOn) ? task.dependsOn : [];
  const attempt = typeof task.attempt === 'number' && task.attempt > 0 ? task.attempt : 0;
  const replacesTaskId =
    typeof task.replacesTaskId === 'string' && task.replacesTaskId ? task.replacesTaskId : '';
  const dagBits = [
    dependsOn.length > 0 ? `依赖 ${dependsOn.length} 项` : null,
    attempt > 0 ? `第 ${attempt + 1} 次尝试` : null,
    replacesTaskId ? '替代前序任务' : null,
  ].filter((v): v is string => v != null);
  return (
    <div className="task-row" data-testid={`agent-task-${task.taskId}`}>
      <span className="task-chain">
        {task.receiver} → {task.taskType}
      </span>
      <span className={`run-badge ${meta.tone}`}>{meta.text}</span>
      <span className="task-body">
        {task.summary && <span className="task-summary">{task.summary}</span>}
        {dagBits.length > 0 && (
          // 依赖 id 全文只放 title，不铺在行内
          <span
            className="run-sub"
            title={dependsOn.length > 0 ? `依赖：${dependsOn.join('、')}` : undefined}
          >
            {dagBits.join(' · ')}
          </span>
        )}
        {task.error && <span className="run-error">{task.error}</span>}
        <span className="run-sub">
          {formatClock(task.startedAt)} · {formatDuration(task.durationMs)}
        </span>
      </span>
    </div>
  );
}

function ToolRow({ call }: { call: ToolCallView }) {
  const digest = truncateDigest(call.argsDigest);
  return (
    <div className="tool-row">
      <span className={`run-badge ${call.success ? 'ok' : 'fail'}`}>{call.success ? '成功' : '失败'}</span>
      <span className="tool-name">{call.toolName}</span>
      <span className="tool-body">
        {call.latencyMs != null && <span className="run-sub">{formatDuration(call.latencyMs)}</span>}
        {digest && <span className="tool-digest" title="参数摘要（已脱敏）">{digest}</span>}
        {!call.success && call.errorCode && <span className="run-error">{call.errorCode}</span>}
      </span>
    </div>
  );
}

/** 错误态：显示错误信息与重试按钮（404「他人的 run」/ 网络错误都走这里） */
function ErrorBox({ message, onRetry, busy }: { message: string; onRetry: () => void; busy?: boolean }) {
  return (
    <div className="run-error-box">
      <div className="run-error">⚠ {message}</div>
      <Button size="small" icon={<ReloadOutlined />} loading={busy} onClick={onRetry}>
        重试
      </Button>
    </div>
  );
}

/**
 * 运行记录抽屉（V2.2 运行追踪）：列出最近 20 次运行，
 * 点击行展开该次运行的任务链（receiver → taskType → status → summary）
 * 与工具调用（工具名 + 成败 + latencyMs + argsDigest 截断）。
 */
export default function AgentRunsDrawer({ open, onClose }: Props) {
  const [runs, setRuns] = useState<RunSummary[] | null>(null);
  const [listLoading, setListLoading] = useState(false);
  const [listError, setListError] = useState<string | null>(null);
  const [detail, setDetail] = useState<RunDetail | null>(null);
  const [detailLoading, setDetailLoading] = useState(false);
  const [detailError, setDetailError] = useState<string | null>(null);
  const [selectedRunId, setSelectedRunId] = useState<string | null>(null);

  const loadList = useCallback(() => {
    setListLoading(true);
    setListError(null);
    getRuns(20)
      .then((list) => setRuns(Array.isArray(list) ? list : []))
      .catch((e: any) => setListError(e?.message || '运行记录加载失败'))
      .finally(() => setListLoading(false));
  }, []);

  const loadDetail = useCallback((runId: string) => {
    setSelectedRunId(runId);
    setDetailLoading(true);
    setDetailError(null);
    setDetail(null);
    getRunDetail(runId)
      .then(setDetail)
      .catch((e: any) => setDetailError(e?.message || '运行详情加载失败'))
      .finally(() => setDetailLoading(false));
  }, []);

  // 打开抽屉时拉列表；关闭时清掉已展开的详情
  useEffect(() => {
    if (open) {
      loadList();
    } else {
      setDetail(null);
      setDetailError(null);
      setSelectedRunId(null);
    }
  }, [open, loadList]);

  return (
    <Drawer
      title="运行记录"
      open={open}
      onClose={onClose}
      width={520}
      styles={{ body: { paddingTop: 12 } }}
    >
      <div className="runs-drawer" data-testid="agent-runs-drawer">
        {listLoading && <Spin style={{ display: 'block', margin: '24px auto' }} />}
        {!listLoading && listError && <ErrorBox message={listError} onRetry={loadList} />}
        {!listLoading && !listError && detail && (
          <>
            <Button
              size="small"
              icon={<ArrowLeftOutlined />}
              onClick={() => setDetail(null)}
              style={{ marginBottom: 12 }}
            >
              返回运行列表
            </Button>
            <RunRow run={detail.run} onClick={() => loadDetail(detail.run.runId)} />
          </>
        )}
        {!listLoading && !listError && !detail && (
          <>
            {runs && runs.length === 0 && <Empty description="还没有运行记录" />}
            {runs && runs.length > 0 && runs.map((r) => <RunRow key={r.runId} run={r} onClick={() => loadDetail(r.runId)} />)}
          </>
        )}
        {detailLoading && <Spin style={{ display: 'block', margin: '24px auto' }} />}
        {detailError && (
          <ErrorBox
            message={detailError}
            onRetry={() => selectedRunId && loadDetail(selectedRunId)}
            busy={detailLoading}
          />
        )}
        {detail && !detailLoading && (
          <>
            <div className="runs-section-title">任务链</div>
            {(detail.tasks ?? []).map((t) => (
              <TaskRow key={t.taskId} task={t} />
            ))}
            {(detail.tasks ?? []).length === 0 && <div className="runs-empty-line">无任务记录</div>}
            <div className="runs-section-title">工具调用</div>
            {(detail.toolCalls ?? []).map((c) => (
              <ToolRow key={`${c.id}-${c.toolName}`} call={c} />
            ))}
            {(detail.toolCalls ?? []).length === 0 && <div className="runs-empty-line">无工具调用</div>}
          </>
        )}
      </div>
    </Drawer>
  );
}
