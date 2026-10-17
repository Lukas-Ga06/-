package com.travel.agent.agent;

import com.travel.agent.dto.AgentDtos;
import com.travel.agent.dto.BudgetDtos;
import com.travel.agent.entity.DailyReport;
import com.travel.agent.entity.Expense;
import com.travel.agent.entity.ItineraryItem;
import com.travel.agent.entity.Memo;
import com.travel.agent.entity.ScheduleItem;
import com.travel.agent.entity.Trip;
import com.travel.agent.exception.BusinessException;
import com.travel.agent.model.WeatherInfo;
import com.travel.agent.rag.RagService;
import com.travel.agent.service.BudgetService;
import com.travel.agent.service.DailyReportService;
import com.travel.agent.service.ExpenseService;
import com.travel.agent.service.HealthService;
import com.travel.agent.service.ItineraryService;
import com.travel.agent.service.MemoService;
import com.travel.agent.service.OperationHistoryService;
import com.travel.agent.service.ScheduleService;
import com.travel.agent.service.TripService;
import com.travel.agent.service.TravelCatalogService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Agent Engine：RECEIVE → UNDERSTAND → PLAN → EXECUTE → REFLECT → PRESENT → CONFIRM → COMMIT。
 * 异常路径：EXECUTE → ERROR → 反馈用户。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AgentEngine {

    private final IntentAgent intentAgent;
    private final PlannerAgent plannerAgent;
    private final ExecutorAgent executorAgent;
    private final ReflectAgent reflectAgent;
    private final PendingPlanStore pendingPlans;
    private final RagService ragService;
    private final BudgetService budgetService;
    private final TripService tripService;
    private final ItineraryService itineraryService;
    private final OperationHistoryService operationHistoryService;
    private final TravelCatalogService catalogService;
    private final ExpenseService expenseService;
    private final MemoService memoService;
    private final ScheduleService scheduleService;
    private final HealthService healthService;
    private final DailyReportService dailyReportService;

    public AgentDtos.AgentResult handle(AgentContext ctx) {
        ctx.emit("start", Map.of("sessionId", ctx.getSessionId()));
        ctx.setState(AgentState.UNDERSTAND);
        try {
            Trip trip = ctx.getTripId() != null ? tripService.getOwned(ctx.getTripId(), ctx.getUserId()) : null;
            IntentResult intent = intentAgent.detect(ctx, trip);
            ctx.emit("intent", intent);
            log.info("Intent: {} target={} user={} trip={}", intent.getIntent(), intent.getTarget(),
                    ctx.getUsername(), ctx.getTripId());

            return switch (intent.getIntent()) {
                case IntentResult.QUERY_KNOWLEDGE -> answerKnowledge(ctx, intent, trip);
                case IntentResult.QUERY_BUDGET -> answerBudget(ctx, intent, trip);
                case IntentResult.RECORD_EXPENSE -> answerRecordExpense(ctx, intent, trip);
                case IntentResult.ADD_MEMO -> answerAddMemo(ctx, intent);
                case IntentResult.QUERY_MEMO -> answerQueryMemo(ctx, intent);
                case IntentResult.ADD_SCHEDULE -> answerAddSchedule(ctx, intent);
                case IntentResult.QUERY_SCHEDULE -> answerQuerySchedule(ctx, intent);
                case IntentResult.GEN_DAILY_REPORT -> answerDailyReport(ctx, intent);
                case IntentResult.QUERY_HEALTH -> answerQueryHealth(ctx, intent);
                case IntentResult.PLAN_TRIP, IntentResult.MODIFY_ITINERARY -> planAndPresent(ctx, intent, trip);
                default -> complete(ctx, AgentDtos.AgentResult.builder()
                        .intent(IntentResult.CHITCHAT)
                        .status(AgentDtos.STATUS_COMPLETED)
                        .message(MessageTemplates.HELP)
                        .build());
            };
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
            return rejected;
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
            return failed;
        }
    }

    private AgentDtos.AgentResult planAndPresent(AgentContext ctx, IntentResult intent, Trip trip) {
        ctx.setState(AgentState.PLAN);
        ctx.emit("planning", Map.of("message",
                IntentResult.PLAN_TRIP.equals(intent.getIntent()) ? "正在为你规划行程…" : "正在重新规划行程…"));

        AgentPlan plan = plannerAgent.plan(ctx, intent, trip);
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
        AgentDtos.AgentResult preview = executorAgent.preview(ctx, plan, trip);
        preview.setMessage(composePlanMessage(plan));

        ctx.setState(AgentState.REFLECT);
        List<String> warnings = reflectAgent.review(plan, trip);
        if (!warnings.isEmpty()) {
            preview.setWarnings(warnings);
            preview.setMessage(preview.getMessage() + "\n⚠ " + String.join("；", warnings));
        }

        ctx.setState(AgentState.PRESENT);
        String planRevision = trip == null ? null
                : ItineraryService.planRevision(trip, itineraryService.listEntities(trip.getId()));
        pendingPlans.put(new PendingPlanStore.PendingPlan(
                plan.getPlanId(), ctx.getUserId(), trip == null ? null : trip.getId(), plan, preview,
                planRevision, java.time.Instant.now()));

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

    private AgentDtos.AgentResult answerKnowledge(AgentContext ctx, IntentResult intent, Trip trip) {
        String city = trip != null ? trip.getDestination() : null;
        if (city == null) {
            String message = ctx.getMessage() == null ? "" : ctx.getMessage();
            city = catalogService.cities().stream()
                    .map(c -> c.name())
                    .filter(message::contains)
                    .findFirst().orElse(null);
        }
        RagService.RagAnswer answer = ragService.answer(ctx.getMessage() == null ? "" : ctx.getMessage(), city);
        ctx.emit("rag_result", Map.of("sources", answer.sources()));
        return complete(ctx, AgentDtos.AgentResult.builder()
                .intent(intent.getIntent())
                .status(AgentDtos.STATUS_COMPLETED)
                .message(answer.message())
                .warnings(new ArrayList<>())
                .build());
    }

    private AgentDtos.AgentResult answerBudget(AgentContext ctx, IntentResult intent, Trip trip) {
        if (trip == null) {
            return complete(ctx, AgentDtos.AgentResult.builder()
                    .intent(intent.getIntent())
                    .status(AgentDtos.STATUS_COMPLETED)
                    .message("请先选择一个行程，我就能帮你算预算啦。")
                    .build());
        }
        List<ItineraryItem> items = itineraryService.listEntities(trip.getId());
        AgentDtos.BudgetSummary summary = budgetService.summarize(items);
        BudgetDtos.BudgetExecution execution = budgetService.execution(trip, items,
                expenseService.listEntitiesByTrip(trip.getId()));
        return complete(ctx, AgentDtos.AgentResult.builder()
                .tripId(trip.getId())
                .intent(intent.getIntent())
                .status(AgentDtos.STATUS_COMPLETED)
                .message(MessageTemplates.budgetExecution(execution))
                .focus(AgentDtos.AgentFocus.builder().type("budget").tripId(trip.getId()).build())
                .budget(summary)
                .warnings(new ArrayList<>())
                .build());
    }

    private AgentDtos.AgentResult answerRecordExpense(AgentContext ctx, IntentResult intent, Trip trip) {
        if (trip == null) {
            return complete(ctx, AgentDtos.AgentResult.builder()
                    .intent(intent.getIntent())
                    .status(AgentDtos.STATUS_COMPLETED)
                    .message("记账前请先选择一个行程；进入行程后说「记一笔午餐 45 元」即可。")
                    .build());
        }
        MessageParser.ExpenseRequest request = MessageParser.parseExpenseRequest(ctx.getMessage());
        if (request == null) {
            return complete(ctx, AgentDtos.AgentResult.builder()
                    .intent(intent.getIntent())
                    .status(AgentDtos.STATUS_COMPLETED)
                    .message("请告诉我金额，比如「记一笔午餐 45 元」或「打车花了 30 元」。")
                    .build());
        }
        java.time.LocalDate spentAt = java.time.LocalDate.now().plusDays(request.dayOffset());
        String note = ctx.getMessage() == null ? null
                : ctx.getMessage().length() > 200 ? ctx.getMessage().substring(0, 200) : ctx.getMessage();
        Expense saved = expenseService.add(trip.getId(), ctx.getUserId(), request.category(),
                request.amount(), note, spentAt, request.paymentMethod());
        java.math.BigDecimal total = expenseService.totalOf(trip.getId());
        StringBuilder sb = new StringBuilder("✅ 已记账：").append(saved.getCategory())
                .append(" ¥").append(saved.getAmount().stripTrailingZeros().toPlainString())
                .append("（").append(spentAt).append(" · ").append(saved.getPaymentMethod()).append("）。本行程累计支出 ¥")
                .append(total.stripTrailingZeros().toPlainString());
        String over = "";
        if (trip.getBudget() != null) {
            sb.append(" / 预算 ¥").append(trip.getBudget().stripTrailingZeros().toPlainString());
            if (total.compareTo(trip.getBudget()) > 0) {
                over = "（已超出预算 ⚠）";
            }
        }
        return complete(ctx, AgentDtos.AgentResult.builder()
                .tripId(trip.getId())
                .intent(intent.getIntent())
                .status(AgentDtos.STATUS_COMPLETED)
                .message(sb + over)
                .focus(AgentDtos.AgentFocus.builder().type("budget").tripId(trip.getId()).build())
                .warnings(new ArrayList<>())
                .build());
    }

    /** 备忘：添加（个人助理生活模块） */
    private AgentDtos.AgentResult answerAddMemo(AgentContext ctx, IntentResult intent) {
        String content = MessageParser.parseMemoContent(ctx.getMessage());
        if (content == null || content.isBlank()) {
            return complete(ctx, AgentDtos.AgentResult.builder()
                    .intent(intent.getIntent())
                    .status(AgentDtos.STATUS_COMPLETED)
                    .message("请告诉我备忘内容，比如「记一下明天办签证」。")
                    .build());
        }
        Memo memo = memoService.add(ctx.getUserId(), content);
        long open = memoService.openCount(ctx.getUserId());
        return complete(ctx, AgentDtos.AgentResult.builder()
                .intent(intent.getIntent())
                .status(AgentDtos.STATUS_COMPLETED)
                .message("📝 已添加备忘：「" + memo.getContent() + "」。当前待办 " + open + " 项。")
                .warnings(new ArrayList<>())
                .build());
    }

    /** 备忘：查看待办列表 */
    private AgentDtos.AgentResult answerQueryMemo(AgentContext ctx, IntentResult intent) {
        List<Memo> memos = memoService.list(ctx.getUserId());
        List<Memo> open = memos.stream().filter(m -> !m.isDone()).toList();
        long doneCount = memos.size() - open.size();
        if (open.isEmpty()) {
            return complete(ctx, AgentDtos.AgentResult.builder()
                    .intent(intent.getIntent())
                    .status(AgentDtos.STATUS_COMPLETED)
                    .message(doneCount > 0
                            ? "📝 待办都完成啦（另有 " + doneCount + " 项已完成）。说「记一下……」可以添加新备忘。"
                            : "📝 备忘录是空的，说「记一下明天办签证」即可添加。")
                    .warnings(new ArrayList<>())
                    .build());
        }
        StringBuilder sb = new StringBuilder("📝 你的待办共 ").append(open.size()).append(" 项：");
        for (int i = 0; i < open.size(); i++) {
            sb.append("\n").append(i + 1).append(". ").append(open.get(i).getContent());
        }
        if (doneCount > 0) {
            sb.append("\n（另有 ").append(doneCount).append(" 项已完成）");
        }
        return complete(ctx, AgentDtos.AgentResult.builder()
                .intent(intent.getIntent())
                .status(AgentDtos.STATUS_COMPLETED)
                .message(sb.toString())
                .warnings(new ArrayList<>())
                .build());
    }

    /** 日程：添加（办公自动化模块） */
    private AgentDtos.AgentResult answerAddSchedule(AgentContext ctx, IntentResult intent) {
        MessageParser.ScheduleRequest request = MessageParser.parseScheduleRequest(ctx.getMessage());
        if (request == null) {
            return complete(ctx, AgentDtos.AgentResult.builder()
                    .intent(intent.getIntent())
                    .status(AgentDtos.STATUS_COMPLETED)
                    .message("请告诉我日程事项，比如「明天下午3点开评审会」。")
                    .build());
        }
        ScheduleItem item = scheduleService.add(ctx.getUserId(), request.title(),
                request.date(), request.time(), null, request.recurrence(), null);
        String timeText = item.getTime() == null ? "" : item.getTime().toString().substring(0, 5) + " ";
        String repeatText = com.travel.agent.util.Recurrence.isRecurring(request.recurrence())
                ? "（" + com.travel.agent.util.Recurrence.label(request.recurrence()) + "）" : "";
        return complete(ctx, AgentDtos.AgentResult.builder()
                .intent(intent.getIntent())
                .status(AgentDtos.STATUS_COMPLETED)
                .message("📅 已加入日程：" + item.getDate() + " " + timeText + "「" + item.getTitle() + "」"
                        + repeatText + "。")
                .warnings(new ArrayList<>())
                .build());
    }

    /** 日程：查看 */
    private AgentDtos.AgentResult answerQuerySchedule(AgentContext ctx, IntentResult intent) {
        List<ScheduleItem> upcoming = scheduleService.upcoming(ctx.getUserId());
        if (upcoming.isEmpty()) {
            return complete(ctx, AgentDtos.AgentResult.builder()
                    .intent(intent.getIntent())
                    .status(AgentDtos.STATUS_COMPLETED)
                    .message("📅 日程是空的，说「明天下午3点开评审会」即可添加。")
                    .warnings(new ArrayList<>())
                    .build());
        }
        StringBuilder sb = new StringBuilder("📅 即将到来的日程：");
        for (ScheduleItem item : upcoming) {
            sb.append("\n· ").append(item.getDate()).append(" ")
                    .append(item.getTime() == null ? "" : item.getTime().toString().substring(0, 5) + " ")
                    .append(item.getTitle());
        }
        return complete(ctx, AgentDtos.AgentResult.builder()
                .intent(intent.getIntent())
                .status(AgentDtos.STATUS_COMPLETED)
                .message(sb.toString())
                .warnings(new ArrayList<>())
                .build());
    }

    /** 日报：汇总当日真实数据自动生成 */
    private AgentDtos.AgentResult answerDailyReport(AgentContext ctx, IntentResult intent) {
        DailyReport report = dailyReportService.generate(ctx.getUserId(), java.time.LocalDate.now());
        String brief = report.getContent().length() > 500
                ? report.getContent().substring(0, 500) + "……（完整日报已保存，可在办公模块查看）"
                : report.getContent();
        return complete(ctx, AgentDtos.AgentResult.builder()
                .intent(intent.getIntent())
                .status(AgentDtos.STATUS_COMPLETED)
                .message("📋 今日日报已生成：\n\n" + brief)
                .warnings(new ArrayList<>())
                .build());
    }

    /** 健康：最新数据 + 一周均值 + 建议 */
    private AgentDtos.AgentResult answerQueryHealth(AgentContext ctx, IntentResult intent) {
        com.travel.agent.entity.HealthRecord latest = healthService.latest(ctx.getUserId());
        double[] avg = healthService.weeklyAverages(ctx.getUserId());
        if (latest == null) {
            return complete(ctx, AgentDtos.AgentResult.builder()
                    .intent(intent.getIntent())
                    .status(AgentDtos.STATUS_COMPLETED)
                    .message("暂无健康数据。可在「❤️ 健康」模块导入小米手环演示数据，或手动记录。")
                    .warnings(new ArrayList<>())
                    .build());
        }
        String advice = healthAdviceText(latest, avg[0], avg[1]);
        return complete(ctx, AgentDtos.AgentResult.builder()
                .intent(intent.getIntent())
                .status(AgentDtos.STATUS_COMPLETED)
                .message("❤️ 最新健康数据（" + latest.getDate() + "）：步数 "
                        + (latest.getSteps() == null ? "-" : latest.getSteps())
                        + "，睡眠 " + (latest.getSleepHours() == null ? "-" : latest.getSleepHours() + " 小时")
                        + "，静息心率 " + (latest.getRestingHeartRate() == null ? "-" : latest.getRestingHeartRate())
                        + "。\n近 7 天均值：步数 " + (int) avg[0] + "，睡眠 " + String.format("%.1f", avg[1]) + " 小时。\n"
                        + advice)
                .warnings(new ArrayList<>())
                .build());
    }

    private String healthAdviceText(com.travel.agent.entity.HealthRecord latest, double avgSteps, double avgSleep) {
        StringBuilder sb = new StringBuilder();
        if (avgSteps < 6000) {
            sb.append("步数偏少，建议每天增加 30 分钟快走。");
        } else {
            sb.append("步数表现不错，保持规律运动。");
        }
        if (avgSleep < 7) {
            sb.append("睡眠不足 7 小时，建议提前 30 分钟入睡。");
        } else {
            sb.append("睡眠时长健康。");
        }
        if (latest.getRestingHeartRate() != null && latest.getRestingHeartRate() > 90) {
            sb.append("静息心率偏高，注意休息。");
        }
        return sb.toString();
    }

    private AgentDtos.AgentResult complete(AgentContext ctx, AgentDtos.AgentResult result) {
        ctx.emit("complete", result);
        return result;
    }

    // ==================== 确认 / 取消 / 撤销（Human-in-the-loop） ====================

    public AgentDtos.AgentResult confirm(String planId, Long userId) {
        // 先 peek 再提交，提交成功后才移除：提交失败时用户仍可重试，不必重新发起规划
        PendingPlanStore.PendingPlan pending = pendingPlans.peek(planId, userId);
        if (pending == null) {
            return AgentDtos.AgentResult.builder()
                    .status(AgentDtos.STATUS_FAILED)
                    .message("确认会话不存在或已过期，请重新发起请求。")
                    .build();
        }
        // 版本校验：方案生成后行程若被改过（另一个标签页/设备），这份 Diff 已不可信
        if (pending.tripId() != null) {
            Trip current = tripService.getOwned(pending.tripId(), userId);
            String currentRevision = ItineraryService.planRevision(current,
                    itineraryService.listEntities(current.getId()));
            if (pending.planRevision() != null && !pending.planRevision().equals(currentRevision)) {
                pendingPlans.remove(planId);
                return AgentDtos.AgentResult.builder()
                        .planId(planId)
                        .tripId(pending.tripId())
                        .status(AgentDtos.STATUS_FAILED)
                        .message("行程在你确认前已经发生变化（安排、预算或日期被修改），这份方案已失效，请重新发起调整。")
                        .build();
            }
        }
        AgentDtos.AgentResult result = executorAgent.commit(pending.plan(), userId);
        pendingPlans.remove(planId);
        return result;
    }

    public AgentDtos.AgentResult cancel(String planId, Long userId) {
        PendingPlanStore.PendingPlan pending = pendingPlans.peek(planId, userId);
        if (pending != null) {
            pendingPlans.remove(planId);
        }
        return AgentDtos.AgentResult.builder()
                .planId(planId)
                .status(AgentDtos.STATUS_COMPLETED)
                .message("已取消该方案，行程未做任何修改。")
                .build();
    }

    /**
     * 取回待确认方案。
     * 前端刷新页面后凭 planId 找回确认弹窗内容（之前刷新即丢，只能重新发起规划）。
     */
    public AgentDtos.AgentResult pendingPreview(String planId, Long userId) {
        PendingPlanStore.PendingPlan pending = pendingPlans.peek(planId, userId);
        if (pending == null) {
            throw new com.travel.agent.exception.BusinessException(404, "待确认方案不存在或已过期，请重新发起");
        }
        return pending.preview();
    }

    public AgentDtos.AgentResult undo(Long tripId, Long userId) {
        Trip trip = tripService.getOwned(tripId, userId);
        String message = operationHistoryService.undoLatest(trip, userId);
        return AgentDtos.AgentResult.builder()
                .tripId(tripId)
                .status(AgentDtos.STATUS_COMPLETED)
                .message(message)
                .build();
    }
}
