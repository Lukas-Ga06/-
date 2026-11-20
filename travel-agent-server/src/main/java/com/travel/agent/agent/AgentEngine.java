package com.travel.agent.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.travel.agent.dto.AgentDtos;
import com.travel.agent.entity.ConversationMessage;
import com.travel.agent.entity.ItineraryItem;
import com.travel.agent.entity.Trip;
import com.travel.agent.exception.BusinessException;
import com.travel.agent.model.WeatherInfo;
import com.travel.agent.observability.TravelMetrics;
import com.travel.agent.service.AgentCommandIdempotency;
import com.travel.agent.service.ConversationService;
import com.travel.agent.service.ItineraryService;
import com.travel.agent.service.PlanNarrationService;
import com.travel.agent.service.TripService;
import com.travel.agent.tool.AgentTool;
import com.travel.agent.tool.ToolInvoker;
import com.travel.agent.tool.ToolRegistry;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Agent Engine：RECEIVE → UNDERSTAND → PLAN → EXECUTE → REFLECT → PRESENT → CONFIRM → COMMIT。
 * 异常路径：EXECUTE → ERROR → 反馈用户。
 *
 * <p>本类只保留<b>状态机与编排</b>：取消边界、会话记忆、规划→预览→反思→待确认链路、
 * 外部工具调用；各类意图的具体答复与确认/撤销分别委托给：
 * <ul>
 *   <li>{@link AssistantIntentHandler}：旅行备忘 / 日程提醒；</li>
 *   <li>{@link TravelQueryHandler}：知识问答 / 预算 / 记账；</li>
 *   <li>{@link PlanConfirmationService}：确认 / 取消 / 取回 / 撤销；</li>
 *   <li>{@link AgentStageTimer}：阶段计时指标。</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AgentEngine {

    /**
     * 票据只读会话下禁止执行的"直接写入类"意图：这三类都会立刻落库
     * （账单 / 备忘 / 日程），而票据是 URL 凭证，泄漏面大，不能凭它代受害者写数据。
     *
     * <p>规划类（PLAN_TRIP / MODIFY_ITINERARY）保持可用：它们只产出待确认方案（pending_plan），
     * 真正提交仍需 POST /api/agent/confirm，而 POST 不接受票据。查询类本来就只读。
     */
    private static final Set<String> TICKET_READONLY_BLOCKED_INTENTS = Set.of(
            IntentResult.RECORD_EXPENSE, IntentResult.ADD_MEMO,
            IntentResult.ADD_SCHEDULE);

    /**
     * 幂等覆盖范围（P0-01）：与「直接写入类意图」完全一致 —— 记账 / 备忘 / 日程。
     * 规划类不在此列：它们只产出待确认方案，重复规划不会重复落库，且需要保留多轮改稿。
     */
    private static final Set<String> IDEMPOTENT_WRITE_INTENTS = Set.of(
            IntentResult.RECORD_EXPENSE, IntentResult.ADD_MEMO,
            IntentResult.ADD_SCHEDULE);

    /** 票据只读会话拒绝写意图时返回的文案（明确告知原因与替代路径，不当作错误处理） */
    private static final String TICKET_READONLY_MESSAGE =
            "当前连接为只读票据会话，记账/备忘/日程请在网页端操作。";

    /** SSE 断连计数：reason=complete|timeout|error（controller 记）＋ cancelled（引擎阶段边界短路记） */
    private static final String SSE_DISCONNECTS_METRIC = "sse.disconnects";

    /** 被取消的轮次在结果里的 intent 标记（只用于日志/单测识别，不随 SSE 事件下发） */
    public static final String CANCELLED_INTENT = "CANCELLED";

    /**
     * 需求追问标记（FR-01）：追问轮次的助手消息以这个 intent 落会话历史。
     * 下一轮据此判断「上一轮在等用户补槽位」，从而把短回答（「上海」「三天」）续到同一次规划里。
     */
    public static final String INTENT_NEED_INFO = "PLAN_TRIP_NEED_INFO";

    /**
     * 追问上限：三个必填槽位最多也就分三轮补齐；超过仍缺（用户一直在答非所补）就兜底，不再盘问。
     * 正常情况下真正起作用的是「本轮没补到新信息 → 兜底」。
     */
    static final int MAX_REQUIREMENT_ASKS = 3;

    /** 追问/兜底说明里「出发日期按明天」的展示格式 */
    private static final DateTimeFormatter SHORT_DATE = DateTimeFormatter.ofPattern("M月d日");

    // ==================== P1 端到端时间预算：降级留痕 ====================

    /** 预算降级追踪任务的接收方（/api/agent/runs/{runId} 的任务列表里可直接查到） */
    static final String BUDGET_TASK_RECEIVER = "budget-guard";
    /** 预算降级追踪任务类型：任务列表里一眼可见「本轮发生过预算降级」（对外契约，单测据此断言） */
    public static final String BUDGET_TASK_TYPE = "DEGRADE";
    /** 用户可见的预算降级提示前缀（幂等判断用：complete 与 finish 都会走到提示逻辑） */
    static final String BUDGET_NOTICE_PREFIX = "⏳ 时间预算";

    /** 降级阶段的用户可读文案：不写内部术语（review/REPLAN），说人话并点明「没做什么」 */
    private static final Map<String, String> BUDGET_STAGE_LABELS = Map.of(
            AgentBudget.STAGE_INTENT, "跳过 LLM 意图识别（改用规则引擎）",
            AgentBudget.STAGE_RESEARCH, "跳过研究取证",
            AgentBudget.STAGE_PLAN, "跳过 LLM 结构化规划（改用规则规划）",
            AgentBudget.STAGE_REVIEW, "跳过审核与重规划（未做路线/天气校验）",
            AgentBudget.STAGE_NARRATION, "跳过文案润色",
            AgentBudget.STAGE_TOOL, "未执行外部工具查询");

    private final IntentAgent intentAgent;
    private final PlannerAgent plannerAgent;
    private final ExecutorAgent executorAgent;
    private final PendingPlanStore pendingPlans;
    private final TripService tripService;
    private final ItineraryService itineraryService;
    private final ConversationService conversationService;
    private final PlanNarrationService planNarrationService;
    private final ToolRegistry toolRegistry;
    private final ToolInvoker toolInvoker;
    private final ObjectMapper objectMapper;
    private final AgentStageTimer stageTimer;
    private final AssistantIntentHandler assistantHandler;
    private final TravelQueryHandler travelQueryHandler;
    private final PlanConfirmationService planConfirmation;

    /** P0-01 写入幂等闸门：记账 / 备忘 / 日程按 (userId, requestId) 最多执行一次 */
    private final AgentCommandIdempotency idempotency;
    /** V2.2 追踪：run / 任务 / 工具调用落库（内部吞异常，绝不反噬主流程） */
    private final com.travel.agent.service.AgentTraceService traceService;
    /**
     * 任务图调度器（第十五批 FR-02/FR-04）：按依赖派发、并行、回收重派。
     * 依赖关系由引擎写死（确定性编排），模型只执行任务内容，不参与调度。
     */
    private final Coordinator coordinator;
    /** V2.2 FR-06：审核 → 有限重规划 → 再审的闭环（REPLAN 状态首次真正启用） */
    private final PlanReviewLoop planReviewLoop;
    /** 审核 Agent：第十五批起首轮审核由引擎作为任务图节点并行派出（重规划仍由 PlanReviewLoop 驱动） */
    private final ReflectAgent reflectAgent;
    /** 指标：sse.disconnects（注入方式与 StructuredPlanner、LlmClient 一致） */
    private final MeterRegistry meterRegistry;
    /** T5：规划结果指标 travel.plan.outcome（success|degraded|failed） */
    private final com.travel.agent.observability.TravelMetrics travelMetrics;

    public AgentDtos.AgentResult handle(AgentContext ctx) {
        ctx.emit("start", Map.of("sessionId", ctx.getSessionId()));
        ctx.setState(AgentState.UNDERSTAND);
        // SSE 异步执行器与渠道线程看不见 filter 设置的 ThreadLocal，这里按执行线程补上（LLM 按用户配额依赖它）
        if (ctx.getUsername() != null) {
            com.travel.agent.security.UserContext.set(ctx.getUsername());
        }
        try {
            // 排队期间客户端可能已经断开：连会话记忆都不必读写
            if (ctx.isCancelled()) {
                return aborted(ctx);
            }
            loadConversation(ctx);
            // V2.2 追踪：一次对话 = 一次 run；追踪不可用时 runId 为空，后续记录全部静默跳过
            com.travel.agent.entity.AgentRun run = traceService.startRun(
                    ctx.getUserId(), ctx.getTripId(), ctx.getMessage());
            if (run != null) {
                ctx.setRunId(run.getRunId());
                ctx.emit("run", Map.of("runId", run.getRunId()));
            }
            AgentDtos.AgentResult result = doHandle(ctx);
            finishRunRecord(ctx, result);
            // 预算降级必须可追踪：单独落一条 DEGRADE 任务（放在 run 收尾之后，不影响结果状态）
            recordBudgetTrace(ctx);
            return result;
        } finally {
            com.travel.agent.security.UserContext.clear();
            // 一次运行只计一次：阶段边界可能多次命中取消检查
            if (ctx.isCancelled()) {
                log.info("客户端已断开，本轮 Agent 取消: user={}", ctx.getUsername());
                meterRegistry.counter(SSE_DISCONNECTS_METRIC, "reason", "cancelled").increment();
                traceService.finishRun(ctx.getRunId(), null,
                        com.travel.agent.entity.AgentRun.STATUS_CANCELLED, "客户端断开连接");
            }
        }
    }

    /**
     * 运行收尾：按结果状态映射 run 状态（NEED_CONFIRMATION 也是成功——规划完成等确认）。
     *
     * <p>P0-07：「成功」不再是无条件绿色。本轮只要有过程警告、预算降级或失败/降级任务，
     * 总状态就是 {@link com.travel.agent.entity.AgentRun#STATUS_PARTIAL}，界面必须显示
     * 「部分完成 / 待核实」，让用户知道结论可能不完整。
     */
    private void finishRunRecord(AgentContext ctx, AgentDtos.AgentResult result) {
        if (ctx.getRunId() == null) {
            return;
        }
        String status = aggregateRunStatus(result, ctx.hasBudgetDegradations(),
                traceService.failedTaskCount(ctx.getRunId()));
        String error = result != null && AgentDtos.STATUS_FAILED.equals(result.getStatus())
                ? result.getMessage()
                : null;
        traceService.finishRun(ctx.getRunId(), result == null ? null : result.getIntent(), status, error);
    }

    /**
     * 总状态聚合（P0-07 的唯一判定点，包可见以便单测直接驱动）。
     *
     * <p>优先级：整体失败 → FAILED；本轮有过程警告 / 预算降级 / 失败任务 → PARTIAL_SUCCESS；
     * 其余才是 SUCCEEDED。任一必要模块出问题都不允许出现无条件的「成功」。
     */
    static String aggregateRunStatus(AgentDtos.AgentResult result, boolean budgetDegraded, int failedTasks) {
        if (result == null) {
            return com.travel.agent.entity.AgentRun.STATUS_FAILED;
        }
        if (AgentDtos.STATUS_FAILED.equals(result.getStatus())) {
            return com.travel.agent.entity.AgentRun.STATUS_FAILED;
        }
        boolean hasWarnings = result.getWarnings() != null && !result.getWarnings().isEmpty();
        if (hasWarnings || budgetDegraded || failedTasks > 0) {
            return com.travel.agent.entity.AgentRun.STATUS_PARTIAL;
        }
        return com.travel.agent.entity.AgentRun.STATUS_SUCCEEDED;
    }

    private AgentDtos.AgentResult doHandle(AgentContext ctx) {
        // 幂等抢占是否成功：异常路径必须释放，否则同一 requestId 会被永久挡住
        boolean idempotencyClaimed = false;
        try {
            Trip trip = ctx.getTripId() != null ? tripService.getOwned(ctx.getTripId(), ctx.getUserId()) : null;
            if (abortIfCancelled(ctx, "before-intent")) {
                return aborted(ctx);
            }
            Coordinator.Receipt<IntentResult> intentReceipt = timedIntent(ctx, trip);
            IntentResult intent = intentReceipt.value();
            // FR-01：上一轮是需求追问时，「上海」「三天」这类短回答续到同一次规划里，
            // 而不是落进 CHITCHAT 把追问状态丢掉（改问别的意图则不劫持，按新意图处理）
            intent = resumeRequirementCollection(ctx, intent);
            if (abortIfCancelled(ctx, "after-intent")) {
                return aborted(ctx);
            }
            ctx.emit("intent", intent);
            log.info("Intent: {} target={} user={} trip={}", intent.getIntent(), intent.getTarget(),
                    ctx.getUsername(), ctx.getTripId());

            // 只读票据会话：写意图不执行，但保持正常结束（前端不需要为票据链路单独处理错误）
            if (ctx.isTicketOnly() && TICKET_READONLY_BLOCKED_INTENTS.contains(intent.getIntent())) {
                log.info("只读票据会话拒绝写意图: intent={} user={}", intent.getIntent(), ctx.getUsername());
                return finish(ctx, complete(ctx, AgentDtos.AgentResult.builder()
                        .intent(intent.getIntent())
                        .status(AgentDtos.STATUS_COMPLETED)
                        .message(TICKET_READONLY_MESSAGE)
                        .warnings(new ArrayList<>())
                        .build()), intent.getIntent());
            }

            // P0-01 幂等闸门：写入类意图按 (userId, requestId) 最多执行一次。
            // claim 在业务写入之前落库，重放请求看到已完成行就直接返回首次结果，绝不再写一遍。
            boolean idempotentWrite = ctx.getRequestId() != null
                    && IDEMPOTENT_WRITE_INTENTS.contains(intent.getIntent());
            if (idempotentWrite) {
                AgentCommandIdempotency.Outcome outcome = idempotency.claim(
                        ctx.getUserId(), ctx.getRequestId(), intent.getIntent());
                if (outcome.decision() != AgentCommandIdempotency.Decision.EXECUTE) {
                    log.info("幂等命中，跳过执行: user={} requestId={} decision={}",
                            ctx.getUsername(), ctx.getRequestId(), outcome.decision());
                    AgentDtos.AgentResult replayed = outcome.result();
                    if (replayed != null && replayed.getIntent() == null) {
                        replayed.setIntent(intent.getIntent());
                    }
                    return finish(ctx, complete(ctx, replayed), intent.getIntent());
                }
                idempotencyClaimed = true;
            }

            AgentDtos.AgentResult result = switch (intent.getIntent()) {
                case IntentResult.QUERY_KNOWLEDGE -> travelQueryHandler.answerKnowledge(ctx, intent, trip);
                case IntentResult.QUERY_BUDGET -> travelQueryHandler.answerBudget(ctx, intent, trip);
                case IntentResult.RECORD_EXPENSE -> travelQueryHandler.recordExpense(ctx, intent, trip);
                case IntentResult.ADD_MEMO -> assistantHandler.addMemo(ctx, intent);
                case IntentResult.QUERY_MEMO -> assistantHandler.queryMemo(ctx, intent);
                case IntentResult.ADD_SCHEDULE -> assistantHandler.addSchedule(ctx, intent);
                case IntentResult.QUERY_SCHEDULE -> assistantHandler.querySchedule(ctx, intent);
                case IntentResult.INVOKE_TOOL -> answerInvokeTool(ctx, intent, intentReceipt.handle());
                case IntentResult.PLAN_TRIP, IntentResult.MODIFY_ITINERARY ->
                        planAndPresent(ctx, intent, trip, intentReceipt.handle());
                default -> complete(ctx, AgentDtos.AgentResult.builder()
                        .intent(IntentResult.CHITCHAT)
                        .status(AgentDtos.STATUS_COMPLETED)
                        .message(MessageTemplates.HELP)
                        .build());
            };
            // 写入已经发生：先登记结果供重放，再做取消检查（取消不能抹掉已落库的事实）
            if (idempotentWrite) {
                if (result == null || AgentDtos.STATUS_FAILED.equals(result.getStatus())) {
                    idempotency.release(ctx.getUserId(), ctx.getRequestId());
                } else {
                    idempotency.complete(ctx.getUserId(), ctx.getRequestId(), result);
                }
                idempotencyClaimed = false;
            }
            if (abortIfCancelled(ctx, "before-return")) {
                return aborted(ctx);
            }
            // 追问轮次在历史里打标记，下一轮据此续槽（见 INTENT_NEED_INFO）
            String historyIntent = result != null && result.getMissingSlots() != null
                    && !result.getMissingSlots().isEmpty() ? INTENT_NEED_INFO : intent.getIntent();
            return finish(ctx, result, historyIntent);
        } catch (BusinessException e) {
            // 业务规则/校验类错误（含越权、参数不合法）：原样告诉用户，不伪装成系统故障
            log.info("Agent 业务校验未通过: {}", e.getMessage());
            releaseIdempotencyIfClaimed(idempotencyClaimed, ctx);
            ctx.setState(AgentState.ERROR);
            AgentDtos.AgentResult rejected = AgentDtos.AgentResult.builder()
                    .intent("REJECTED")
                    .status(AgentDtos.STATUS_FAILED)
                    .message(e.getMessage())
                    .build();
            ctx.emit("error", Map.of("message", rejected.getMessage()));
            ctx.emit("complete", rejected);
            return finish(ctx, rejected, "REJECTED");
        } catch (Exception e) {
            log.error("Agent 执行异常", e);
            ctx.setState(AgentState.ERROR);
            releaseIdempotencyIfClaimed(idempotencyClaimed, ctx);
            AgentDtos.AgentResult failed = AgentDtos.AgentResult.builder()
                    .intent("ERROR")
                    .status(AgentDtos.STATUS_FAILED)
                    // 内部异常细节只进日志，不返回给用户
                    .message("抱歉，处理请求时出现问题，请稍后重试或换一种说法")
                    .build();
            ctx.emit("error", Map.of("message", failed.getMessage()));
            ctx.emit("complete", failed);
            return finish(ctx, failed, "ERROR");
        }
    }

    /**
     * 执行异常时释放幂等抢占（fail-open）：否则用户拿同一个 requestId 重试，
     * 永远只会得到「正在处理中」，等于被一次偶发故障永久拉黑。
     */
    private void releaseIdempotencyIfClaimed(boolean claimed, AgentContext ctx) {
        if (claimed) {
            idempotency.release(ctx.getUserId(), ctx.getRequestId());
        }
    }

    // ==================== 阶段计时（agent.stage） ====================

    /** intent 阶段：意图识别（LLM 优先，规则兜底）；异常也计时，失败次数看 count */
    private Coordinator.Receipt<IntentResult> timedIntent(AgentContext ctx, Trip trip) {
        Timer.Sample sample = stageTimer.start();
        try {
            // 任务图根节点：无前置依赖；后续 PLAN / TOOL 都依赖它
            return coordinator.dispatch(ctx,
                    Coordinator.TaskSpec.of(com.travel.agent.entity.AgentTask.RECEIVER_INTENT, "INTENT"),
                    () -> intentAgent.detect(ctx, trip),
                    intent -> "识别意图: " + (intent == null ? "?" : intent.getIntent()));
        } finally {
            stageTimer.stop(AgentStageTimer.STAGE_INTENT, sample);
        }
    }

    // ==================== 客户端断连取消 ====================

    /**
     * 阶段边界取消检查：客户端已断开就不再发起新的 LLM / 工具调用。
     *
     * <p>只做标记检查，<b>不</b> interrupt 线程（共享池线程不能被污染）。
     * 真正的计数在 {@link #handle(AgentContext)} 的 finally 里统一做，一次运行只计一次。
     *
     * @return true 表示本轮应立即返回 {@link #aborted(AgentContext)}
     */
    private boolean abortIfCancelled(AgentContext ctx, String boundary) {
        if (!ctx.isCancelled()) {
            return false;
        }
        log.info("客户端已断开，Agent 在阶段边界取消: boundary={} user={}", boundary, ctx.getUsername());
        return true;
    }

    /**
     * 取消后的返回：不再发 SSE 事件（连接已断，发了也没人收），也不落会话记忆——
     * 半途取消的一轮没有可展示的助手答复，写进去只会污染下一轮的上下文。
     */
    private AgentDtos.AgentResult aborted(AgentContext ctx) {
        return AgentDtos.AgentResult.builder()
                .intent(CANCELLED_INTENT)
                .status(AgentDtos.STATUS_FAILED)
                .message("请求已取消（客户端已断开连接）")
                .build();
    }

    /**
     * 会话记忆：载入上一段历史供意图识别消解指代，并把本轮用户消息先落一条。
     * 先载后存，保证 history 不包含本轮消息；无 conversationId 时两者都是空操作。
     */
    private void loadConversation(AgentContext ctx) {
        try {
            ctx.setHistory(conversationService.recent(ctx.getUserId(), ctx.getConversationId()));
            conversationService.append(ctx.getUserId(), ctx.getConversationId(),
                    ConversationMessage.ROLE_USER, ctx.getMessage(), null);
        } catch (Exception e) {
            // 记忆存取失败不能挡住对话：按单轮继续处理
            log.warn("会话记忆读取失败，按单轮对话处理: {}", e.getMessage());
            ctx.setHistory(List.of());
        }
    }

    /** 出口统一落一条助手消息（也吃掉 null message 的返回），异常时同样不挡主流程 */
    private AgentDtos.AgentResult finish(AgentContext ctx, AgentDtos.AgentResult result, String intent) {
        // 预算降级的说明必须在返回给用户之前补上（handler 自己发的 complete 事件可能已先到，
        // 所以 complete() 与 finish() 两处都要挂，靠前缀幂等避免重复）
        attachBudgetNotice(ctx, result);
        try {
            conversationService.append(ctx.getUserId(), ctx.getConversationId(),
                    ConversationMessage.ROLE_ASSISTANT, result == null ? null : result.getMessage(), intent);
        } catch (Exception e) {
            log.warn("会话记忆写入失败: {}", e.getMessage());
        }
        return result;
    }

    // ==================== P1 端到端时间预算：用户提示与追踪留痕 ====================

    /**
     * 把预算降级写进用户可见的回复（message + warnings）。
     *
     * <p>ADR-002 允许「什么都没做」，但不允许「静默假成功」：既然跳过了审核/研究，
     * 就必须在回复里说清楚跳过了什么，让用户自己决定要不要重试。
     * 幂等：同一轮可能先后经过 complete() 与 finish()，靠前缀去重。
     */
    private void attachBudgetNotice(AgentContext ctx, AgentDtos.AgentResult result) {
        if (result == null || !ctx.hasBudgetDegradations()) {
            return;
        }
        String notice = budgetNoticeLine(ctx);
        boolean inMessage = result.getMessage() != null && result.getMessage().contains(BUDGET_NOTICE_PREFIX);
        boolean inWarnings = result.getWarnings() != null
                && result.getWarnings().stream().anyMatch(w -> w != null && w.contains(BUDGET_NOTICE_PREFIX));
        if (inMessage && inWarnings) {
            return;
        }
        if (!inMessage) {
            result.appendMessage("\n" + notice);
        }
        if (!inWarnings) {
            // 不原地改 handler 给的集合（可能是不可变 List）：复制一份再追加
            List<String> warnings = result.getWarnings() == null
                    ? new ArrayList<>() : new ArrayList<>(result.getWarnings());
            warnings.add(notice);
            result.setWarnings(warnings);
        }
    }

    /** 「预算耗尽 → 降级了哪个阶段」的用户可读说明（同一阶段只出现一次） */
    private static String budgetNoticeLine(AgentContext ctx) {
        String stages = ctx.getBudgetDegradations().stream()
                .map(d -> BUDGET_STAGE_LABELS.getOrDefault(d.stage(), d.stage()))
                .distinct()
                .collect(java.util.stream.Collectors.joining("、"));
        return BUDGET_NOTICE_PREFIX + "（" + String.format(java.util.Locale.ROOT, "%.1f",
                ctx.getBudget().totalMs() / 1000.0) + "s）已用尽，为保证按时返回做了降级："
                + stages + "。本次结果未经完整校验，可稍后重试获取完整结果。";
    }

    /**
     * 把预算降级落到运行追踪里：一条 DEGRADE 任务，summary 写明「哪个阶段 → 剩余多少预算」。
     *
     * <p>与「LLM 不可用降级」区分：只有「模型/阶段本来能跑、但时间预算不够」才会写这条任务。
     * {@link com.travel.agent.service.AgentTraceService} 内部吞写入异常（ADR-001），这里再兜一层。
     */
    private void recordBudgetTrace(AgentContext ctx) {
        if (ctx.getRunId() == null || !ctx.hasBudgetDegradations()) {
            return;
        }
        try {
            String summary = ctx.getBudgetDegradations().stream()
                    .map(d -> d.stage() + "：预算耗尽降级（剩余 " + d.remainingMs() + "ms）")
                    .collect(java.util.stream.Collectors.joining("；"));
            String taskId = traceService.startTask(ctx.getRunId(), ctx.getUserId(),
                    BUDGET_TASK_RECEIVER, BUDGET_TASK_TYPE);
            traceService.finishTask(taskId, com.travel.agent.entity.AgentTask.STATUS_SUCCEEDED,
                    truncateForTask(summary), null);
        } catch (RuntimeException e) {
            log.debug("记录预算降级任务失败: runId={} err={}", ctx.getRunId(), e.getMessage());
        }
    }

    private AgentDtos.AgentResult planAndPresent(AgentContext ctx, IntentResult intent, Trip trip,
                                                 Coordinator.TaskHandle intentTask) {
        if (abortIfCancelled(ctx, "before-plan")) {
            return aborted(ctx);
        }

        // 需求槽位（FR-01）：整份规划前先确认「目的地 / 天数 / 人数」。
        // 缺了就合并追问一次（不规划），用户拒绝回答或追问已达上限后按默认值兜底并在回复里明示。
        List<String> defaulted = new ArrayList<>();
        if (IntentResult.PLAN_TRIP.equals(intent.getIntent())) {
            PendingRequirements pending = pendingRequirements(ctx.getHistory());
            RequirementSlots current = intent.getSlots() == null
                    ? RequirementSlots.fromMessage(ctx.getMessage()) : intent.getSlots();
            RequirementSlots slots = current
                    .fillMissingFrom(pending == null ? null : pending.slots())
                    .fillMissingFrom(RequirementSlots.fromTrip(trip));
            boolean declined = RequirementSlots.isDecline(ctx.getMessage());
            List<String> missing = slots.missingRequired();
            if (!declined && !missing.isEmpty()) {
                // 本轮补到了新槽位就继续问剩下的；什么新信息都没有（或已问满三轮）才兜底，
                // 这样「上海」→「三天」→「两个人」能自然收齐，而不会在中途突然替用户决定人数。
                boolean progressed = pending == null || !slots.sameRequired(pending.slots());
                if (progressed && (pending == null || pending.asks() < MAX_REQUIREMENT_ASKS)) {
                    return askForRequirements(ctx, missing);
                }
            }
            RequirementSlots resolved = slots.withDefaults(LocalDate.now());
            defaulted = defaultedLabels(slots, resolved);
            intent.setSlots(resolved);
        }

        ctx.setState(AgentState.PLAN);
        ctx.emit("planning", Map.of("message",
                IntentResult.PLAN_TRIP.equals(intent.getIntent()) ? "正在为你规划行程…" : "正在重新规划行程…"));

        // plan 阶段：规则路径 + LLM 结构化规划都在 plannerAgent.plan 内部，一并计时。
        // 任务图：PLAN 依赖 INTENT（意图没定就不该规划）——依赖边由 Coordinator 强制校验。
        Timer.Sample planSample = stageTimer.start();
        // T5：降级口径 =「本次规划新增了预算降级」，而不是「上下文里曾经有过降级」
        // （intent 阶段的降级不该污染规划成功率，否则一次预算紧张的会话会被记两次 degraded）
        int planDegradationsBefore = ctx.getBudgetDegradations().size();
        Coordinator.Receipt<AgentPlan> planReceipt;
        try {
            planReceipt = coordinator.dispatch(ctx,
                    Coordinator.TaskSpec.after(com.travel.agent.entity.AgentTask.RECEIVER_PLANNER, "PLAN", intentTask),
                    () -> plannerAgent.plan(ctx, intent, trip),
                    p -> p.getOperations().isEmpty() ? "未产出操作：" + truncateForTask(p.getReasoning())
                            : "生成 " + p.getOperations().size() + " 项操作");
        } catch (RuntimeException e) {
            // T5：异常路径同样要落到指标上（阶段耗时在 finally 已记，但"失败"这个结论不能只留在日志里）
            travelMetrics.planOutcome(TravelMetrics.RESULT_FAILED);
            throw e;
        } finally {
            stageTimer.stop(AgentStageTimer.STAGE_PLAN, planSample);
        }
        AgentPlan plan = planReceipt.value();
        // T5：规划结果埋点。degraded 只表示"计划产出了，但本轮跳过了研究/结构化规划"；
        // 空操作方案（无需改动）是正常业务结论，计 success，不算失败。
        travelMetrics.planOutcome(plan == null ? TravelMetrics.RESULT_FAILED
                : ctx.getBudgetDegradations().size() > planDegradationsBefore
                        ? TravelMetrics.RESULT_DEGRADED
                        : TravelMetrics.RESULT_SUCCESS);
        Coordinator.TaskHandle planTask = planReceipt.handle();
        // 计划携带 runId：确认提交（REST 线程）据此把 COMMIT 任务挂回同一运行
        if (plan != null && plan.getRunId() == null) {
            plan.setRunId(ctx.getRunId());
        }
        // 规划往往要几秒到几十秒：结束时再确认客户端还在，别再做预览/反思/落待确认方案
        if (abortIfCancelled(ctx, "after-plan")) {
            return aborted(ctx);
        }
        if (plan.getOperations().isEmpty()) {
            return complete(ctx, AgentDtos.AgentResult.builder()
                    .planId(plan.getPlanId())
                    .tripId(trip == null ? null : trip.getId())
                    .intent(intent.getIntent())
                    .status(AgentDtos.STATUS_COMPLETED)
                    .message(plan.getReasoning())
                    .focus(plan.getFocus())
                    .warnings(new ArrayList<>())
                    .build());
        }

        // 预览与审核互不依赖（都只依赖 PLAN）：第十五批起作为同一并行组执行。
        // 两行任务在 body 开始之前按声明顺序建好 —— id 顺序稳定可回查，时间区间必然重叠。
        // 指标口径：reflect 自本批起覆盖「并行预览 + 审核 + 有限重规划」整段（预览与审核无法
        // 再从时间上拆开，硬拆只会让同一段耗时被记两次）。
        ctx.setState(AgentState.EXECUTE);
        final AgentPlan candidate = plan;
        AgentDtos.AgentResult preview;
        // null 表示本轮因预算耗尽跳过了审核（此时没有审核结论，也不能假装「审核通过」）
        PlanReviewLoop.Outcome outcome = null;
        Timer.Sample reflectSample = stageTimer.start();
        try {
            if (!ctx.getBudget().allows(AgentBudget.MIN_REVIEW_MS)) {
                // 预算降级（ADR-002）：审核要查路线与天气，是整条链路最慢的外部依赖段。
                // 跳过审核与重规划，但 **保留** 纯本地的 Diff 预览——用户仍能拿到可确认的方案，
                // 只是回复里必须如实说明「本次未经审核」，绝不静默当成功。
                ctx.recordBudgetDegradation(AgentBudget.STAGE_REVIEW,
                        "预算耗尽，已跳过审核与重规划（未做路线/天气/预算硬约束校验）");
                preview = coordinator.dispatch(ctx,
                        Coordinator.TaskSpec.after(com.travel.agent.entity.AgentTask.RECEIVER_EXECUTOR,
                                "PREVIEW", planTask),
                        () -> executorAgent.preview(ctx, candidate, trip),
                        p -> "生成 Diff 预览（预算降级，跳过审核）："
                                + (p.getDiff() == null ? 0 : p.getDiff().getChanges().size()) + " 项变更")
                        .value();
            } else {
                java.util.List<Coordinator.Receipt<?>> parallel = coordinator.dispatchAll(ctx, java.util.List.of(
                        new Coordinator.ParallelTask<>(
                                Coordinator.TaskSpec.after(com.travel.agent.entity.AgentTask.RECEIVER_EXECUTOR,
                                        "PREVIEW", planTask),
                                () -> executorAgent.preview(ctx, candidate, trip),
                                p -> "生成 Diff 预览："
                                        + (p.getDiff() == null ? 0 : p.getDiff().getChanges().size()) + " 项变更"),
                        new Coordinator.ParallelTask<>(
                                Coordinator.TaskSpec.after(com.travel.agent.entity.AgentTask.RECEIVER_REVIEWER,
                                        "REVIEW", planTask),
                                () -> reflectAgent.reviewVerdict(candidate, trip),
                                v -> v.passed() ? "审核通过"
                                        : "审核未通过：" + v.highIssues().size() + " 项高风险")));
                preview = (AgentDtos.AgentResult) parallel.get(0).value();
                Coordinator.TaskHandle previewTask = parallel.get(0).handle();
                Coordinator.TaskHandle reviewTask = parallel.get(1).handle();
                ReflectAgent.ReviewVerdict initialVerdict = (ReflectAgent.ReviewVerdict) parallel.get(1).value();

                // 反思（V2.2 FR-06）：审核发现 HIGH 级硬约束问题 → 派 REPLAN 任务（依赖 REVIEW）做有限
                // 重规划，再审一遍。之前这里只把审核警告拼到消息上，用户照样能确认超预算/赶不上的方案——
                // 审核发现问题却不改变结果，等于没有审核。
                ctx.setState(AgentState.REFLECT);
                outcome = planReviewLoop.run(ctx, candidate, trip, reviewTask, initialVerdict);
                // 重规划可能改过条目/时间/费用：用最终计划重建 Diff，保证用户看到的就是最后要提交的内容。
                // 这次预览是「回收重派」（replacesTaskId=首轮预览，发 recover 事件）：首轮结果作废，
                // 同一逻辑工作只留一份最终结果。
                if (outcome.attempts() > 0) {
                    final AgentPlan repairedPlan = outcome.plan();
                    preview = coordinator.redispatch(ctx,
                            Coordinator.TaskSpec.after(com.travel.agent.entity.AgentTask.RECEIVER_EXECUTOR,
                                            "PREVIEW", outcome.reviewTask())
                                    .replacing(previewTask, 1, "重规划后重建 Diff 预览"),
                            () -> executorAgent.preview(ctx, repairedPlan, trip),
                            p -> "重建 Diff 预览："
                                    + (p.getDiff() == null ? 0 : p.getDiff().getChanges().size()) + " 项变更")
                            .value();
                }
            }
        } finally {
            stageTimer.stop(AgentStageTimer.STAGE_REFLECT, reflectSample);
        }
        final AgentPlan planned = outcome != null && outcome.attempts() > 0 ? outcome.plan() : candidate;
        // 模板事实 + 模型润色（必须在审核/修复之后，数字才是最终值）。
        // P1：润色是锦上添花且要再调一次模型——预算不够就用模板原文，并留下降级痕迹。
        if (ctx.getBudget().allows(AgentBudget.MIN_NARRATION_MS)) {
            preview.setMessage(planNarrationService.polish(composePlanMessage(planned)));
        } else {
            ctx.recordBudgetDegradation(AgentBudget.STAGE_NARRATION, "预算耗尽，跳过文案润色（使用模板文案）");
            preview.setMessage(composePlanMessage(planned));
        }
        if (outcome != null) {
            applyReview(preview, outcome);
        }
        // FR-01：兜底项必须明示——「不答也能继续」不等于「静默替用户决定」
        if (!defaulted.isEmpty()) {
            preview.appendMessage("\nℹ 没提到的信息已按默认：" + String.join("、", defaulted) + "。可以随时让我改。");
        }
        ctx.setState(AgentState.REFLECT);

        ctx.setState(AgentState.PRESENT);
        String planRevision = trip == null ? null
                : ItineraryService.planRevision(trip, itineraryService.listEntities(trip.getId()));
        // contentHash 在 PendingPlanStore.put 内对 plan_json 计算（单一权威），这里不需要传入
        pendingPlans.put(new PendingPlanStore.PendingPlan(
                planned.getPlanId(), ctx.getUserId(), trip == null ? null : trip.getId(), planned, preview,
                planRevision, null, java.time.Instant.now()));

        if (preview.getDiff() != null) {
            ctx.emit("plan_update", Map.of("diff", preview.getDiff(), "budget", preview.getBudget()));
        }
        if (preview.getFocus() != null) {
            ctx.emit("focus", preview.getFocus());
        }
        if (preview.getConfirmation() != null) {
            ctx.emit("confirmation", preview.getConfirmation());
        }
        return complete(ctx, preview);
    }

    /** 会话里「正在补需求槽位」的状态：上一轮是追问，且追问片段内用户说过的槽位 */
    private record PendingRequirements(RequirementSlots slots, int asks) {
    }

    /**
     * 从会话历史还原补槽状态（FR-01）。
     *
     * <p>只认「最后一条助手消息是需求追问」的情况，并把这次追问片段内用户说过的槽位按
     * 「新的优先、旧的补空」合并起来——这样「上海」→「三天」→「两个人」分三轮说，
     * 下一轮仍能拼出完整需求。用户中间改聊别的（最后一条助手消息不是追问）时自然失效，
     * 不会把旧话题的槽位带进新规划。
     */
    private PendingRequirements pendingRequirements(List<ConversationMessage> history) {
        if (history == null || history.isEmpty()) {
            return null;
        }
        int asks = 0;
        RequirementSlots collected = RequirementSlots.empty();
        for (int i = history.size() - 1; i >= 0; i--) {
            ConversationMessage message = history.get(i);
            if (message == null) {
                continue;
            }
            if ("assistant".equals(message.getRole())) {
                if (!INTENT_NEED_INFO.equals(message.getIntent())) {
                    break; // 上一次正常答复 = 追问片段起点
                }
                asks++;
                continue;
            }
            if ("user".equals(message.getRole())) {
                collected = collected.fillMissingFrom(RequirementSlots.fromMessage(message.getContent()));
            }
        }
        return asks == 0 ? null : new PendingRequirements(collected, asks);
    }

    /**
     * 追问轮次后的短回答续槽（FR-01）：「上海」「三天」「两个人」这类消息本身不像规划指令，
     * 规则意图会落到 CHITCHAT；只要上一轮是追问、且这句里补到了槽位（或明确说「随便」），
     * 就按 PLAN_TRIP 继续，让补槽对话能自然地多轮收齐。
     */
    private IntentResult resumeRequirementCollection(AgentContext ctx, IntentResult intent) {
        if (intent == null || !IntentResult.CHITCHAT.equals(intent.getIntent())) {
            return intent;
        }
        if (pendingRequirements(ctx.getHistory()) == null) {
            return intent;
        }
        String message = ctx.getMessage() == null ? "" : ctx.getMessage();
        RequirementSlots parsed = RequirementSlots.fromMessage(message);
        boolean decline = RequirementSlots.isDecline(message);
        if (!decline && parsed.missingRequired().size() == 3) {
            return intent; // 什么都没补到：仍然只是闲聊
        }
        return IntentResult.builder()
                .intent(IntentResult.PLAN_TRIP)
                .target(IntentResult.TARGET_NONE)
                .constraints(intent.getConstraints())
                .slots(parsed)
                .build();
    }

    /** 合并追问：一次问完所有缺失的必填槽位；预算/日期只作可选提示，不强制 */
    private AgentDtos.AgentResult askForRequirements(AgentContext ctx, List<String> missing) {
        StringBuilder text = new StringBuilder("开始规划前，还需要确认：");
        if (missing.contains("destination")) {
            text.append("\n· 去哪里？");
        }
        if (missing.contains("days")) {
            text.append("\n· 玩几天？");
        }
        if (missing.contains("partySize")) {
            text.append("\n· 几个人一起（儿童也算人数）？");
        }
        text.append("\n预算和出发日期也可以顺手说；不说的话我按 ¥5000、明天出发估算。");
        log.info("需求槽位不完整，先追问: {} user={}", missing, ctx.getUsername());
        return complete(ctx, AgentDtos.AgentResult.builder()
                .intent(IntentResult.PLAN_TRIP)
                .status(AgentDtos.STATUS_COMPLETED)
                .message(text.toString())
                .missingSlots(new ArrayList<>(missing))
                .warnings(new ArrayList<>())
                .build());
    }

    /** 哪些槽位用了兜底默认值（只描述「原本缺失」的项；写进回复，绝不静默） */
    private static List<String> defaultedLabels(RequirementSlots before, RequirementSlots after) {
        List<String> labels = new ArrayList<>();
        if (before.destination() == null || before.destination().isBlank()) {
            labels.add("目的地按「" + after.destination() + "」");
        }
        if (before.days() == null) {
            labels.add("天数按 " + after.days() + " 天");
        }
        if (before.partySize() == null) {
            labels.add("人数按 " + after.partySize() + " 人");
        }
        if (before.budget() == null) {
            labels.add("预算按 ¥" + after.budget().stripTrailingZeros().toPlainString());
        }
        if (before.startDate() == null) {
            labels.add("出发日期按 " + after.startDate().format(SHORT_DATE));
        }
        return labels;
    }

    /**
     * 把审核结论落到预览上（V2.2 FR-06）：
     * <ul>
     *   <li>{@code review}：结构化判定 + 已执行的重规划动作，前端据此展示「审核结论」；</li>
     *   <li>{@code warnings}：既有契约，保留全部问题文案（HIGH + MEDIUM）；</li>
     *   <li>{@code blocked}：未解决 HIGH 问题时把风险等级抬到 HIGH 并要求显式接受，
     *       确认门（PlanConfirmationService）据此拒绝「未确认风险」的提交。</li>
     * </ul>
     */
    private void applyReview(AgentDtos.AgentResult preview, PlanReviewLoop.Outcome outcome) {
        ReflectAgent.ReviewVerdict verdict = outcome.verdict();
        preview.setReview(PlanReviewLoop.toDto(outcome));

        if (!outcome.repairs().isEmpty()) {
            preview.appendMessage("\n✅ 已自动重规划 " + outcome.attempts() + " 次："
                    + String.join("；", outcome.repairs()) + "。");
        }
        if (!verdict.warnings().isEmpty()) {
            preview.setWarnings(new ArrayList<>(verdict.warnings()));
            preview.appendMessage("\n⚠ " + String.join("；", verdict.warnings()));
        }
        if (!verdict.passed() && preview.getConfirmation() != null) {
            preview.getConfirmation().setRequiresAcknowledgement(true);
            preview.getConfirmation().setRiskLevel(com.travel.agent.tool.RiskLevel.HIGH.name());
            preview.appendMessage("\n❌ 仍有未解决的高风险问题，本次提交需要明确接受风险。");
        }
    }

    private String composePlanMessage(AgentPlan plan) {
        if (IntentResult.PLAN_TRIP.equals(plan.getIntent()) && plan.getTripDraft() != null) {
            List<ItineraryItem> added = plan.getOperations().stream()
                    .filter(op -> PlanOperation.ADD.equals(op.getType()))
                    .map(PlanOperation::getDraft)
                    .toList();
            long attractions = added.stream().filter(i -> ItineraryItem.TYPE_ATTRACTION.equals(i.getItemType())).count();
            long restaurants = added.stream().filter(i -> ItineraryItem.TYPE_RESTAURANT.equals(i.getItemType())).count();
            boolean hasHotel = added.stream().anyMatch(i -> ItineraryItem.TYPE_HOTEL.equals(i.getItemType()));
            BigDecimal total = plan.getExtras().get("total") instanceof BigDecimal t ? t : BigDecimal.ZERO;
            return MessageTemplates.planTrip(plan.getTripDraft(), (int) attractions, (int) restaurants, hasHotel, total);
        }
        if (IntentResult.MODIFY_ITINERARY.equals(plan.getIntent())) {
            WeatherInfo weather = plan.getExtras().get("weather") instanceof WeatherInfo w ? w : null;
            List<String> replacements = plan.getExtras().get("replacements") instanceof List<?> list
                    ? list.stream().map(String::valueOf).toList() : List.of();
            int day = plan.getExtras().get("day") instanceof Integer d ? d : 1;
            return MessageTemplates.modify(weather, replacements, day);
        }
        return plan.getReasoning();
    }

    /**
     * INVOKE_TOOL：LLM 选中一个已注册的 MCP 外部工具并给出参数，直接执行（工具层保证只读）。
     * 仅 LLM 路径可产出该意图；规则兜底路径不会走到这里。
     */
    private AgentDtos.AgentResult answerInvokeTool(AgentContext ctx, IntentResult intent,
                                                   Coordinator.TaskHandle intentTask) {
        String toolName = intent.getToolName();
        AgentTool tool = toolRegistry.get(toolName);
        if (tool == null) {
            return complete(ctx, AgentDtos.AgentResult.builder()
                    .intent(intent.getIntent())
                    .status(AgentDtos.STATUS_FAILED)
                    .message("外部工具「" + toolName + "」暂不可用，请换个问法或稍后再试。")
                    .warnings(new ArrayList<>())
                    .build());
        }
        Object result;
        if (abortIfCancelled(ctx, "before-tool")) {
            return aborted(ctx);
        }
        // P1：预算耗尽时不再发起外部工具调用（MCP/HTTP 可能很慢）；如实告诉用户「没查」，
        // 而不是返回一个空的「已查询」——那等于伪造一次外部查询（ADR-002 / ADR-006）。
        if (!ctx.getBudget().allows(AgentBudget.MIN_TOOL_MS)) {
            ctx.recordBudgetDegradation(AgentBudget.STAGE_TOOL, "预算耗尽，未执行外部工具查询");
            return complete(ctx, AgentDtos.AgentResult.builder()
                    .intent(intent.getIntent())
                    .status(AgentDtos.STATUS_COMPLETED)
                    .message("⏳ 时间预算已用尽，本次没有执行外部工具「" + toolName + "」的查询，请稍后重试。")
                    .warnings(new ArrayList<>())
                    .build());
        }
        // tool 阶段：外部 MCP / 内置只读工具调用（ToolInvoker 内部含审计与 tool_call/tool_result 事件）
        // 任务图：TOOL 依赖 INTENT（先知道要调什么，才谈得上调工具）
        Timer.Sample toolSample = stageTimer.start();
        try {
            result = coordinator.dispatch(ctx,
                    Coordinator.TaskSpec.after("tool-invoker", "TOOL", intentTask),
                    () -> toolInvoker.invoke(ctx, toolName,
                            intent.getToolArgs() == null ? Map.of() : intent.getToolArgs()),
                    r -> "调用外部工具 " + toolName).value();
        } finally {
            stageTimer.stop(AgentStageTimer.STAGE_TOOL, toolSample);
        }
        if (abortIfCancelled(ctx, "after-tool")) {
            return aborted(ctx);
        }
        return complete(ctx, AgentDtos.AgentResult.builder()
                .intent(intent.getIntent())
                .status(AgentDtos.STATUS_COMPLETED)
                .message("🔎 已通过外部工具「" + toolName + "」查询：\n\n" + renderToolResult(result))
                .warnings(new ArrayList<>())
                .build());
    }

    private String renderToolResult(Object result) {
        if (result == null) {
            return "（无结果）";
        }
        String text;
        if (result instanceof String s && !s.isBlank()) {
            text = s;
        } else {
            try {
                text = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(result);
            } catch (Exception e) {
                text = String.valueOf(result);
            }
        }
        return text.length() > 2000 ? text.substring(0, 2000) + "…" : text;
    }

    private AgentDtos.AgentResult complete(AgentContext ctx, AgentDtos.AgentResult result) {
        // 预算降级提示必须在 complete 事件之前挂上：SSE 客户端拿到的最终结果要与同步接口一致
        attachBudgetNotice(ctx, result);
        ctx.emit("complete", result);
        return result;
    }

    /** 任务摘要截断（总长不超过追踪表 summary 列宽 1000，含省略号） */
    private static String truncateForTask(String text) {
        if (text == null) {
            return "";
        }
        return text.length() > 900 ? text.substring(0, 899) + "…" : text;
    }

    // ==================== 确认 / 取消 / 撤销（Human-in-the-loop） ====================
    // 具体并发语义见 PlanConfirmationService；这里保留对外入口以维持既有调用方与测试契约。

    public AgentDtos.AgentResult confirm(String planId, Long userId) {
        return planConfirmation.confirm(planId, userId, null, null);
    }

    /** 带内容指纹的确认（V2.2 FR-08）：指纹与方案存档不一致时拒绝提交 */
    public AgentDtos.AgentResult confirm(String planId, Long userId, String contentHash) {
        return planConfirmation.confirm(planId, userId, contentHash, null);
    }

    /**
     * 完整确认入口（V2.2 FR-06）：{@code acknowledgeIssues=true} 表示用户已在页面上明确接受
     * 未解决的高风险问题；审核 blocked 的方案不带它一律拒绝（方案不被消费，可改带后重试）。
     */
    public AgentDtos.AgentResult confirm(String planId, Long userId, String contentHash, Boolean acknowledgeIssues) {
        return planConfirmation.confirm(planId, userId, contentHash, acknowledgeIssues);
    }

    public AgentDtos.AgentResult cancel(String planId, Long userId) {
        return planConfirmation.cancel(planId, userId);
    }

    public AgentDtos.AgentResult pendingPreview(String planId, Long userId) {
        return planConfirmation.pendingPreview(planId, userId);
    }

    public AgentDtos.AgentResult undo(Long tripId, Long userId) {
        return planConfirmation.undo(tripId, userId);
    }
}