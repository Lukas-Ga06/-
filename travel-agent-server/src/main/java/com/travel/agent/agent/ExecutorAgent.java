package com.travel.agent.agent;

import com.travel.agent.dto.AgentDtos;
import com.travel.agent.dto.ItineraryDtos;
import com.travel.agent.entity.ItineraryItem;
import com.travel.agent.entity.Trip;
import com.travel.agent.service.BudgetService;
import com.travel.agent.service.ItineraryService;
import com.travel.agent.service.OperationHistoryService;
import com.travel.agent.service.TripService;
import com.travel.agent.tool.RiskLevel;
import com.travel.agent.tool.SafetyInterceptor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * Executor Agent：不直接操作数据库。
 * preview：把计划转成 Diff 预览（Before → After），等待用户确认；
 * commit：用户确认后经 Service → Repository 落库，并写入操作历史（支持撤销）。
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ExecutorAgent {

    private final BudgetService budgetService;
    private final ItineraryService itineraryService;
    private final TripService tripService;
    private final OperationHistoryService historyService;
    private final SafetyInterceptor safetyInterceptor;

    /** 生成 Diff 预览与确认请求 */
    public AgentDtos.AgentResult preview(AgentContext ctx, AgentPlan plan, Trip trip) {
        List<AgentDtos.DiffChange> changes = new ArrayList<>();
        BigDecimal removed = BigDecimal.ZERO;
        BigDecimal added = BigDecimal.ZERO;

        for (PlanOperation op : plan.getOperations()) {
            switch (op.getType()) {
                case PlanOperation.ADD -> {
                    ItineraryItem d = op.getDraft();
                    changes.add(AgentDtos.DiffChange.builder()
                            .type("ADD").itemType(d.getItemType()).title(d.getTitle())
                            .detail(op.getDetail()).cost(d.getCost()).dayIndex(d.getDayIndex())
                            .startTime(d.getStartTime() == null ? null : d.getStartTime().toString())
                            .build());
                    if (d.getCost() != null) added = added.add(d.getCost());
                }
                case PlanOperation.REMOVE -> {
                    ItineraryItem o = op.getOriginal();
                    changes.add(AgentDtos.DiffChange.builder()
                            .type("REMOVE")
                            .itemId(op.getTargetItemId())
                            .itemType(o == null ? null : o.getItemType())
                            .title(o == null ? null : o.getTitle())
                            .detail(op.getDetail())
                            .cost(o == null ? null : o.getCost())
                            .dayIndex(o == null ? null : o.getDayIndex())
                            .startTime(o == null || o.getStartTime() == null ? null : o.getStartTime().toString())
                            .build());
                    if (o != null && o.getCost() != null) removed = removed.add(o.getCost());
                }
                case PlanOperation.MODIFY -> {
                    ItineraryItem d = op.getDraft();
                    changes.add(AgentDtos.DiffChange.builder()
                            .type("MODIFY").itemId(op.getTargetItemId()).itemType(d.getItemType())
                            .title(d.getTitle()).detail(op.getDetail()).cost(d.getCost())
                            .dayIndex(d.getDayIndex()).build());
                    if (d.getCost() != null) added = added.add(d.getCost());
                }
                default -> log.warn("未知操作类型: {}", op.getType());
            }
        }

        BigDecimal before = trip != null ? budgetService.totalOf(itineraryService.listEntities(trip.getId()))
                : BigDecimal.ZERO;
        BigDecimal after = before.subtract(removed).add(added);
        AgentDtos.PlanDiff diff = AgentDtos.PlanDiff.builder()
                .changes(changes).budgetBefore(before).budgetAfter(after).build();
        AgentDtos.BudgetSummary budget = AgentDtos.BudgetSummary.builder()
                .total(after).previousTotal(before).difference(after.subtract(before)).build();

        BigDecimal referenceBudget = trip != null ? trip.getBudget()
                : (plan.getTripDraft() != null ? plan.getTripDraft().getBudget() : null);
        RiskLevel risk = safetyInterceptor.evaluatePlanChange(plan.getOperations().isEmpty(), before, after, referenceBudget);

        String summary = "本次将删除 " + changes.stream().filter(c -> "REMOVE".equals(c.getType())).count()
                + " 项、新增 " + changes.stream().filter(c -> "ADD".equals(c.getType())).count()
                + " 项，预算 ¥" + before.stripTrailingZeros().toPlainString() + " → ¥" + after.stripTrailingZeros().toPlainString();

        return AgentDtos.AgentResult.builder()
                .planId(plan.getPlanId())
                .tripId(trip == null ? null : trip.getId())
                .intent(plan.getIntent())
                .status(AgentDtos.STATUS_NEED_CONFIRMATION)
                .focus(plan.getFocus())
                .diff(diff)
                .budget(budget)
                .warnings(new ArrayList<>())
                .confirmation(AgentDtos.ConfirmationRequest.builder()
                        .planId(plan.getPlanId()).summary(summary).riskLevel(risk.name()).build())
                .build();
    }

    /**
     * 用户确认后真正提交：写入数据库并记录操作历史。
     * 必须整体处于同一事务中——否则「行程已改、撤销历史未写入」会让用户无法撤销。
     */
    @Transactional
    public AgentDtos.AgentResult commit(AgentPlan plan, Long userId) {
        // 有 tripId = 重新规划已有行程（REMOVE+ADD 落到原行程）；没有 tripId 才是新建
        if (plan.getTripId() == null && plan.getTripDraft() != null) {
            List<ItineraryItem> items = plan.getOperations().stream()
                    .filter(op -> PlanOperation.ADD.equals(op.getType()))
                    .map(PlanOperation::getDraft)
                    .toList();
            Trip trip = tripService.createFromAgent(userId, plan.getTripDraft(), items);
            historyService.recordTripCreate(trip, userId,
                    itineraryService.listEntities(trip.getId()), plan.getReasoning());
            AgentDtos.BudgetSummary budget = budgetService.summarize(items);
            ItineraryDtos.ItineraryItemDTO first = itineraryService.listByTrip(trip.getId()).stream()
                    .findFirst().orElse(null);
            return AgentDtos.AgentResult.builder()
                    .planId(plan.getPlanId())
                    .tripId(trip.getId())
                    .intent(plan.getIntent())
                    .status(AgentDtos.STATUS_COMPLETED)
                    .message("行程已保存，祝你旅途愉快！可以在对话里随时让我调整。")
                    .focus(AgentDtos.AgentFocus.builder().type("itinerary").tripId(trip.getId())
                            .day(1).itemId(first == null ? null : first.getId()).build())
                    .budget(budget)
                    .build();
        }

        Trip trip = tripService.getOwned(plan.getTripId(), userId);
        List<ItineraryItem> before = itineraryService.listEntities(trip.getId());
        itineraryService.applyOperations(trip.getId(), plan.getOperations());
        List<ItineraryItem> after = itineraryService.listEntities(trip.getId());
        historyService.recordCommit(trip, userId, before, after, plan.getReasoning());

        Long focusItemId = plan.getOperations().stream()
                .filter(op -> PlanOperation.ADD.equals(op.getType()))
                .map(PlanOperation::getDraft)
                .map(ItineraryItem::getId)
                .filter(id -> id != null)
                .findFirst().orElse(null);
        return AgentDtos.AgentResult.builder()
                .planId(plan.getPlanId())
                .tripId(trip.getId())
                .intent(plan.getIntent())
                .status(AgentDtos.STATUS_COMPLETED)
                .message("修改已保存，行程已更新。如不满意可点击「撤销」。")
                .focus(AgentDtos.AgentFocus.builder().type("itinerary").tripId(trip.getId())
                        .day(plan.getFocus() == null ? null : plan.getFocus().getDay())
                        .itemId(focusItemId).build())
                .budget(budgetService.summarize(after, budgetService.totalOf(before)))
                .build();
    }
}
