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
import CitationList from '../CitationList';
import RunStatus, { type RunState } from '../RunStatus';
import type { AgentCitation } from '../../types/agent';

interface Props {
  activity: ActivityEvent[];
}

/** 每类事件的小图标（纯装饰，状态语义由文字与配色承载） */
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
  complete: <FlagOutlined aria-hidden="true" />,
  error: <WarningOutlined aria-hidden="true" />,
  // Batch 15：任务 DAG 的委派 / 重派 / 冲突
  delegate: <ExportOutlined aria-hidden="true" />,
  recover: <RedoOutlined aria-hidden="true" />,
  conflict: <StopOutlined aria-hidden="true" />,
};

/** 事件字段取文案：非空字符串才采用（缺失时由 stageText 的占位文案兜底） */
function field(value: unknown): string {
  return typeof value === 'string' && value ? value : '';
}

/** 尝试次数：缺省或非法值一律按 0（首次）处理 */
function attemptOf(data: any): number {
  return typeof data?.attempt === 'number' && data.attempt >= 0 ? data.attempt : 0;
}

/** rag_result 事件携带的 citations（P1）；旧后端只有 sources 时返回空数组 */
function eventCitations(data: any): AgentCitation[] {
  if (!data || !Array.isArray(data.citations)) return [];
  return data.citations.filter((c: unknown): c is AgentCitation => !!c && typeof c === 'object');
}

/**
 * 敏感参数红线：工具调用只展示工具名与参数的「字段名列表」（Object.keys），
 * 绝不渲染参数值原文。字段名之外的任何内容都不进 DOM。
 */
function toolCallText(data: any): string {
  const tool = typeof data?.tool === 'string' ? data.tool : '';
  const args =
    data && typeof data === 'object' && data.args && typeof data.args === 'object'
      ? Object.keys(data.args)
      : [];
  const fields = args.length > 0 ? `（参数：${args.join('、')}）` : '';
  return `调用工具 ${tool}${fields}`;
}

function eventText(ev: ActivityEvent): string | null {
  const d = ev.data;
  switch (ev.event) {
    case 'run':
      return `本轮运行 ${shortRunId(d?.runId)}`;
    case 'tool_call':
      return toolCallText(d);
    case 'tool_result': {
      const tool = typeof d?.tool === 'string' ? d.tool : '';
      return d?.success === false ? `工具 ${tool} 执行失败` : `工具 ${tool} 执行完成`;
    }
    case 'complete':
      return '本轮运行完成';
    case 'error':
      return typeof d?.message === 'string' && d.message ? d.message : 'Agent 执行出错';
    // Batch 15：任务 DAG 的委派 / 重派 / 冲突；字段缺失时退回 stageText 的占位文案
    case 'delegate': {
      const receiver = field(d?.receiver);
      const taskType = field(d?.taskType);
      return receiver && taskType ? `委派 ${receiver}：${taskType}` : stageText(ev.event, d);
    }
    case 'recover': {
      const taskType = field(d?.taskType);
      return taskType
        ? `重派 ${taskType}（第 ${attemptOf(d) + 1} 次尝试）`
        : stageText(ev.event, d);
    }
    case 'conflict': {
      const receiver = field(d?.receiver);
      const taskType = field(d?.taskType);
      return receiver && taskType
        ? `任务冲突：${receiver}/${taskType} 前置未完成，已回收`
        : stageText(ev.event, d);
    }
    default:
      // 其余事件复用既有 STAGE_TEXT 映射；未映射的事件（如 focus）不单独成行
      return stageText(ev.event, d);
  }
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
 * 渲染 run/intent/planning/replan/tool_call/tool_result/rag_result/plan_update/confirmation/complete/error，
 * 以及 Batch 15 任务 DAG 的 delegate/recover/conflict。
 * activity 为空时整块不渲染，ChatPanel 退化为既有单行 stage 动画。
 */
export default function AgentActivityPanel({ activity }: Props) {
  // 默认折叠（§8.6）：过程默认只给「用户语言进度」，DAG / 工具调用放进「查看运行详情」
  const [open, setOpen] = useState(false);
  const listRef = useRef<HTMLDivElement>(null);

  const rows = activity
    .map((ev) => ({ ev, text: eventText(ev) }))
    .filter((r): r is { ev: ActivityEvent; text: string } => r.text != null);

  // 过程状态：有 error/conflict 就是「有问题」，收到 complete 才是完成，否则仍在进行。
  // 用共享 RunStatus 输出图标 + 文字，不靠颜色单独表达。
  const runState: RunState = rows.some((r) => r.ev.event === 'error' || r.ev.event === 'conflict')
    ? 'degraded'
    : rows.some((r) => r.ev.event === 'complete')
      ? 'complete'
      : 'loading';

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
    <div className="agent-activity" data-testid="agent-activity-panel">
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
      {open && (
        <div className="aa-list" ref={listRef}>
          {rows.map((r, i) => (
            <Fragment key={`${r.ev.time}-${i}`}>
              <div
                className={`aa-row ${r.ev.event === 'error' || r.ev.event === 'conflict' ? 'error' : ''}`}
                data-event={r.ev.event}
              >
                <span className="aa-icon">{EVENT_ICON[r.ev.event] ?? '•'}</span>
                <span className="aa-text">{r.text}</span>
                <span className="aa-time">{relativeTime(r.ev.time)}</span>
              </div>
              {r.ev.event === 'rag_result' && (
                <div className="aa-citation-wrap">
                  <CitationList
                    citations={eventCitations(r.ev.data)}
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
