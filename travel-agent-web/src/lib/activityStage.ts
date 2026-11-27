/**
 * Agent 过程时间线的**阶段语义**：把线上/本地的事件名聚合成用户能理解的几个阶段，
 * 并给出「这一行有多严重」与「整条时间线该显示什么运行状态」。
 *
 * 边界（为什么不是一事件一标签）：
 * - SSE 事件名是传输层的粒度（`tool_call`、`delegate`、`budget_degraded` …），用户不按这个粒度理解；
 * - 阶段是**用户可感知的动作**：准备 → 研究检索 → 工具调用 → 规划 → 冲突/重派 → 降级 → 确认 → 同步台账 → 收尾。
 *   一个阶段覆盖多个事件，新增传输事件时先问「用户会在哪个阶段看到它」，找不到阶段就不该进时间线。
 *
 * 数据来源以代码为准，不照抄文档转述：
 * - SSE 事件名：`src/api/agent.ts` 的 `EVENT_NAMES` + 续传端点的 `resume_timeout`；
 * - 本地合成事件：`warning`（SSE `error` 转成的过程警告）、`cancelled`（`agentStore.abort()`），
 *   两者不是线上事件名，但会进入同一个 activity 数组，必须一起映射；
 * - 同步台账不是 SSE 事件：确认提交后由 `ExecutionSummary` 驱动，阶段由调用方显式传入。
 *
 * 本文件是纯函数，不依赖 React、状态库或网络。
 */

import { toRunState, type RunState } from './runState';

/** 用户可感知的阶段（封闭集合；新增阶段要同步 `docs/guide/frontend.md` 的阶段表） */
export type ActivityStage =
  | 'prepare'
  | 'research'
  | 'tool'
  | 'plan'
  | 'dispatch'
  | 'degrade'
  | 'confirm'
  | 'ledger'
  | 'finish';

/** 单行的语义轻重：只表达轻重，不落具体色值（颜色由 tokens.css 决定） */
export type ActivitySeverity = 'info' | 'warn' | 'error' | 'muted';

export interface ActivityStageMeta {
  /** 时间线上的阶段标签 */
  label: string;
  /** 这个阶段在做什么（给展开态与排障用，不直接渲染成长文案） */
  hint: string;
}

export const ACTIVITY_STAGE_META: Record<ActivityStage, ActivityStageMeta> = {
  prepare: { label: '准备', hint: '建立连接、理解你的需求' },
  research: { label: '研究检索', hint: '检索攻略与知识库证据' },
  tool: { label: '工具调用', hint: '调用只读工具查天气 / 路线 / 城市数据' },
  plan: { label: '规划', hint: '编排行程、生成方案对比、审核未通过后重规划' },
  dispatch: { label: '冲突/重派', hint: '前置未完成被拒后回收、任务被重派' },
  degrade: { label: '降级', hint: '预算或外部依赖不可用，部分阶段被跳过' },
  confirm: { label: '确认', hint: '等待你确认方案' },
  ledger: { label: '同步台账', hint: '确认后写入日程 / 预算 / 足迹的结果' },
  finish: { label: '收尾', hint: '本轮结束' },
};

/** 时间线行的最小输入：只要求事件名与载荷 */
export interface ActivityRowInput {
  event: string;
  data?: unknown;
}

/**
 * 事件 → 阶段。返回 null 表示「这个事件不单独成行」（如 `focus`，它只驱动地图定位）。
 *
 * `warning` / `cancelled` 是本地合成的事件名（见文件头），线上 SSE 不发送它们。
 * `ledger` 不是事件名，由调用方在同步台账行上显式指定阶段。
 */
export function activityStage(event: string): ActivityStage | null {
  switch (event) {
    case 'run':
    case 'start':
    case 'intent':
      return 'prepare';
    case 'rag_result':
      return 'research';
    case 'tool_call':
    case 'tool_result':
      return 'tool';
    case 'planning':
    case 'replan':
    case 'plan_update':
      return 'plan';
    // 委派是规划/执行编排的一段（PREVIEW / PLAN / REVIEW 子任务），不是「冲突」；
    // 把它归进冲突/重派会让正常的「委派 executor-agent：PREVIEW」被读成出了问题。
    case 'delegate':
      return 'plan';
    case 'recover':
    case 'conflict':
      return 'dispatch';
    // 预算降级 / 过程警告 / 续传超时：用户看到的是「这次走了降级或不完整路径」，与普通成功分开
    case 'budget_degraded':
    case 'warning':
    case 'resume_timeout':
      return 'degrade';
    case 'confirmation':
      return 'confirm';
    // 终止错误与用户停止都属于「本轮结束」；失败本身由行文案与状态色表达，不另设阶段
    case 'complete':
    case 'cancelled':
    case 'error':
      return 'finish';
    default:
      // focus 等不渲染成行的事件；未来新增的线上事件也走这里（不猜它的用户语义）
      return null;
  }
}

/** 载荷取值：非空字符串才采用（与服务端字段缺失时的兜底一致） */
function textOf(value: unknown): string {
  return typeof value === 'string' ? value : '';
}

/** 取对象载荷里的一个字段；非对象一律当缺省 */
function fieldOf(data: unknown, key: string): unknown {
  if (typeof data !== 'object' || data === null || !(key in data)) {
    return undefined;
  }
  return (data as Record<string, unknown>)[key];
}

/** 工具结果「没有数据」：后端把 null 结果序列化成字符串 "null"（见 ToolInvoker） */
function toolResultMissing(data: unknown): boolean {
  const result = fieldOf(data, 'result');
  return result === null || result === 'null';
}

/** 工具结果失败：契约上 success=false 才是否定；缺字段按成功（与旧文案一致） */
function toolFailed(data: unknown): boolean {
  return fieldOf(data, 'success') === false;
}

/**
 * 单行的语义轻重。`error` 事件在 activity 里只由 store 的**终止**错误写入
 * （SSE 过程警告走 `warning`），因此这里是 error 档而不是 warn。
 */
export function activitySeverity(event: string, data?: unknown): ActivitySeverity {
  switch (event) {
    case 'error':
    case 'conflict':
      return 'error';
    case 'warning':
    case 'budget_degraded':
    case 'resume_timeout':
    case 'recover':
      return 'warn';
    case 'cancelled':
      return 'muted';
    case 'tool_result':
      return toolFailed(data) || toolResultMissing(data) ? 'warn' : 'info';
    case 'complete': {
      const status = textOf(fieldOf(data, 'status'));
      if (status === 'FAILED') return 'error';
      if (status === 'PARTIAL_SUCCESS') return 'warn';
      if (status === 'CANCELLED') return 'muted';
      return 'info';
    }
    default:
      return 'info';
  }
}

/** 单个事件对运行状态的贡献 */
function rowRunState(row: ActivityRowInput): RunState {
  switch (row.event) {
    case 'budget_degraded':
    case 'warning':
    case 'conflict':
    case 'resume_timeout':
      return 'degraded';
    case 'tool_result':
      return toolFailed(row.data) || toolResultMissing(row.data) ? 'degraded' : 'loading';
    // activity 里的 error 只有 store 的终止错误（过程警告走 warning）
    case 'error':
      return 'failed';
    case 'cancelled':
      return 'cancelled';
    case 'complete': {
      // complete 的加载即 AgentResult.status：它比「收到 complete」更权威
      const status = textOf(fieldOf(row.data, 'status'));
      if (status === 'FAILED') return 'failed';
      if (status === 'CANCELLED') return 'cancelled';
      if (status === 'PARTIAL_SUCCESS') return 'partial';
      return 'complete';
    }
    default: {
      // 其余已登记事件复用统一映射表（loading / degraded）；未登记事件按「无贡献」处理
      const mapping = toRunState('sse', row.event);
      return mapping.state === 'unknown' ? 'loading' : mapping.state;
    }
  }
}

/**
 * 状态合成优先级：越靠后越「重」。
 * 降级必须压过完成——预算降级的一轮**不许**显示成绿色「完成」。
 */
const STATE_RANK: Record<RunState, number> = {
  // empty 不来自过程事件（调用方只在没有内容时用），合成时按最轻处理
  empty: 0,
  loading: 0,
  complete: 1,
  degraded: 2,
  partial: 3,
  cancelled: 4,
  failed: 5,
};

/** 合成多个状态：取最重的那个（失败 > 取消 > 部分成功 > 降级 > 完成 > 进行中） */
export function mergeRunStates(...states: RunState[]): RunState {
  return states.reduce<RunState>(
    (acc, state) => (STATE_RANK[state] > STATE_RANK[acc] ? state : acc),
    'loading',
  );
}

/** 整条时间线的运行状态（同步台账行由调用方另行合成，见 `ledgerRunState`） */
export function activityRunState(rows: readonly ActivityRowInput[]): RunState {
  return rows.reduce<RunState>((acc, row) => mergeRunStates(acc, rowRunState(row)), 'loading');
}

/**
 * 同步台账（`ExecutionSummary.status`）→ 七态。
 *
 * 与运行状态分开是因为它发生在确认提交之后、由另一个接口驱动；
 * 未知取值按「结果可能不完整」处理，绝不默认成功。
 */
export function ledgerRunState(status: string): RunState {
  switch (status) {
    case 'SUCCEEDED':
      return 'complete';
    case 'PARTIAL_FAILED':
      return 'partial';
    case 'FAILED':
      return 'failed';
    default:
      return 'degraded';
  }
}
