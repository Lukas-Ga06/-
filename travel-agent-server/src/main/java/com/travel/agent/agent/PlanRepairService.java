package com.travel.agent.agent;

import com.travel.agent.entity.ItineraryItem;
import com.travel.agent.entity.Trip;
import com.travel.agent.model.Attraction;
import com.travel.agent.model.CityCatalog;
import com.travel.agent.model.RouteInfo;
import com.travel.agent.model.WeatherLookup;
import com.travel.agent.service.RouteService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * 计划的确定性修复器（V2.2 FR-06「让审核真正改变结果」）。
 *
 * <p>只处理 HIGH 级硬约束，且**不调模型**：同样的输入必须得到同样的修复动作，理由还要能直接讲给用户听
 * （与 ADR-007 的预算收敛同一原则）。三类修复：
 * <ol>
 *   <li><b>雨天户外</b>：按天把户外景点换成同城评分最高/最近的室内景点（只换景点，不动时间与餐饮）；</li>
 *   <li><b>预算超限</b>：逐次移除最贵的景点，每天保底 1 个；<b>不拆</b>刚换上的雨天室内景点
 *       （否则会与天气修复互相抵消）；</li>
 *   <li><b>转场来不及</b>：把「来不及的那一段及之后的安排」整体后移，补足驾车时长 + 5 分钟缓冲；
 *       后移导致超过 23:30 时移除该条（宁可少一个点，也不产出不可能完成的行程）。</li>
 * </ol>
 *
 * <p>修复只改计划里的 ADD/MODIFY 草稿（未落库），REMOVE 的原条目与库中既有条目不动：
 * 所以「改成什么样」完全可由 Diff 预览看到，用户确认后才会生效。
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class PlanRepairService {

    /** 转场缓冲：留出 5 分钟找车/检票，避免把「刚好够」算成「来得及」 */
    private static final int TRANSFER_BUFFER_MINUTES = 5;
    /** 一天安排的最晚结束时间：后移超过它就不再硬塞 */
    private static final LocalTime LATEST_END = LocalTime.of(23, 30);

    private final PlanInspector inspector;
    private final RouteService routeService;

    public record Result(AgentPlan plan, List<String> actions) {
        public boolean changed() {
            return !actions.isEmpty();
        }
    }

    /**
     * 对计划做一轮确定性修复。
     *
     * <p>无论本轮审核报的是哪类 HIGH 问题，三类修复都会跑一遍（各自幂等、只改需要改的），
     * 这样「天气换室内导致超预算」「挪时间导致后段越界」这类连锁问题能在同一轮里收敛。
     */
    public Result repair(AgentPlan plan, Trip trip) {
        List<String> actions = new ArrayList<>();
        Set<String> weatherProtected = replaceRainyOutdoor(plan, trip, actions);
        trimToBudget(plan, trip, actions, weatherProtected);
        retimeTransfers(plan, trip, actions);
        if (!actions.isEmpty()) {
            log.info("计划重规划修复 {} 项: {}", actions.size(), actions);
        }
        return new Result(plan, actions);
    }

    // ==================== 1. 雨天户外 → 室内 ====================

    /**
     * @return 被换上来的室内景点标题（预算修复不得再移除它们，否则两轮修复会互相抵消）
     */
    private Set<String> replaceRainyOutdoor(AgentPlan plan, Trip trip, List<String> actions) {
        Set<String> protectedTitles = new HashSet<>();
        CityCatalog city = plan.getCity();
        if (city == null || city.attractions() == null || city.attractions().isEmpty()) {
            return protectedTitles;
        }
        Map<Integer, WeatherLookup> weatherByDay = inspector.weatherByDay(plan, trip);
        if (weatherByDay.isEmpty()) {
            return protectedTitles;
        }

        // 已被行程占用的景点名：替换时不得撞名
        Set<String> used = new HashSet<>();
        for (PlanOperation op : plan.getOperations()) {
            if (op.getDraft() != null && ItineraryItem.TYPE_ATTRACTION.equals(op.getDraft().getItemType())) {
                used.add(op.getDraft().getTitle());
            }
        }

        for (Map.Entry<Integer, WeatherLookup> entry : new TreeMap<>(weatherByDay).entrySet()) {
            // 只有「可用的真实预报」才改行程：不可用没有事实依据，模拟（仅测试开关）只提示（ADR-006）
            WeatherLookup lookup = entry.getValue();
            if (lookup == null || !lookup.available() || lookup.simulation()
                    || !ReflectAgent.likelyRainy(lookup.info())) {
                continue;
            }
            int day = entry.getKey();
            for (PlanOperation op : plan.getOperations()) {
                if (!PlanOperation.ADD.equals(op.getType()) || op.getDraft() == null) {
                    continue;
                }
                ItineraryItem draft = op.getDraft();
                if (!ItineraryItem.TYPE_ATTRACTION.equals(draft.getItemType()) || !draft.isOutdoor()) {
                    continue;
                }
                if (draft.getDayIndex() == null || draft.getDayIndex() != day) {
                    continue;
                }
                Attraction replacement = pickIndoor(city, draft, used);
                if (replacement == null) {
                    continue;
                }
                String previous = draft.getTitle();
                draft.setTitle(replacement.name());
                draft.setLatitude(replacement.lat());
                draft.setLongitude(replacement.lng());
                draft.setCost(BigDecimal.valueOf(replacement.ticket()));
                draft.setTags(String.join(",", replacement.tagsWithVenue()));
                draft.setDescription(replacement.description());
                op.setDetail("新增：" + replacement.name());
                used.add(replacement.name());
                protectedTitles.add(replacement.name());
                actions.add("Day " + day + " 将雨天户外景点「" + previous + "」换成室内景点「"
                        + replacement.name() + "」");
            }
        }
        return protectedTitles;
    }

    /** 室内候选：离原景点最近优先，同距离取评分高者（确定性，不随机） */
    private Attraction pickIndoor(CityCatalog city, ItineraryItem draft, Set<String> used) {
        return city.attractions().stream()
                .filter(a -> "indoor".equals(a.venue()))
                .filter(a -> !used.contains(a.name()))
                .min(Comparator
                        .comparingDouble((Attraction a) -> routeService.haversineKm(
                                draft.getLatitude(), draft.getLongitude(), a.lat(), a.lng()))
                        .thenComparing(Comparator.comparingDouble(Attraction::rating).reversed()))
                .orElse(null);
    }

    // ==================== 2. 预算超限 → 确定性精简 ====================

    private void trimToBudget(AgentPlan plan, Trip trip, List<String> actions, Set<String> protectedTitles) {
        // 局部修改计划不维护 extras.total（既有口径）：不在这里凭空引入预算判定
        if (!plan.getExtras().containsKey("total")) {
            return;
        }
        BigDecimal total = recomputeTotal(plan);
        plan.getExtras().put("total", total);
        BigDecimal budget = trip != null ? trip.getBudget()
                : (plan.getTripDraft() != null ? plan.getTripDraft().getBudget() : null);
        if (budget == null) {
            return;
        }
        while (total.compareTo(budget) > 0) {
            PlanOperation candidateOp = heaviestRemovableAttraction(plan, protectedTitles);
            if (candidateOp == null) {
                break; // 没有可移除项：保留超支，交回审核判定（最终 blocked）
            }
            ItineraryItem candidate = candidateOp.getDraft();
            BigDecimal cost = candidate.getCost() == null ? BigDecimal.ZERO : candidate.getCost();
            plan.getOperations().remove(candidateOp);
            total = recomputeTotal(plan);
            actions.add("为守住预算移除「" + candidate.getTitle() + "」（¥"
                    + cost.stripTrailingZeros().toPlainString() + "）");
        }
        plan.getExtras().put("total", total);
    }

    /** 最贵的可移除景点：每天至少留 1 个景点，且不碰雨天换上的室内景点 */
    private PlanOperation heaviestRemovableAttraction(AgentPlan plan, Set<String> protectedTitles) {
        Map<Integer, Long> attractionCountByDay = new HashMap<>();
        for (PlanOperation op : plan.getOperations()) {
            if (isAttraction(op)) {
                attractionCountByDay.merge(op.getDraft().getDayIndex(), 1L, Long::sum);
            }
        }
        PlanOperation candidate = null;
        for (PlanOperation op : plan.getOperations()) {
            if (!isAttraction(op)) {
                continue;
            }
            ItineraryItem draft = op.getDraft();
            int day = draft.getDayIndex() == null ? 1 : draft.getDayIndex();
            if (attractionCountByDay.getOrDefault(day, 0L) <= 1) {
                continue;
            }
            if (protectedTitles.contains(draft.getTitle())) {
                continue;
            }
            if (candidate == null || costOf(draft).compareTo(costOf(candidate.getDraft())) > 0) {
                candidate = op;
            }
        }
        return candidate;
    }

    private static boolean isAttraction(PlanOperation op) {
        return (PlanOperation.ADD.equals(op.getType()) || PlanOperation.MODIFY.equals(op.getType()))
                && op.getDraft() != null
                && ItineraryItem.TYPE_ATTRACTION.equals(op.getDraft().getItemType());
    }

    private static BigDecimal costOf(ItineraryItem item) {
        return item.getCost() == null ? BigDecimal.ZERO : item.getCost();
    }

    /** 计划合计 = 全部 ADD/MODIFY 草稿费用之和（与 PlannerAgent 的 extras.total 同口径） */
    private static BigDecimal recomputeTotal(AgentPlan plan) {
        BigDecimal total = BigDecimal.ZERO;
        for (PlanOperation op : plan.getOperations()) {
            if ((PlanOperation.ADD.equals(op.getType()) || PlanOperation.MODIFY.equals(op.getType()))
                    && op.getDraft() != null) {
                total = total.add(costOf(op.getDraft()));
            }
        }
        return total;
    }

    // ==================== 3. 转场来不及 → 整体后移 ====================

    /**
     * 对每天可编辑（ADD/MODIFY）的条目按开始时间顺序重排：
     * 一旦相邻两段之间留的时间 < 驾车时长 + 缓冲，就把后一段及其之后整体后移；
     * 后移后若最后一条结束超过 {@link #LATEST_END}，移除该条并记录（不产出不可完成的一天）。
     */
    private void retimeTransfers(AgentPlan plan, Trip trip, List<String> actions) {
        Map<Integer, List<ItineraryItem>> byDay = new LinkedHashMap<>();
        Map<ItineraryItem, PlanOperation> owner = new IdentityHashMap<>();
        for (PlanOperation op : plan.getOperations()) {
            if (!PlanOperation.ADD.equals(op.getType()) && !PlanOperation.MODIFY.equals(op.getType())) {
                continue;
            }
            ItineraryItem draft = op.getDraft();
            if (draft == null || draft.getStartTime() == null
                    || draft.getLatitude() == null || draft.getLongitude() == null) {
                continue;
            }
            int day = draft.getDayIndex() == null ? 1 : draft.getDayIndex();
            byDay.computeIfAbsent(day, k -> new ArrayList<>()).add(draft);
            owner.put(draft, op);
        }

        for (Map.Entry<Integer, List<ItineraryItem>> entry : new TreeMap<>(byDay).entrySet()) {
            List<ItineraryItem> items = entry.getValue();
            items.sort(Comparator.comparing(ItineraryItem::getStartTime));
            int i = 0;
            while (i + 1 < items.size()) {
                ItineraryItem from = items.get(i);
                ItineraryItem to = items.get(i + 1);
                RouteInfo route = routeService.route(from.getLatitude(), from.getLongitude(),
                        to.getLatitude(), to.getLongitude());
                int need = route.driveMinutes() + TRANSFER_BUFFER_MINUTES;
                long gap = Duration.between(PlanInspector.endOf(from), to.getStartTime()).toMinutes();
                if (gap >= need) {
                    i++;
                    continue;
                }
                long delta = need - gap;
                for (int j = i + 1; j < items.size(); j++) {
                    shift(items.get(j), delta);
                }
                actions.add("Day " + entry.getKey() + " 把「" + to.getTitle() + "」及之后的安排整体后移 "
                        + delta + " 分钟（转场驾车约 " + route.driveMinutes() + " 分钟）");

                ItineraryItem last = items.get(items.size() - 1);
                // 用「当天日期 + 最晚结束时刻」比较：只比 toLocalTime 的话，跨过午夜的 00:40 会被误判成「很早」
                if (last.getEndTime() != null && last.getEndTime()
                        .isAfter(last.getStartTime().toLocalDate().atTime(LATEST_END))) {
                    // 后移越界：移除最后一条，避免出现半夜还在景点的行程
                    PlanOperation removed = owner.remove(last);
                    if (removed != null) {
                        plan.getOperations().remove(removed);
                    }
                    items.remove(items.size() - 1);
                    actions.add("Day " + entry.getKey() + " 移除「" + last.getTitle() + "」（当天时间窗口不足）");
                }
                i++;
            }
        }
    }

    private static void shift(ItineraryItem item, long minutes) {
        if (item.getStartTime() != null) {
            item.setStartTime(item.getStartTime().plusMinutes(minutes));
        }
        if (item.getEndTime() != null) {
            item.setEndTime(item.getEndTime().plusMinutes(minutes));
        }
    }
}