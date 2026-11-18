package com.travel.agent.agent;

import com.travel.agent.entity.ItineraryItem;
import com.travel.agent.entity.Trip;
import com.travel.agent.service.ItineraryService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Reflect Agent：对计划做事后校验——时间冲突？预算超限？发现问题输出警告。
 *
 * 时间冲突基于「应用计划之后」的行程视图判断：
 * 原条目去掉被 REMOVE 的、被 MODIFY 的换成新时间、再加上 ADD 的条目。
 * （旧实现只看 ADD，因此"把某个安排改到与另一个重叠"永远不会被发现；也只比较相邻两项。）
 *
 * <p>V2.2 FR-06：{@link #reviewVerdict} 输出结构化判定（PASS/REVISE + 问题清单），
 * {@link #review} 是它的警告文案投影——对外契约不变，前端与既有测试零改动。
 */
@Component
@RequiredArgsConstructor
public class ReflectAgent {

    /** 单次最多返回几条时间冲突提示，避免刷屏 */
    private static final int MAX_TIME_WARNINGS = 5;

    private final ItineraryService itineraryService;

    /**
     * 结构化审核判定（V2.2 FR-06）。
     *
     * <p>passed=false（REVISE）表示存在硬约束问题（当前：预算超限），
     * 需要规划侧自愈或用户修改；时间冲突是软性提醒（建议手动微调），不阻断确认。
     */
    public record PlanIssue(String type, String severity, String message) {
        public static final String TYPE_BUDGET = "BUDGET_OVERRUN";
        public static final String TYPE_TIME_CONFLICT = "TIME_CONFLICT";
        public static final String SEVERITY_HIGH = "HIGH";
        public static final String SEVERITY_MEDIUM = "MEDIUM";
    }

    public record ReviewVerdict(boolean passed, List<PlanIssue> issues, List<String> warnings) {
        /** 便捷投影：问题清单转警告文案（与旧 review() 输出一致） */
        public List<String> warningLines() {
            return warnings;
        }
    }

    /** 旧契约：返回警告文案列表（内部委托给结构化判定） */
    public List<String> review(AgentPlan plan, Trip trip) {
        return reviewVerdict(plan, trip).warnings();
    }

    /** 结构化判定：预算超限（HIGH，REVISE）+ 时间冲突（MEDIUM，提示） */
    public ReviewVerdict reviewVerdict(AgentPlan plan, Trip trip) {
        List<PlanIssue> issues = new ArrayList<>();
        List<String> warnings = new ArrayList<>();

        // 1. 预算检查（硬约束：REVISE 的判定依据）
        if (plan.getExtras().get("total") instanceof BigDecimal total) {
            BigDecimal budget = trip != null ? trip.getBudget()
                    : (plan.getTripDraft() != null ? plan.getTripDraft().getBudget() : null);
            if (budget != null && total.compareTo(budget) > 0) {
                String message = "预计花费 ¥" + total.stripTrailingZeros().toPlainString()
                        + " 已超出预算 ¥" + budget.stripTrailingZeros().toPlainString() + "，可让我再精简一下";
                issues.add(new PlanIssue(PlanIssue.TYPE_BUDGET, PlanIssue.SEVERITY_HIGH, message));
                warnings.add(message);
            }
        }

        // 2. 时间冲突检查（应用计划后的行程，逐天两两比对）
        int conflicts = 0;
        for (Map.Entry<Integer, List<ItineraryItem>> entry : effectiveItemsByDay(plan, trip).entrySet()) {
            List<ItineraryItem> items = entry.getValue().stream()
                    .filter(i -> i.getStartTime() != null)
                    .sorted(Comparator.comparing(ItineraryItem::getStartTime))
                    .toList();
            for (int i = 0; i < items.size() && conflicts < MAX_TIME_WARNINGS; i++) {
                for (int j = i + 1; j < items.size() && conflicts < MAX_TIME_WARNINGS; j++) {
                    ItineraryItem earlier = items.get(i);
                    ItineraryItem later = items.get(j);
                    if (later.getStartTime().isBefore(endOf(earlier))) {
                        String message = "Day " + entry.getKey() + " 的「" + earlier.getTitle() + "」与「"
                                + later.getTitle() + "」时间重叠，建议手动微调";
                        issues.add(new PlanIssue(PlanIssue.TYPE_TIME_CONFLICT, PlanIssue.SEVERITY_MEDIUM, message));
                        warnings.add(message);
                        conflicts++;
                    }
                }
            }
            if (conflicts >= MAX_TIME_WARNINGS) {
                break;
            }
        }
        boolean passed = issues.stream().noneMatch(issue -> PlanIssue.SEVERITY_HIGH.equals(issue.severity()));
        return new ReviewVerdict(passed, issues, warnings);
    }

    /** 应用计划之后的行程视图：按天分组，含 REMOVE / MODIFY / ADD 三种操作的最终效果 */
    private Map<Integer, List<ItineraryItem>> effectiveItemsByDay(AgentPlan plan, Trip trip) {
        Map<Integer, List<ItineraryItem>> byDay = new HashMap<>();
        Set<Long> removed = new HashSet<>();
        Map<Long, ItineraryItem> modified = new HashMap<>();
        List<ItineraryItem> added = new ArrayList<>();

        for (PlanOperation op : plan.getOperations()) {
            switch (op.getType()) {
                case PlanOperation.REMOVE -> {
                    if (op.getTargetItemId() != null) {
                        removed.add(op.getTargetItemId());
                    }
                }
                case PlanOperation.MODIFY -> {
                    if (op.getTargetItemId() != null && op.getDraft() != null) {
                        modified.put(op.getTargetItemId(), op.getDraft());
                    }
                }
                case PlanOperation.ADD -> {
                    if (op.getDraft() != null) {
                        added.add(op.getDraft());
                    }
                }
                default -> {
                    // 未知操作类型会在 Executor 阶段被拒绝，这里忽略即可
                }
            }
        }

        if (trip != null && plan.getTripDraft() == null) {
            for (ItineraryItem item : itineraryService.listEntities(trip.getId())) {
                if (removed.contains(item.getId())) {
                    continue;
                }
                ItineraryItem draft = modified.get(item.getId());
                ItineraryItem effective = draft == null ? item : merge(item, draft);
                if (effective.getStartTime() != null) {
                    byDay.computeIfAbsent(effective.getDayIndex(), k -> new ArrayList<>()).add(effective);
                }
            }
        }
        for (ItineraryItem draft : added) {
            if (draft.getStartTime() != null) {
                byDay.computeIfAbsent(draft.getDayIndex(), k -> new ArrayList<>()).add(draft);
            }
        }
        return byDay;
    }

    /** MODIFY 的草稿可能只填了部分字段：以原条目为底、用非空字段覆盖，避免误判 */
    private static ItineraryItem merge(ItineraryItem base, ItineraryItem patch) {
        return ItineraryItem.builder()
                .id(base.getId())
                .tripId(base.getTripId())
                .dayIndex(patch.getDayIndex() == null ? base.getDayIndex() : patch.getDayIndex())
                .itemType(patch.getItemType() == null ? base.getItemType() : patch.getItemType())
                .title(patch.getTitle() == null ? base.getTitle() : patch.getTitle())
                .startTime(patch.getStartTime() == null ? base.getStartTime() : patch.getStartTime())
                .endTime(patch.getEndTime() == null ? base.getEndTime() : patch.getEndTime())
                .build();
    }

    /** 结束时间缺失时按 1 小时估算，否则"只有开始时间"的条目永远不会参与冲突判断 */
    private static LocalDateTime endOf(ItineraryItem item) {
        if (item.getEndTime() != null) {
            return item.getEndTime();
        }
        return item.getStartTime() == null ? LocalDateTime.MAX : item.getStartTime().plusHours(1);
    }
}
