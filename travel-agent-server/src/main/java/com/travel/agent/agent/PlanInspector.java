package com.travel.agent.agent;

import com.travel.agent.entity.ItineraryItem;
import com.travel.agent.entity.Trip;
import com.travel.agent.model.RouteInfo;
import com.travel.agent.model.WeatherLookup;
import com.travel.agent.service.ItineraryService;
import com.travel.agent.service.RouteService;
import com.travel.agent.service.WeatherService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * 计划的「事实视图」（V2.2 FR-06 审核与修复共用口径）。
 *
 * <p>本次抽取前，{@link ReflectAgent} 自己维护一份「应用计划之后」的行程视图；
 * 现在审核（发现问题）与 {@link PlanRepairService}（修复问题）必须看到同一份事实，
 * 否则会出现「审核说转场不够、修复却按另一组条目去挪时间」的对不齐。
 *
 * <p>三类事实：
 * <ul>
 *   <li>{@link #effectiveItemsByDay}：应用计划之后的逐日行程（REMOVE 去掉、MODIFY 合并、ADD 补上）；</li>
 *   <li>{@link #transferLegs}：逐日相邻条目的转场路线（真实/估算时长与可用时间窗口）；</li>
 *   <li>{@link #weatherByDay}：逐日天气（按行程出发日期推导每天的真实日期）。</li>
 * </ul>
 *
 * <p>只做只读推导，不改库、不改计划；异常一律降级为空事实（审核不能因为路线/天气查询失败而挡住规划）。
 */
@Component
@RequiredArgsConstructor
public class PlanInspector {

    private final ItineraryService itineraryService;
    private final RouteService routeService;
    private final WeatherService weatherService;

    /**
     * 一天内的一段转场。
     *
     * @param gapMinutes 上一段结束到下一段开始之间可用的分钟数（可为负：时间重叠）
     */
    public record DayLeg(int dayIndex, ItineraryItem from, ItineraryItem to, RouteInfo route, int gapMinutes) {
    }

    /**
     * 应用计划之后的行程视图：按天分组，含 REMOVE / MODIFY / ADD 三种操作的最终效果。
     *
     * <p>新行程（plan.tripDraft 存在）只有 ADD；重新规划已有行程时规划器会先 REMOVE 全部旧条目再 ADD，
     * 因此只看 ADD 即可；只有「在现有行程上做局部修改」（tripDraft 为空）才需要把库里的条目与操作合并。
     */
    public Map<Integer, List<ItineraryItem>> effectiveItemsByDay(AgentPlan plan, Trip trip) {
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

    /** 行程的出发日期：新行程取草案日期，局部修改取行程本身 */
    public LocalDate baseDate(AgentPlan plan, Trip trip) {
        if (plan.getTripDraft() != null && plan.getTripDraft().getStartDate() != null) {
            return plan.getTripDraft().getStartDate();
        }
        return trip == null ? null : trip.getStartDate();
    }

    /** 城市名：优先计划匹配到的目录城市，其次行程目的地 */
    public String cityName(AgentPlan plan, Trip trip) {
        if (plan.getCity() != null && plan.getCity().name() != null && !plan.getCity().name().isBlank()) {
            return plan.getCity().name();
        }
        return trip == null ? null : trip.getDestination();
    }

    /**
     * 逐日天气（只有行程里出现的天才查，避免无谓请求）。
     *
     * <p>没有出发日期或城市时返回空 Map——审核侧据此跳过天气约束，绝不猜一个日期去查。
     * <b>查不到就是不可用</b>：每个值都是 {@link WeatherLookup}（可用预报或不可用 + 原因），
     * 这里不会用模拟数据补位，审核/修复按 {@code available()} 自行决定「跳过」「如实告警」还是「明确拒绝」。
     */
    public Map<Integer, WeatherLookup> weatherByDay(AgentPlan plan, Trip trip) {
        LocalDate base = baseDate(plan, trip);
        String city = cityName(plan, trip);
        if (base == null || city == null || city.isBlank()) {
            return Map.of();
        }
        Map<Integer, WeatherLookup> weather = new TreeMap<>();
        for (Integer day : effectiveItemsByDay(plan, trip).keySet()) {
            int index = day == null ? 1 : day;
            LocalDate date = base.plusDays(Math.max(index, 1) - 1L);
            weather.put(index, weatherService.forecast(city, date));
        }
        return weather;
    }

    /**
     * 逐日相邻条目的转场：只取「同时有坐标与开始时间」的条目，按开始时间排序后两两成段。
     * 无坐标条目（交通费汇总等）不参与，避免算出假路线。
     */
    public List<DayLeg> transferLegs(AgentPlan plan, Trip trip) {
        Map<Integer, List<ItineraryItem>> byDay = effectiveItemsByDay(plan, trip);
        List<DayLeg> legs = new ArrayList<>();
        for (Map.Entry<Integer, List<ItineraryItem>> entry : new TreeMap<>(byDay).entrySet()) {
            List<ItineraryItem> items = entry.getValue().stream()
                    .filter(i -> i.getStartTime() != null && i.getLatitude() != null && i.getLongitude() != null)
                    .sorted(Comparator.comparing(ItineraryItem::getStartTime))
                    .toList();
            for (int i = 0; i + 1 < items.size(); i++) {
                ItineraryItem from = items.get(i);
                ItineraryItem to = items.get(i + 1);
                RouteInfo route = routeService.route(from.getLatitude(), from.getLongitude(),
                        to.getLatitude(), to.getLongitude());
                long gap = Duration.between(endOf(from), to.getStartTime()).toMinutes();
                legs.add(new DayLeg(entry.getKey(), from, to, route, (int) gap));
            }
        }
        return legs;
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

    /** 结束时间缺失时按 1 小时估算，否则「只有开始时间」的条目永远不会参与转场/冲突判断 */
    public static LocalDateTime endOf(ItineraryItem item) {
        if (item.getEndTime() != null) {
            return item.getEndTime();
        }
        return item.getStartTime() == null ? LocalDateTime.MAX : item.getStartTime().plusHours(1);
    }
}