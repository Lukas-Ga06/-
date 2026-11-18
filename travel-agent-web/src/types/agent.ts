export interface AgentFocus {
  type: string; // itinerary / map / budget
  tripId?: number | null;
  day?: number | null;
  itemId?: number | null;
  latitude?: number | null;
  longitude?: number | null;
}

export interface DiffChange {
  type: string; // ADD / REMOVE / MODIFY
  itemId?: number | null;
  itemType?: string | null;
  title?: string | null;
  detail?: string | null;
  cost?: number | null;
  dayIndex?: number | null;
  startTime?: string | null;
}

export interface PlanDiffDTO {
  changes: DiffChange[];
  budgetBefore: number | null;
  budgetAfter: number | null;
}

export interface BudgetSummaryDTO {
  total: number;
  previousTotal?: number | null;
  difference?: number | null;
  breakdown?: Record<string, number> | null;
}

export interface ConfirmationDTO {
  planId: string;
  summary: string;
  riskLevel?: string;
  /** 方案内容 SHA-256 指纹：确认时回传给后端绑定具体计划内容版本（V2.2 FR-08） */
  contentHash?: string;
  /** 存在未解决 HIGH 问题时为 true：前端必须要求勾选「接受风险」后才能提交（Batch 13） */
  requiresAcknowledgement?: boolean;
}

/** 审核问题（Batch 13）：type 见契约 BUDGET_OVERRUN / TRANSFER_TIME / WEATHER_OUTDOOR / TIME_CONFLICT */
export interface PlanReviewIssue {
  type: string;
  /** HIGH / MEDIUM */
  severity: string;
  message: string;
}

/** ReflectAgent 的审核结论：最终是否有 HIGH 问题、重规划次数与自动修复动作 */
export interface PlanReviewDTO {
  /** 最终是否仍存在 HIGH 级问题 */
  passed: boolean;
  /** true = 存在未解决 HIGH 问题，确认必须显式接受风险 */
  blocked?: boolean;
  /** 已执行的重规划次数（0 表示一次也没重排） */
  attempts?: number;
  /** 最终一轮审核的问题清单（passed=true 时也可能有 MEDIUM） */
  issues?: PlanReviewIssue[] | null;
  /** 自动修复动作（人话，可直接展示） */
  repairs?: string[] | null;
}

export interface AgentResult {
  planId?: string | null;
  tripId?: number | null;
  message: string;
  intent?: string | null;
  status: string; // COMPLETED / NEED_CONFIRMATION / FAILED
  focus?: AgentFocus | null;
  diff?: PlanDiffDTO | null;
  budget?: BudgetSummaryDTO | null;
  confirmation?: ConfirmationDTO | null;
  /** 审核结论（Batch 13）；缺失表示旧后端或本次无需审核 */
  review?: PlanReviewDTO | null;
  warnings?: string[] | null;
  /** 确认提交后的日程/预算/足迹同步执行结果（V2.2 §9.1） */
  execution?: ExecutionSummary | null;
}

/* ============================================================
   V2.2 运行追踪与执行同步（后端契约已冻结）
   ============================================================ */

export type AgentRunStatus = 'RUNNING' | 'SUCCEEDED' | 'FAILED' | 'CANCELLED';

/** GET /api/agent/runs 列表项 */
export interface RunSummary {
  runId: string;
  tripId?: number | null;
  message?: string | null;
  intent?: string | null;
  status: AgentRunStatus;
  error?: string | null;
  startedAt: string;
  endedAt?: string | null;
  durationMs?: number | null;
}

/** 任务归属的 Agent 角色 */
export type TaskReceiver =
  | 'intent-agent'
  | 'research-agent'
  | 'planner-agent'
  | 'executor-agent'
  | 'reflect-agent'
  | 'tool-invoker';

/** REPLAN 是 V2.2 FR-06 新增的任务类型：审核发现 HIGH 问题后的一次有限重规划 */
export type TaskType = 'INTENT' | 'RESEARCH' | 'PLAN' | 'PREVIEW' | 'REVIEW' | 'REPLAN' | 'TOOL' | 'COMMIT';

export type TaskViewStatus = 'RUNNING' | 'SUCCEEDED' | 'FAILED';

/** GET /api/agent/runs/{runId} 的任务链节点 */
export interface TaskView {
  taskId: string;
  receiver: TaskReceiver;
  taskType: TaskType;
  status: TaskViewStatus;
  summary?: string | null;
  error?: string | null;
  startedAt: string;
  endedAt?: string | null;
  durationMs?: number | null;
}

/** 运行内的一次工具调用（摘要已由后端脱敏为 digest） */
export interface ToolCallView {
  id: number;
  taskId?: string | null;
  toolName: string;
  success: boolean;
  errorCode?: string | null;
  latencyMs?: number | null;
  argsDigest?: string | null;
  resultDigest?: string | null;
  startedAt: string;
}

/** GET /api/agent/runs/{runId} 响应（他人 run 返回 404 业务错误） */
export interface RunDetail {
  run: RunSummary;
  tasks: TaskView[];
  toolCalls: ToolCallView[];
}

/** 同步模块：calendar=日程时间块 / budget=计划预算快照（不会自动记账）/ footprint=足迹计划标记 */
export type ExecutionModuleKind = 'calendar' | 'budget' | 'footprint';

export type ExecutionModuleStatus = 'SUCCEEDED' | 'FAILED';

export type ExecutionStatus = 'SUCCEEDED' | 'PARTIAL_FAILED' | 'FAILED';

export interface ExecutionModule {
  module: ExecutionModuleKind;
  status: ExecutionModuleStatus;
  detail?: string | null;
  idempotencyKey?: string | null;
  retryCount?: number | null;
  error?: string | null;
}

/** 确认提交后的模块级同步结果（GET /api/agent/executions/{id} 同构） */
export interface ExecutionSummary {
  executionId: string;
  planId: string;
  tripId?: number | null;
  status: ExecutionStatus;
  modules: ExecutionModule[];
}

export interface ChatMessage {
  id: string;
  role: 'user' | 'assistant';
  content: string;
  time: string;
}

export interface SelectedElement {
  type: string;
  id: number | null;
  title: string | null;
}
