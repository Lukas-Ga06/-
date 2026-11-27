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

/**
 * 方案的知识依据（P1）：与知识库检索响应同构，可追溯文档 / 版本 / 切块 / 相似度。
 * 空数组表示方案没有引用知识库，不代表「没有依据」；前端不得编造来源。
 */
export interface AgentCitation {
  documentId?: number | null;
  version?: number | null;
  chunkId?: number | null;
  score?: number | null;
  source?: string | null;
  title?: string | null;
  snippet?: string | null;
  label?: string | null;
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
  /**
   * 需求槽位追问（Batch 14 / FR-01）：非空表示本轮没有规划，而是在问用户补齐
   * destination / days / partySize。旧后端不返回该字段。
   */
  missingSlots?: string[] | null;
  warnings?: string[] | null;
  /** 确认提交后的日程/预算/足迹同步执行结果（V2.2 §9.1） */
  execution?: ExecutionSummary | null;
  /** 方案的知识依据（P1）；旧后端缺失时为空数组 */
  citations?: AgentCitation[] | null;
}

/* ============================================================
   V2.2 运行追踪与执行同步（后端契约已冻结）
   ============================================================ */

/**
 * 运行总状态。
 * PARTIAL_SUCCESS（部分完成）表示本轮正常收尾但有阶段失败/降级、过程警告或预算降级，
 * 结论可能不完整——界面上不能与 SUCCEEDED 共用同一个绿色标签（P0-07）。
 *
 * ABANDONED（结果未知）是心跳超时/进程重启后执行体已不存在、本轮没有收尾的状态。
 * 后端刻意把它与 FAILED、CANCELLED 分开（`AgentRun.java:51`）：它不是失败报告，
 * 也不是用户取消，前端不得归入这两档；显示口径见 `lib/runState.ts`（degraded）。
 */
export type AgentRunStatus =
  'RUNNING' | 'SUCCEEDED' | 'PARTIAL_SUCCESS' | 'FAILED' | 'CANCELLED' | 'ABANDONED';

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
  | 'tool-invoker'
  /** 预算守卫：时间或花费预算耗尽时落一条 DEGRADE 任务（后端契约 AgentEngine.BUDGET_TASK_TYPE） */
  | 'budget-guard';

/**
 * REPLAN 是 V2.2 FR-06 新增的任务类型：审核发现 HIGH 问题后的一次有限重规划。
 * DEGRADE 是预算降级的留痕：summary 写明哪个阶段因哪种预算（时间 / 花费）被跳过。
 */
export type TaskType =
  'INTENT' | 'RESEARCH' | 'PLAN' | 'PREVIEW' | 'REVIEW' | 'REPLAN' | 'TOOL' | 'COMMIT' | 'DEGRADE';

/** CONFLICT（Batch 15）：前置任务未完成，本次派发被拒绝（不执行任务体） */
export type TaskViewStatus = 'RUNNING' | 'SUCCEEDED' | 'FAILED' | 'CONFLICT';

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
  /** 前置任务 id 列表（Batch 15）；旧数据/无前置为 [] 或缺失 */
  dependsOn?: string[];
  /** 第几次尝试：0 = 首次，>0 = 重派（Batch 15）；旧数据缺失 */
  attempt?: number;
  /** 本次替换掉的任务 id（失败/冲突/旧版本）；首次为 null（Batch 15） */
  replacesTaskId?: string | null;
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

/**
 * 单轮运行的花费快照（V29 起后端记录；字段与 OpenAPI 的 SpendView 一一对齐）。
 *
 * 缺席语义：V29 之前的 run 没有这条记录，`spend` 整个缺省——缺省不等于「零花费」，
 * 调用方不得把它补成 0，也不该渲染成空壳。
 */
export interface SpendView {
  /** 输出 token 数（后端 int64） */
  completionTokens?: number;
  /** 美元金额，后端存 6 位小数；0 通常表示未配置单价，不代表「免费」 */
  costUsd?: number;
  /**
   * 触顶的预算项 tag：llm_calls / tool_calls / retries / tokens / cost；
   * 未触顶时后端不返回该字段（与 OpenAPI 契约一致，故不声明 null）。
   */
  exhausted?: string;
  /** LLM 调用次数（后端 int32） */
  llmCalls?: number;
  /** 输入 token 数（后端 int64） */
  promptTokens?: number;
  /** 重试次数（后端 int32） */
  retries?: number;
  /** 工具调用次数（后端 int32） */
  toolCalls?: number;
  /** 合计 token 数（后端 int64） */
  totalTokens?: number;
}

/** GET /api/agent/runs/{runId} 响应（他人 run 返回 404 业务错误） */
export interface RunDetail {
  run: RunSummary;
  /** 本轮花费快照；V29 之前的老 run 缺省（不渲染花费区块） */
  spend?: SpendView | null;
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
  /** assistant 消息附带的知识依据；用户消息 / 无引用时不设置 */
  citations?: AgentCitation[] | null;
  /**
   * assistant 消息的结构化结果（阶段 C）：ChatPanel 据此渲染 ResultCard。
   * 失败与主动停止也会写入一个合成结果（status=FAILED / CANCELLED），让「停止/失败」
   * 与普通文本消息在界面上可区分。
   */
  result?: AgentResult | null;
}

export interface SelectedElement {
  type: string;
  id: number | null;
  title: string | null;
}
