import { useCallback, useEffect, useRef, useState } from 'react';
import { Button, Empty, Spin } from 'antd';
import { ReloadOutlined } from '@ant-design/icons';
import dayjs from 'dayjs';
import { useNavigate } from 'react-router-dom';
import AppShell from '../../components/AppShell';
import { getRunDetail, getRuns } from '../../api/agent';
import type { RunDetail, RunSummary, TaskView, ToolCallView } from '../../types/agent';
import './workbench.css';

/** 运行状态徽标文案（成功绿 / 部分完成橙 / 失败红 / 运行中蓝 / 取消灰） */
function runStatusMeta(status: string): {
  text: string;
  tone: 'ok' | 'warn' | 'fail' | 'run' | 'muted';
} {
  switch (status) {
    case 'SUCCEEDED':
      return { text: '成功', tone: 'ok' };
    // P0-07：有阶段失败/降级、过程警告或预算降级时，绝不能与完全成功共用绿色标签
    case 'PARTIAL_SUCCESS':
      return { text: '部分完成', tone: 'warn' };
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

/** 运行列表展示「创建/开始时间」；后端返回 ISO 字符串，解析失败显示占位 */
function formatDateTime(iso?: string | null): string {
  if (!iso) return '—';
  const d = dayjs(iso);
  return d.isValid() ? d.format('YYYY-MM-DD HH:mm:ss') : '—';
}

/** digest 是后端脱敏摘要：只截断展示，不解析也不渲染原始参数 */
function truncateDigest(digest?: string | null, max = 32): string | null {
  if (!digest) return null;
  return digest.length > max ? `${digest.slice(0, max)}…` : digest;
}

function ErrorBox({
  message,
  onRetry,
  busy,
}: {
  message: string;
  onRetry: () => void;
  busy?: boolean;
}) {
  return (
    <div className="run-error-box">
      <div className="run-error">⚠ {message}</div>
      <Button size="small" icon={<ReloadOutlined />} loading={busy} onClick={onRetry}>
        重试
      </Button>
    </div>
  );
}

function RunRow({
  run,
  selected,
  onClick,
}: {
  run: RunSummary;
  selected: boolean;
  onClick: () => void;
}) {
  const meta = runStatusMeta(run.status);
  return (
    <button
      type="button"
      className={`run-row ${selected ? 'selected' : ''}`}
      onClick={onClick}
      data-testid={`agent-run-${run.runId}`}
    >
      <span className={`run-badge ${meta.tone}`}>{meta.text}</span>
      <span className="run-body">
        <span className="run-title">{run.intent || run.message || '（无意图记录）'}</span>
        <span className="run-sub">
          {formatDateTime(run.startedAt)} · 耗时 {formatDuration(run.durationMs)}
          {run.tripId != null ? ` · 行程 #${run.tripId}` : ''}
        </span>
        {run.error && <span className="run-error">{run.error}</span>}
      </span>
    </button>
  );
}

function TaskRow({ task }: { task: TaskView }) {
  const meta = taskStatusMeta(task.status);
  const dependsOn = Array.isArray(task.dependsOn) ? task.dependsOn : [];
  const attempt = typeof task.attempt === 'number' && task.attempt > 0 ? task.attempt : 0;
  const replacesTaskId =
    typeof task.replacesTaskId === 'string' && task.replacesTaskId ? task.replacesTaskId : '';
  /** 后端用「budget-guard → DEGRADE」这一条任务记录预算降级，不需要额外字段 */
  const degraded = task.taskType === 'DEGRADE' || task.receiver === 'budget-guard';
  return (
    <div className="task-row" data-testid={`agent-task-${task.taskId}`}>
      <span className="task-chain">
        {task.receiver} → {task.taskType}
      </span>
      <span className={`run-badge ${meta.tone}`}>{meta.text}</span>
      <span className="task-body">
        {task.summary && <span className="task-summary">{task.summary}</span>}
        {(degraded || dependsOn.length > 0 || attempt > 0 || replacesTaskId) && (
          <span className="wb-tags">
            {degraded && <span className="wb-tag warn">预算降级 · 已跳过部分阶段</span>}
            {dependsOn.length > 0 && (
              <span className="wb-tag" title={`依赖：${dependsOn.join('、')}`}>
                依赖 {dependsOn.length} 项
              </span>
            )}
            {attempt > 0 && <span className="wb-tag warn">重派 · 第 {attempt + 1} 次尝试</span>}
            {replacesTaskId && <span className="wb-tag">替代前序任务</span>}
          </span>
        )}
        {task.error && <span className="run-error">{task.error}</span>}
        <span className="run-sub">
          {formatDateTime(task.startedAt)} · {formatDuration(task.durationMs)}
        </span>
      </span>
    </div>
  );
}

/** 工具调用：provider 即工具名；source 用所属任务标识；失败原因展示后端 errorCode */
function ToolRow({ call }: { call: ToolCallView }) {
  const digest = truncateDigest(call.argsDigest);
  return (
    <div className="tool-row" data-testid={`agent-tool-${call.id}`}>
      <span className={`run-badge ${call.success ? 'ok' : 'fail'}`}>
        {call.success ? '成功' : '失败'}
      </span>
      <span className="tool-name">{call.toolName}</span>
      <span className="tool-body">
        <span className="run-sub">
          来源：{call.taskId ?? '—'} · 耗时 {formatDuration(call.latencyMs)}
        </span>
        {digest && (
          <span className="tool-digest" title="参数摘要（已脱敏）">
            {digest}
          </span>
        )}
        {!call.success && <span className="run-error">失败原因：{call.errorCode || '未上报'}</span>}
      </span>
    </div>
  );
}

/**
 * Agent 运行观察：列出最近运行（状态 / 耗时 / 创建时间），
 * 选中后展示任务 DAG（依赖、重派、替代、CONFLICT）与工具调用（工具名、来源任务、耗时、失败原因）。
 *
 * 运行追踪契约（RunDetail）不包含确认状态——确认状态属于对话侧的 AgentResult，
 * 因此本页不虚构该字段，只给出「去 AI 助手查看待确认方案」的去向提示。
 */
export default function Workbench() {
  const navigate = useNavigate();
  const [runs, setRuns] = useState<RunSummary[] | null>(null);
  const [listLoading, setListLoading] = useState(false);
  const [listError, setListError] = useState<string | null>(null);
  const [selectedRunId, setSelectedRunId] = useState<string | null>(null);
  const [detail, setDetail] = useState<RunDetail | null>(null);
  const [detailLoading, setDetailLoading] = useState(false);
  const [detailError, setDetailError] = useState<string | null>(null);
  /** 刷新列表时保留当前选中运行：用 ref 避免把 selectedRunId 塞进 loadList 依赖 */
  const selectedRef = useRef<string | null>(null);

  useEffect(() => {
    selectedRef.current = selectedRunId;
  }, [selectedRunId]);

  const loadDetail = useCallback((runId: string) => {
    // 立刻同步 ref：列表刷新与用户点选并发时，避免自动选中覆盖用户刚选的运行
    selectedRef.current = runId;
    setSelectedRunId(runId);
    setDetailLoading(true);
    setDetailError(null);
    setDetail(null);
    getRunDetail(runId)
      .then(setDetail)
      .catch((e: any) => setDetailError(e?.message || '运行详情加载失败'))
      .finally(() => setDetailLoading(false));
  }, []);

  const loadList = useCallback(() => {
    setListLoading(true);
    setListError(null);
    getRuns(20)
      .then((list) => {
        const safe = Array.isArray(list) ? list : [];
        setRuns(safe);
        const keep =
          selectedRef.current && safe.some((r) => r.runId === selectedRef.current)
            ? selectedRef.current
            : safe[0]?.runId;
        if (keep) {
          loadDetail(keep);
        } else {
          setSelectedRunId(null);
          setDetail(null);
        }
      })
      .catch((e: any) => setListError(e?.message || '运行记录加载失败'))
      .finally(() => setListLoading(false));
  }, [loadDetail]);

  useEffect(() => {
    loadList();
  }, [loadList]);

  const refresh = () => loadList();

  const tasks = detail?.tasks ?? [];
  const toolCalls = detail?.toolCalls ?? [];
  const degraded = tasks.filter(
    (t) => t.taskType === 'DEGRADE' || t.receiver === 'budget-guard',
  ).length;
  const reassigned = tasks.filter((t) => typeof t.attempt === 'number' && t.attempt > 0).length;
  const conflicts = tasks.filter((t) => t.status === 'CONFLICT').length;
  const failedTasks = tasks.filter((t) => t.status === 'FAILED').length;
  const failedTools = toolCalls.filter((c) => !c.success).length;
  /** 部分完成时告诉用户「受影响的具体内容」，而不是只给一个橙色标签 */
  const partialReasons = [
    failedTasks > 0 ? `${failedTasks} 个阶段失败` : null,
    degraded > 0 ? `${degraded} 次预算降级` : null,
    failedTools > 0 ? `${failedTools} 次工具失败` : null,
  ].filter(Boolean);

  return (
    <AppShell active="diagnostics">
      <div className="dash-wrap">
        <div className="dash-greeting">
          <div>
            <h1>🩺 运行诊断</h1>
            <div className="dg-date">
              回看每次旅行规划运行：任务 DAG、重派与冲突、工具调用耗时与失败原因
            </div>
          </div>
          <Button icon={<ReloadOutlined />} loading={listLoading} onClick={refresh}>
            刷新
          </Button>
        </div>

        <div className="workbench-grid">
          <section className="dash-card">
            <div className="dc-head">
              <b>运行列表</b>
              <span className="run-sub">{runs ? `最近 ${runs.length} 次` : '加载中…'}</span>
            </div>
            <div className="runs-drawer workbench-list-scroll">
              {listLoading && !runs && <Spin style={{ display: 'block', margin: '24px auto' }} />}
              {listError && <ErrorBox message={listError} onRetry={loadList} busy={listLoading} />}
              {!listLoading && !listError && runs && runs.length === 0 && (
                <Empty description="还没有运行记录，去 AI 助手发起一次旅行规划吧" />
              )}
              {runs &&
                runs.map((run) => (
                  <RunRow
                    key={run.runId}
                    run={run}
                    selected={run.runId === selectedRunId}
                    onClick={() => loadDetail(run.runId)}
                  />
                ))}
            </div>
          </section>

          <section className="dash-card">
            <div className="dc-head">
              <b>运行详情</b>
              {detail && (
                <span className="run-sub">
                  降级 {degraded} · 重派 {reassigned} · 冲突 {conflicts} · 失败任务 {failedTasks} ·
                  工具失败 {failedTools}
                </span>
              )}
            </div>

            {detailLoading && <Spin style={{ display: 'block', margin: '32px auto' }} />}
            {!detailLoading && detailError && (
              <ErrorBox
                message={detailError}
                onRetry={() => selectedRunId && loadDetail(selectedRunId)}
                busy={detailLoading}
              />
            )}
            {!detailLoading && !detailError && !detail && (
              <Empty description="选择左侧运行查看任务链与工具调用" />
            )}

            {detail && !detailLoading && (
              <div className="runs-drawer">
                <RunRow run={detail.run} selected onClick={() => loadDetail(detail.run.runId)} />
                <div className="wb-summary">
                  <span>
                    开始：<b>{formatDateTime(detail.run.startedAt)}</b>
                  </span>
                  <span>
                    结束：<b>{formatDateTime(detail.run.endedAt)}</b>
                  </span>
                  <span>
                    耗时：<b>{formatDuration(detail.run.durationMs)}</b>
                  </span>
                  <span>
                    意图：<b>{detail.run.intent || '—'}</b>
                  </span>
                </div>
                <div className="wb-note">
                  <span>确认状态：运行追踪契约不含该字段，待确认方案请到 AI 助手查看。</span>
                  <Button type="link" size="small" onClick={() => navigate('/assistant')}>
                    前往 AI 助手
                  </Button>
                </div>

                {/* P0-07：部分完成必须解释「哪里受影响」，不能只换一个徽标颜色 */}
                {detail.run.status === 'PARTIAL_SUCCESS' && (
                  <div
                    className="wb-note wb-note-warn"
                    role="status"
                    data-testid="run-partial-note"
                  >
                    <span>
                      本轮为部分完成：
                      {partialReasons.length > 0 ? partialReasons.join('、') : '存在未完成的阶段'}
                      。结论可能不完整，下面的任务链与工具调用即受影响范围。
                    </span>
                  </div>
                )}

                <div className="runs-section-title">任务 DAG / 任务列表</div>
                {tasks.map((task) => (
                  <TaskRow key={task.taskId} task={task} />
                ))}
                {tasks.length === 0 && <div className="runs-empty-line">无任务记录</div>}

                <div className="runs-section-title">工具调用</div>
                {toolCalls.map((call) => (
                  <ToolRow key={`${call.id}-${call.toolName}`} call={call} />
                ))}
                {toolCalls.length === 0 && <div className="runs-empty-line">无工具调用</div>}
              </div>
            )}
          </section>
        </div>
      </div>
    </AppShell>
  );
}
