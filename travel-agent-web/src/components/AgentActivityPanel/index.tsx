import { Fragment, useEffect, useRef, useState } from 'react';
import { Button } from 'antd';
import {
  AimOutlined,
  ApiOutlined,
  BookOutlined,
  CheckCircleOutlined,
  CompassOutlined,
  DiffOutlined,
  DownOutlined,
  ExportOutlined,
  FlagOutlined,
  PauseCircleOutlined,
  RedoOutlined,
  RocketOutlined,
  StopOutlined,
  SyncOutlined,
  ToolOutlined,
  UpOutlined,
  WarningOutlined,
} from '@ant-design/icons';
import { stageText, shortRunId, type ActivityEvent } from '../../store/stageText';
import {
  ACTIVITY_STAGE_META,
  activityRunState,
  activitySeverity,
  activityStage,
  ledgerRunState,
  mergeRunStates,
  type ActivitySeverity,
  type ActivityStage,
} from '../../lib/activityStage';
import CitationList from '../CitationList';
import RunStatus, { type RunState } from '../RunStatus';
import type { AgentCitation, ExecutionSummary } from '../../types/agent';
import './activity-stage.css';

interface Props {
  activity: ActivityEvent[];
  /**
   * 确认提交后的同步台账（真实 `ExecutionSummary`）：不是 SSE 事件，
   * 但属于同一次运行的用户可见结果，作为「同步台账」阶段追加在时间线末尾。
   */
  execution?: ExecutionSummary | null;
}

/** 一行时间线：阶段与轻重在构建时算好，渲染层不再做事件名判断 */
interface TimelineRow {
  key: string;
  event: string;
  data: unknown;
  /** 本地合成的行（如台账）没有发生时间，不渲染相对时间 */
  time: number | null;
  text: string;
  stage: ActivityStage;
  severity: ActivitySeverity;
  /** 与上一行同阶段时为 false：阶段标签只在阶段切换时出现，避免每行重复 */
  groupStart: boolean;
}

/** 每类事件的小图标（纯装饰，状态语义由文字、阶段标签与配色承载） */
const EVENT_ICON: Record<string, React.ReactNode> = {
  run: <RocketOutlined aria-hidden="true" />,
  start: <ApiOutlined aria-hidden="true" />,
  intent: <AimOutlined aria-hidden="true" />,
  planning: <CompassOutlined aria-hidden="true" />,
  replan: <SyncOutlined aria-hidden="true" />,
  tool_call: <ToolOutlined aria-hidden="true" />,
  tool_result: <CheckCircleOutlined aria-hidden="true" />,
  rag_result: <BookOutlined aria-hidden="true" />,
  plan_update: <DiffOutlined aria-hidden="true" />,
  confirmation: <PauseCircleOutlined aria-hidden="true" />,
  budget_degraded: <WarningOutlined aria-hidden="true" />,
  warning: <WarningOutlined aria-hidden="true" />,
  complete: <FlagOutlined aria-hidden="true" />,
  cancelled: <StopOutlined aria-hidden="true" />,
  error: <WarningOutlined aria-hidden="true" />,
  // Batch 15：任务 DAG 的委派 / 重派 / 冲突
  delegate: <ExportOutlined aria-hidden="true" />,
  recover: <RedoOutlined aria-hidden="true" />,
  conflict: <StopOutlined aria-hidden="true" />,
  ledger: <SyncOutlined aria-hidden="true" />,
};

/** 同步台账的模块名（与 ExecutionResultPanel 的展示口径一致） */
const LEDGER_MODULE_NAME: Record<string, string> = {
  trip: '行程',
  calendar: '日程',
  budget: '预算',
  footprint: '足迹',
};

/** 事件字段取文案：非空字符串才采用（缺失时由 stageText 的占位文案兜底） */
function field(value: unknown): string {
  return typeof value === 'string' && value ? value : '';
}

function recordOf(data: unknown): Record<string, unknown> | null {
  return data && typeof data === 'object' ? (data as Record<string, unknown>) : null;
}

/** 尝试次数：缺省或非法值一律按 0（首次）处理 */
function attemptOf(data: unknown): number {
  const attempt = recordOf(data)?.attempt;
  return typeof attempt === 'number' && attempt >= 0 ? attempt : 0;
}

/** rag_result 事件携带的 citations（P1）；旧后端只有 sources 时返回空数组 */
function eventCitations(data: unknown): AgentCitation[] {
  const citations = recordOf(data)?.citations;
  if (!Array.isArray(citations)) return [];
  return citations.filter((c: unknown): c is AgentCitation => !!c && typeof c === 'object');
}

/**
 * 敏感参数红线：工具调用只展示工具名与参数的「字段名列表」（Object.keys），
 * 绝不渲染参数值原文。字段名之外的任何内容都不进 DOM。
 */
function toolCallText(data: unknown): string {
  const d = recordOf(data);
  const tool = field(d?.tool);
  const args =
    d && typeof d.args === 'object' && d.args !== null && !Array.isArray(d.args)
      ? Object.keys(d.args)
      : [];
  const fields = args.length > 0 ? `（参数：${args.join('、')}）` : '';
  return `调用工具 ${tool}${fields}`;
}

/** 单个事件的用户可读文案；null 表示这一行不渲染（如 focus） */
function eventText(ev: ActivityEvent): string | null {
  const d = recordOf(ev.data);
  switch (ev.event) {
    case 'run':
      return `本轮运行 ${shortRunId(d?.runId)}`;
    case 'tool_call':
      return toolCallText(ev.data);
    case 'tool_result': {
      const tool = field(d?.tool);
      if (d?.success === false) return `工具 ${tool} 执行失败`;
      // 后端把 null 结果序列化成 "null"：工具没拿到数据，和「调用成功」不是一回事
      if (d?.result === null || d?.result === 'null') return `工具 ${tool} 未返回数据`;
      return `工具 ${tool} 执行完成`;
    }
    case 'complete': {
      // complete 的载荷是 AgentResult：结论以 status 为准，不统一写成「完成」
      const status = field(d?.status);
      if (status === 'FAILED') return '本轮未完成（失败）';
      if (status === 'CANCELLED') return '本轮已取消';
      if (status === 'PARTIAL_SUCCESS') return '本轮部分完成';
      if (status === 'NEED_CONFIRMATION') return '已生成方案，等待你确认';
      return '本轮运行完成';
    }
    case 'error':
      return field(d?.message) || 'Agent 执行出错';
    // 预算降级：后端已把原因写进 detail（哪个阶段被跳过、是时间还是花费触顶）
    case 'budget_degraded':
      return field(d?.detail) || '预算不足，部分阶段被跳过（原因未上报）';
    // SSE error 转成的过程警告（本轮继续，但已经缺了一块）
    case 'warning':
      return field(d?.message) || '有环节未按完整路径完成';
    case 'cancelled':
      return '本轮已停止（未生成最终结果）';
    case 'resume_timeout':
      return '连接中断且运行超时，可稍后查看运行记录';
    // Batch 15：任务 DAG 的委派 / 重派 / 冲突；字段缺失时退回 stageText 的占位文案
    case 'delegate': {
      const receiver = field(d?.receiver);
      const taskType = field(d?.taskType);
      return receiver && taskType ? `委派 ${receiver}：${taskType}` : stageText(ev.event, ev.data);
    }
    case 'recover': {
      const taskType = field(d?.taskType);
      return taskType
        ? `重派 ${taskType}（第 ${attemptOf(ev.data) + 1} 次尝试）`
        : stageText(ev.event, ev.data);
    }
    case 'conflict': {
      const receiver = field(d?.receiver);
      const taskType = field(d?.taskType);
      return receiver && taskType
        ? `任务冲突：${receiver}/${taskType} 前置未完成，已回收`
        : stageText(ev.event, ev.data);
    }
    default:
      // 其余事件复用既有 STAGE_TEXT 映射；未映射的事件（如 focus）不单独成行
      return stageText(ev.event, ev.data);
  }
}

/** 同步台账 → 一行摘要：成功的模块点名，失败的给出原因与「可重试」 */
function ledgerText(execution: ExecutionSummary): string {
  if (execution.modules.length === 0) {
    return '同步台账：未返回模块结果';
  }
  const parts = execution.modules.map(
    (m) =>
      `${LEDGER_MODULE_NAME[m.module] ?? m.module} ${m.status === 'FAILED' ? '失败' : '已同步'}`,
  );
  const failed = execution.modules.filter((m) => m.status === 'FAILED');
  if (failed.length === 0) {
    return `同步台账：${parts.join('、')}`;
  }
  const reasons = failed
    .map((m) => `${LEDGER_MODULE_NAME[m.module] ?? m.module}：${field(m.error) || '未返回原因'}`)
    .join('；');
  return `同步台账：${parts.join('、')}；失败 ${reasons}，可在下方重试`;
}

function ledgerSeverity(execution: ExecutionSummary): ActivitySeverity {
  if (execution.status === 'SUCCEEDED') return 'info';
  if (execution.status === 'PARTIAL_FAILED') return 'warn';
  return 'error';
}

/** 把 activity（+ 同步台账）投影成时间线行 */
function buildRows(activity: ActivityEvent[], execution?: ExecutionSummary | null): TimelineRow[] {
  const rows: TimelineRow[] = [];
  activity.forEach((ev, index) => {
    const text = eventText(ev);
    const stage = activityStage(ev.event);
    // 没有文案或没有阶段的事件不占一行（例如 focus 只驱动地图）
    if (text == null || stage == null) return;
    rows.push({
      key: `${ev.time}-${index}`,
      event: ev.event,
      data: ev.data,
      time: ev.time,
      text,
      stage,
      severity: activitySeverity(ev.event, ev.data),
      groupStart: false,
    });
  });
  if (execution) {
    rows.push({
      key: 'ledger',
      event: 'ledger',
      data: execution,
      time: null,
      text: ledgerText(execution),
      stage: 'ledger',
      severity: ledgerSeverity(execution),
      groupStart: false,
    });
  }
  return rows.map((row, index) => ({
    ...row,
    groupStart: index === 0 || rows[index - 1].stage !== row.stage,
  }));
}

/**
 * 降级提示：折叠态也必须能看出「这次走了降级路径、为什么」。
 * 只把**降级阶段**的行提上来；冲突/重派有自己的阶段，不混进这句结论。
 */
function degradeNotice(rows: TimelineRow[]): { tone: string; text: string } | null {
  const degrade = rows.filter((r) => r.stage === 'degrade');
  if (degrade.length === 0) return null;
  const first = degrade[0];
  const extra = degrade.length > 1 ? `（共 ${degrade.length} 项）` : '';
  if (first.event === 'budget_degraded') {
    return { tone: 'warn', text: `本次结果走了降级路径：${first.text}${extra}` };
  }
  if (first.event === 'warning') {
    return { tone: 'warn', text: `本轮有过程警告：${first.text}${extra}` };
  }
  return { tone: 'warn', text: `本轮有环节未按完整路径完成：${first.text}${extra}` };
}

/** 相对时间：刚刚 / N 秒前 / N 分钟前，超过 1 小时回退为时钟时间 */
function relativeTime(time: number): string {
  const diff = Date.now() - time;
  if (diff < 5000) return '刚刚';
  if (diff < 60000) return `${Math.floor(diff / 1000)} 秒前`;
  if (diff < 3600000) return `${Math.floor(diff / 60000)} 分钟前`;
  return new Date(time).toLocaleTimeString('zh-CN', { hour12: false });
}

/**
 * Agent 过程时间线（V2.2 §9.1 过程可视化）：消费 agentStore.activity，
 * 把 SSE 事件按**用户能理解的阶段**聚合（准备 / 研究检索 / 工具调用 / 规划 /
 * 冲突重派 / 降级 / 确认 / 同步台账 / 收尾），并在折叠态给出降级结论。
 *
 * activity 为空时整块不渲染，ChatPanel 退化为既有单行 stage 动画。
 */
export default function AgentActivityPanel({ activity, execution }: Props) {
  // 默认折叠（§8.6）：过程默认只给「用户语言进度」，DAG / 工具调用放进「查看运行详情」
  const [open, setOpen] = useState(false);
  const listRef = useRef<HTMLDivElement>(null);

  const rows = buildRows(activity, execution);
  const notice = degradeNotice(rows);
  // 降级压过完成：预算降级的一轮不能显示成绿色「完成」
  const runState: RunState = execution
    ? mergeRunStates(activityRunState(rows), ledgerRunState(execution.status))
    : activityRunState(rows);

  // 事件密集时保持时间线滚到最新一行
  useEffect(() => {
    if (open) {
      listRef.current?.scrollTo({ top: listRef.current.scrollHeight });
    }
  }, [rows.length, open]);

  if (rows.length === 0) {
    return null;
  }

  return (
    <div className="agent-activity" data-testid="agent-activity-panel" data-run-state={runState}>
      <div className="aa-head">
        <span className="aa-title">Agent 过程</span>
        <RunStatus status={runState} size="sm" testId="agent-activity-status" />
        <span className="aa-count">{rows.length} 条</span>
        {/* 折叠时展示最新一条用户语言进度，避免「收起来就什么都看不见」 */}
        {!open && (
          <span
            className="aa-latest"
            data-testid="agent-activity-latest"
            style={{
              fontSize: 11,
              color: 'var(--ink-3)',
              minWidth: 0,
              flex: 1,
              overflow: 'hidden',
              textOverflow: 'ellipsis',
              whiteSpace: 'nowrap',
            }}
          >
            {rows[rows.length - 1].text}
          </span>
        )}
        <Button
          type="text"
          size="small"
          aria-label={open ? '收起运行详情' : '查看运行详情'}
          icon={open ? <UpOutlined /> : <DownOutlined />}
          onClick={() => setOpen((v) => !v)}
        >
          {open ? '收起' : '查看运行详情'}
        </Button>
      </div>
      {notice && (
        <div
          className={`aa-degrade ${notice.tone}`}
          data-testid="agent-activity-degrade"
          role="status"
        >
          <WarningOutlined aria-hidden="true" />
          <span className="aa-degrade-text">{notice.text}</span>
        </div>
      )}
      {open && (
        <div className="aa-list" ref={listRef}>
          {rows.map((r) => (
            <Fragment key={r.key}>
              <div
                className={`aa-row ${r.severity} ${r.groupStart ? 'stage-start' : ''}`.trim()}
                data-event={r.event}
                data-stage={r.stage}
                data-severity={r.severity}
              >
                <span className="aa-icon">{EVENT_ICON[r.event] ?? '•'}</span>
                {/* 阶段标签只在阶段切换时出现；降级行每次都给标签（降级必须显式） */}
                {(r.groupStart || r.stage === 'degrade') && (
                  <span className="aa-stage" data-stage={r.stage}>
                    {ACTIVITY_STAGE_META[r.stage].label}
                  </span>
                )}
                <span className="aa-text">{r.text}</span>
                {r.time != null && <span className="aa-time">{relativeTime(r.time)}</span>}
              </div>
              {r.event === 'rag_result' && (
                <div className="aa-citation-wrap">
                  <CitationList
                    citations={eventCitations(r.data)}
                    compact
                    testId="activity-citation-list"
                  />
                </div>
              )}
            </Fragment>
          ))}
        </div>
      )}
    </div>
  );
}
