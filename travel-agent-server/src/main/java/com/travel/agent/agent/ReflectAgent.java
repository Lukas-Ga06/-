package com.travel.agent.agent;

import com.travel.agent.entity.ItineraryItem;
import com.travel.agent.entity.Trip;
import com.travel.agent.service.ItineraryService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Reflect Agent：对计划做事后校验——
 * 时间冲突？预算超限？路线合理？发现问题输出警告，必要时触发 REPLAN。
 */
@Component
@RequiredArgsConstructor
public class ReflectAgent {

    private final ItineraryService itineraryService;

    public List<String> review(AgentPlan plan, Trip trip) {
        List<String> warnings = new ArrayList<>();

        // 1. 预算检查
        if (plan.getExtras().get("total") instanceof BigDecimal total) {
            BigDecimal budget = trip != null ? trip.getBudget()
                    : (plan.getTripDraft() != null ? plan.getTripDraft().getBudget() : null);
            if (budget != null && total.compareTo(budget) > 0) {
                warnings.add("预计花费 ¥" + total.stripTrailingZeros().toPlainString()
                        + " 已超出预算 ¥" + budget.stripTrailingZeros().toPlainString() + "，可让我再精简一下");
            }
        }

        // 2. 时间冲突检查（模拟应用操作后的行程）
        Map<Integer, List<ItineraryItem>> byDay = new HashMap<>();
        if (trip != null && plan.getTripDraft() == null) {
            List<Long> removedIds = plan.getOperations().stream()
                    .filter(op -> PlanOperation.REMOVE.equals(op.getType()))
                    .map(PlanOperation::getTargetItemId).toList();
            for (ItineraryItem item : itineraryService.listEntities(trip.getId())) {
                if (!removedIds.contains(item.getId())) {
                    byDay.computeIfAbsent(item.getDayIndex(), k -> new ArrayList<>()).add(item);
                }
            }
        }
        for (PlanOperation op : plan.getOperations()) {
            if (PlanOperation.ADD.equals(op.getType()) && op.getDraft().getStartTime() != null) {
                byDay.computeIfAbsent(op.getDraft().getDayIndex(), k -> new ArrayList<>()).add(op.getDraft());
            }
        }
        for (Map.Entry<Integer, List<ItineraryItem>> entry : byDay.entrySet()) {
            List<ItineraryItem> items = entry.getValue().stream()
                    .filter(i -> i.getStartTime() != null && i.getEndTime() != null)
                    .sorted(java.util.Comparator.comparing(ItineraryItem::getStartTime))
                    .toList();
            for (int i = 1; i < items.size(); i++) {
                ItineraryItem prev = items.get(i - 1);
                ItineraryItem curr = items.get(i);
                if (curr.getStartTime().isBefore(prev.getEndTime())) {
                    warnings.add("Day " + entry.getKey() + " 的「" + prev.getTitle() + "」与「"
                            + curr.getTitle() + "」时间冲突，建议手动微调");
                    break;
                }
            }
            if (warnings.size() >= 3) {
                break;
            }
        }
        return warnings;
    }
}
