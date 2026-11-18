package com.travel.agent.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.travel.agent.dto.AgentDtos;
import com.travel.agent.entity.ConversationMessage;
import com.travel.agent.entity.ItineraryItem;
import com.travel.agent.entity.Trip;
import com.travel.agent.exception.BusinessException;
import com.travel.agent.model.WeatherInfo;
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
 *   <li>{@link AssistantIntentHandler}：备忘 / 日程 / 日报 / 健康；</li>
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
     * 票据只读会话下禁止执行的"直接写入类"意图：这四类都会立刻落库
     * （账单 / 备忘 / 日程 / 日报），而票据是 URL 凭证，泄漏面大，不能凭它代受害者写数据。
     *
     * <p>规划类（PLAN_TRIP / MODIFY_ITINERARY）保持可用：它们只产出待确认方案（pending_plan），
     * 真正提交仍需 POST /api/agent/confirm，而 POST 不接受票据。查询类本来就只读。
     */
    private static final Set<String> TICKET_READONLY_BLOCKED_INTENTS = Set.of(
            IntentResult.RECORD_EXPENSE, IntentResult.ADD_MEMO,
            IntentResult.ADD_SCHEDULE, IntentResult.GEN_DAILY_REPORT);

    /** 票据只读会话拒绝写意图时返回的文案（明确告知原因与替代路径，不当作错误处理） */
    private static final String TICKET_READONLY_MESSAGE =
            "当前连接为只读票据会话，记账/备忘/日程/日报请在网页端操作。";

    /** SSE 断连计数：reason=complete|timeout|error（controller 记）＋ cancelled（引擎阶段边界短路记） */
    private static final String SSE_DISCONNECTS_METRIC = "sse.disconnects";

    /** 被取消的轮次在结果里的 intent 标记（只用于日志/单测识别，不随 SSE 事件下发） */
    public static final String CANCELLED_INTENT = "CANCELLED";

    private final IntentAgent intentAgent;
    private final PlannerAgent plannerAgent;
    private final ExecutorAgent executorAgent;
    private final ReflectAgent reflectAgent;
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
    /** V2.2 追踪：run / 任务 / 工具调用落库（内部吞异常，绝不反噬主流程） */
    private final com.travel.agent.service.AgentTraceService traceService;
    /** 阶段任务记录器（引擎与规划器共用的任务包装样板） */
    private final AgentTaskRecorder taskRecorder;
    /** 指标：sse.disconnects（注入方式与 StructuredPlanner、LlmClient 一致） */
    private final MeterRegistry meterRegistry;

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

    /** 运行收尾：按结果状态映射 run 状态（NEED_CONFIRMATION 也是成功——规划完成等确认） */
    private void finishRunRecord(AgentContext ctx, AgentDtos.AgentResult result) {
        if (ctx.getRunId() == null) {
            return;
        }
        String status;
        String error = null;
        if (result == null) {
            status = com.travel.agent.entity.AgentRun.STATUS_FAILED;
        } else if (AgentDtos.STATUS_FAILED.equals(result.getStatus())) {
            status = com.travel.agent.entity.AgentRun.STATUS_FAILED;
            error = result.getMessage();
        } else {
            status = com.travel.agent.entity.AgentRun.STATUS_SUCCEEDED;
        }
        traceService.finishRun(ctx.getRunId(), result == null ? null : result.getIntent(), status, error);
    }

    /**
     * 以独立任务记录包裹一段阶段逻辑（V2.2 FR-04 最小任务形态）：
     * 任务开始/结束各落一行（REQUIRES_NEW，主流程失败痕迹仍在），
     * 执行期间把 taskId 挂到 ctx 上，工具调用追踪据此关联触发任务。
     */
    private <T> T traced(AgentContext ctx, String receiver, String taskType,
                         java.util.function.Supplier<T> body, java.util.function.Function<T, String> summary) {
        return taskRecorder.around(ctx, receiver, taskType, body, summary);
    }

    private AgentDtos.AgentResult doHandle(AgentContext ctx) {
        try {
            Trip trip = ctx.getTripId() != null ? tripService.getOwned(ctx.getTripId(), ctx.getUserId()) : null;
            if (abortIfCancelled(ctx, "before-intent")) {
                return aborted(ctx);
            }
            IntentResult intent = timedIntent(ctx, trip);
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

            AgentDtos.AgentResult result = switch (intent.getIntent()) {
                case IntentResult.QUERY_KNOWLEDGE -> travelQueryHandler.answerKnowledge(ctx, intent, trip);
                case IntentResult.QUERY_BUDGET -> travelQueryHandler.answerBudget(ctx, intent, trip);
                case IntentResult.RECORD_EXPENSE -> travelQueryHandler.recordExpense(ctx, intent, trip);
                case IntentResult.ADD_MEMO -> assistantHandler.addMemo(ctx, intent);
                case IntentResult.QUERY_MEMO -> assistantHandler.queryMemo(ctx, intent);
                case IntentResult.ADD_SCHEDULE -> assistantHandler.addSchedule(ctx, intent);
                case IntentResult.QUERY_SCHEDULE -> assistantHandler.querySchedule(ctx, intent);
                case IntentResult.GEN_DAILY_REPORT -> assistantHandler.dailyReport(ctx, intent);
                case IntentResult.QUERY_HEALTH -> assistantHandler.queryHealth(ctx, intent);
                case IntentResult.INVOKE_TOOL -> answerInvokeTool(ctx, intent);
                case IntentResult.PLAN_TRIP, IntentResult.MODIFY_ITINERARY -> planAndPresent(ctx, intent, trip);
                default -> complete(ctx, AgentDtos.AgentResult.builder()
                        .intent(IntentResult.CHITCHAT)
                        .status(AgentDtos.STATUS_COMPLETED)
                        .message(MessageTemplates.HELP)
                        .build());
            };
            if (abortIfCancelled(ctx, "before-return")) {
                return aborted(ctx);
            }
            return finish(ctx, result, intent.getIntent());
        } catch (BusinessException e) {
            // 业务规则/校验类错误（含越权、参数不合法）：原样告诉用户，不伪装成系统故障
            log.info("Agent 业务校验未通过: {}", e.getMessage());
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

    // ==================== 阶段计时（agent.stage） ====================

    /** intent 阶段：意图识别（LLM 优先，规则兜底）；异常也计时，失败次数看 count */
    private IntentResult timedIntent(AgentContext ctx, Trip trip) {
        Timer.Sample sample = stageTimer.start();
        try {
            return traced(ctx, com.travel.agent.entity.AgentTask.RECEIVER_INTENT, "INTENT",
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
        try {
            conversationService.append(ctx.getUserId(), ctx.getConversationId(),
                    ConversationMessage.ROLE_ASSISTANT, result == null ? null : result.getMessage(), intent);
        } catch (Exception e) {
            log.warn("会话记忆写入失败: {}", e.getMessage());
        }
        return result;
    }

    private AgentDtos.AgentResult planAndPresent(AgentContext ctx, IntentResult intent, Trip trip) {
        if (abortIfCancelled(ctx, "before-plan")) {
            return aborted(ctx);
        }
        ctx.setState(AgentState.PLAN);
        ctx.emit("planning", Map.of("message",
                IntentResult.PLAN_TRIP.equals(intent.getIntent()) ? "正在为你规划行程…" : "正在重新规划行程…"));

        // plan 阶段：规则路径 + LLM 结构化规划都在 plannerAgent.plan 内部，一并计时
        Timer.Sample planSample = stageTimer.start();
        AgentPlan plan;
        try {
            plan = traced(ctx, com.travel.agent.entity.AgentTask.RECEIVER_PLANNER, "PLAN",
                    () -> plannerAgent.plan(ctx, intent, trip),
                    p -> p.getOperations().isEmpty() ? "未产出操作：" + truncateForTask(p.getReasoning())
                            : "生成 " + p.getOperations().size() + " 项操作");
        } finally {
            stageTimer.stop(AgentStageTimer.STAGE_PLAN, planSample);
        }
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

        ctx.setState(AgentState.EXECUTE);
        AgentDtos.AgentResult preview = traced(ctx, com.travel.agent.entity.AgentTask.RECEIVER_EXECUTOR,
                "PREVIEW", () -> executorAgent.preview(ctx, plan, trip),
                p -> "生成 Diff 预览：" + (p.getDiff() == null ? 0 : p.getDiff().getChanges().size()) + " 项变更");
        // 模板事实 + 模型润色：润色失败或不可用时原样返回模板
        preview.setMessage(planNarrationService.polish(composePlanMessage(plan)));

        ctx.setState(AgentState.REFLECT);
        List<String> warnings = traced(ctx, com.travel.agent.entity.AgentTask.RECEIVER_REVIEWER,
                "REVIEW", () -> reflectAgent.review(plan, trip),
                w -> w.isEmpty() ? "审核通过，无警告" : "发现 " + w.size() + " 条警告");
        if (!warnings.isEmpty()) {
            preview.setWarnings(warnings);
            preview.setMessage(preview.getMessage() + "\n⚠ " + String.join("；", warnings));
        }

        ctx.setState(AgentState.PRESENT);
        String planRevision = trip == null ? null
                : ItineraryService.planRevision(trip, itineraryService.listEntities(trip.getId()));
        // contentHash 在 PendingPlanStore.put 内对 plan_json 计算（单一权威），这里不需要传入
        pendingPlans.put(new PendingPlanStore.PendingPlan(
                plan.getPlanId(), ctx.getUserId(), trip == null ? null : trip.getId(), plan, preview,
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
    private AgentDtos.AgentResult answerInvokeTool(AgentContext ctx, IntentResult intent) {
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
        // tool 阶段：外部 MCP / 内置只读工具调用（ToolInvoker 内部含审计与 tool_call/tool_result 事件）
        Timer.Sample toolSample = stageTimer.start();
        try {
            result = traced(ctx, "tool-invoker", "TOOL",
                    () -> toolInvoker.invoke(ctx, toolName,
                            intent.getToolArgs() == null ? Map.of() : intent.getToolArgs()),
                    r -> "调用外部工具 " + toolName);
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
        return planConfirmation.confirm(planId, userId, null);
    }

    /** 带内容指纹的确认（V2.2 FR-08）：指纹与方案存档不一致时拒绝提交 */
    public AgentDtos.AgentResult confirm(String planId, Long userId, String contentHash) {
        return planConfirmation.confirm(planId, userId, contentHash);
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