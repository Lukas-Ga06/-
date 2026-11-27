/**
 * 统一运行状态映射：后端运行状态、前端资源态、SSE 事件 → 七态 `RunState`。
 *
 * 七态口径与 `components/RunStatus` 一致：loading / empty / degraded / partial /
 * failed / cancelled / complete。`unknown` 不是第八种用户状态，而是映射层对**未登记
 * 取值**的显式兜底：保留来源与原始值供排查，不抛异常，也绝不静默归到 complete。
 *
 * 来源取值以代码为准，不照抄文档转述：
 * - `AgentRun.status`：`travel-agent-server/.../entity/AgentRun.java`（6 个，含
 *   `ABANDONED`；前端 `types/agent.ts` 的 `AgentRunStatus` 已与之对齐）
 * - `ResourceState.status`：`src/lib/resource.ts`（实际 6 个）
 * - SSE 事件名：`src/api/agent.ts` 的 `EVENT_NAMES`（17 个）+ 后端会发但 GET 通道
 *   未注册的 `budget_degraded` + 仅续传端点的 `resume_timeout`
 *
 * 本文件是纯函数，不依赖 React、状态库或网络。
 */

import type { AgentRunStatus } from '../types/agent';
import type { ResourceState } from './resource';

/** 用户可见的七种运行状态（与 `components/RunStatus` 的七态同名） */
export type RunState =
  'loading' | 'empty' | 'degraded' | 'partial' | 'failed' | 'cancelled' | 'complete';

/** 映射结果的来源标识，用于排查「这个状态是谁给的」 */
export type RunStateSource = 'agent-run' | 'resource' | 'sse';

/** 语义色档位：只表达轻重，不落具体色值 */
export type RunStateTone = 'ok' | 'warn' | 'danger' | 'muted' | 'info';

export interface RunStateMeta {
  /** 用户可见主文案 */
  label: string;
  /** 给用户看的下一步操作提示 */
  hint: string;
  tone: RunStateTone;
}

/** 七态 + 映射层兜底；`unknown` 不出现在任何页面状态的视觉规范里 */
export type RunStateOrUnknown = RunState | 'unknown';

interface KnownRunStateMapping {
  state: RunState;
  source: RunStateSource;
  /** 来源原始取值，已知值也保留，便于时间线与日志对齐 */
  raw: string;
}

interface UnknownRunStateMapping {
  state: 'unknown';
  source: RunStateSource;
  /** 未登记的原始取值；对象/数字按 String() 归一，便于展示与上报 */
  raw: string;
}

export type RunStateMapping = KnownRunStateMapping | UnknownRunStateMapping;

export const RUN_STATE_META: Record<RunStateOrUnknown, RunStateMeta> = {
  loading: {
    label: '进行中',
    hint: '本轮仍在执行；可展开过程查看进度，长时间没有变化再重试。',
    tone: 'info',
  },
  empty: {
    label: '暂无内容',
    hint: '还没有可展示的结果；发起一次对话或操作后这里会出现。',
    tone: 'muted',
  },
  degraded: {
    label: '已降级',
    hint: '部分能力被跳过或结果可能不完整；可查看运行详情，必要时重试。',
    tone: 'warn',
  },
  partial: {
    label: '部分成功',
    hint: '有些部分没有完成；先查看运行详情，确认缺了什么再决定是否重试。',
    tone: 'warn',
  },
  failed: {
    label: '失败',
    hint: '本轮没有完成；可以重试，反复失败时打开运行记录或系统诊断。',
    tone: 'danger',
  },
  cancelled: {
    label: '已取消',
    hint: '本轮已停止，没有继续执行；需要时重新发起即可。',
    tone: 'muted',
  },
  complete: {
    label: '完成',
    hint: '本轮已正常收尾；可以查看结果或继续下一步。',
    tone: 'ok',
  },
  unknown: {
    label: '状态未知',
    hint: '无法识别该状态；请刷新重试，仍然异常时查看运行记录或联系支持。',
    tone: 'muted',
  },
};

/** 后端 `AgentRun.status` 的真实取值；前端 `AgentRunStatus` 已补齐，缺 key 会由 tsc 挡住 */
type AgentRunStatusValue = AgentRunStatus;

const AGENT_RUN_STATES: Record<AgentRunStatusValue, RunState> = {
  RUNNING: 'loading',
  SUCCEEDED: 'complete',
  PARTIAL_SUCCESS: 'partial',
  FAILED: 'failed',
  CANCELLED: 'cancelled',
  // 心跳超时/进程重启，执行体已不存在、结果未知；与 FAILED 区分，按「结果可能不完整」处理
  ABANDONED: 'degraded',
};

type ResourceStatusValue = ResourceState<unknown>['status'];

const RESOURCE_STATES: Record<ResourceStatusValue, RunState> = {
  idle: 'empty',
  loading: 'loading',
  success: 'complete',
  empty: 'empty',
  error: 'failed',
  'offline-stale': 'degraded',
};

type SseEventName =
  | 'run'
  | 'start'
  | 'intent'
  | 'planning'
  | 'replan'
  | 'delegate'
  | 'recover'
  | 'conflict'
  | 'tool_call'
  | 'tool_result'
  | 'rag_result'
  | 'plan_update'
  | 'focus'
  | 'confirmation'
  | 'budget_degraded'
  | 'complete'
  | 'cancelled'
  | 'error'
  | 'resume_timeout';

const SSE_EVENT_STATES: Record<SseEventName, RunState> = {
  run: 'loading',
  start: 'loading',
  intent: 'loading',
  planning: 'loading',
  replan: 'loading',
  delegate: 'loading',
  recover: 'loading',
  // 前置未完成、任务被拒后回收；本轮继续，但过程已出现缺口
  conflict: 'degraded',
  tool_call: 'loading',
  tool_result: 'loading',
  rag_result: 'loading',
  plan_update: 'loading',
  focus: 'loading',
  // 七态没有独立「待确认」档；等待用户操作期间仍算本轮未收尾
  confirmation: 'loading',
  budget_degraded: 'degraded',
  complete: 'complete',
  cancelled: 'cancelled',
  // 过程警告：服务端随后仍会下发 complete，不能当本轮失败
  error: 'degraded',
  // 仅续传端点：结束本次连接，服务端 run 可能仍在执行
  resume_timeout: 'degraded',
};

const AGENT_RUN_STATE_MAP = new Map<string, RunState>(Object.entries(AGENT_RUN_STATES));
const RESOURCE_STATE_MAP = new Map<string, RunState>(Object.entries(RESOURCE_STATES));
const SSE_EVENT_STATE_MAP = new Map<string, RunState>(Object.entries(SSE_EVENT_STATES));

/**
 * 把任一来源的原始取值映射到七态。
 *
 * 未登记取值返回 `{ state: 'unknown', source, raw }`——调用方必须显式处理 unknown，
 * 不允许把它当成功；函数对未知取值不抛异常。`source` 是封闭联合，传错属于调用方 bug，
 * 由 `assertNever` 大声失败。
 */
export function toRunState(source: RunStateSource, raw: unknown): RunStateMapping {
  const value = describeRaw(raw);
  switch (source) {
    case 'agent-run':
      return mappingFrom('agent-run', value, AGENT_RUN_STATE_MAP);
    case 'resource':
      return mappingFrom('resource', value, RESOURCE_STATE_MAP);
    case 'sse':
      return mappingFrom('sse', value, SSE_EVENT_STATE_MAP);
    default:
      return assertNever(source);
  }
}

/**
 * 读取「确认已登记」的映射结果：给 `ResourceState.status` 这类封闭联合用。
 *
 * `toRunState` 对未知取值返回 unknown 而不抛异常（那是给运行时数据兜底的）；
 * 封闭联合里出现未知值属于调用方 bug，这里大声失败，避免把 unknown 当七态往下传。
 */
export function knownRunState(source: RunStateSource, raw: string): RunState {
  const mapping = toRunState(source, raw);
  if (mapping.state === 'unknown') {
    throw new Error(`未登记的运行状态：${source}/${raw}`);
  }
  return mapping.state;
}

function mappingFrom(
  source: RunStateSource,
  raw: string,
  table: ReadonlyMap<string, RunState>,
): RunStateMapping {
  const state = table.get(raw);
  return state === undefined ? { state: 'unknown', source, raw } : { state, source, raw };
}

/** 原始值归一：字符串原样保留，其余值转成可展示、可上报的文本 */
function describeRaw(raw: unknown): string {
  if (typeof raw === 'string') {
    return raw;
  }
  if (raw === null) {
    return 'null';
  }
  if (raw === undefined) {
    return 'undefined';
  }
  return String(raw);
}

function assertNever(value: never): never {
  throw new Error(`未处理的 RunState 来源：${String(value)}`);
}
