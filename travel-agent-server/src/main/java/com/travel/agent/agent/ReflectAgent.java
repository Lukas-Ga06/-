package com.travel.agent.agent;

import com.travel.agent.entity.ItineraryItem;
import com.travel.agent.entity.Trip;
import com.travel.agent.model.RouteInfo;
import com.travel.agent.model.WeatherInfo;
import com.travel.agent.model.WeatherLookup;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * Reflect Agent：对计划做事后校验——预算？转场时间够不够？雨天还排户外？时间重叠？
 *
 * <p>时间冲突基于「应用计划之后」的行程视图判断（{@link PlanInspector#effectiveItemsByDay}）：
 * 原条目去掉被 REMOVE 的、被 MODIFY 的换成新时间、再加上 ADD 的条目。
 * （旧实现只看 ADD，因此「把某个安排改到与另一个重叠」永远不会被发现；也只比较相邻两项。）
 *
 * <p>V2.2 FR-06：{@link #reviewVerdict} 输出结构化判定（passed + 问题清单），
 * 硬约束（预算超限 / 转场时间不够 / 雨天户外）会触发引擎的有限重规划；
 * 时间重叠仍是软提醒。{@link #review} 是它的警告文案投影，对外契约不变。
 *
 * <p>「转场不够」用 {@link RouteInfo#driveMinutes()} 与两个安排之间的真实空档比较——
 * 规划产物里的交通方式口径就是驾车/打车，所以按驾车时长判定才与预算里那笔打车费一致。
 *
 * <p>天气约束只在<b>真实预报</b>下算硬约束：拿不到真实天气时 {@link PlanInspector#weatherByDay} 返回
 * {@code available=false}，此时只能如实提示「未获取真实天气（原因），本次未做雨天/户外校验」，
 * 不能凭不存在的事实判 HIGH，更不能拿模拟的「小雨」去强制改用户行程（ADR-006 的延续）。
 * 模拟天气只可能在 {@code travel.weather.simulation=true}（仅测试/离线演示）下出现，此时只给 MEDIUM 提示并标注「模拟天气」。
 */
@Component
@RequiredArgsConstructor
public class ReflectAgent {

    /** 单次最多返回几条时间冲突提示，避免刷屏 */
    private static final int MAX_TIME_WARNINGS = 5;
    /** 单次最多返回几条转场不可行提示 */
    private static final int MAX_TRANSFER_ISSUES = 5;
    /** 单次最多返回几天天气提示 */
    private static final int MAX_WEATHER_ISSUES = 5;
    /** 雨天判定阈值：降雨概率达到该值才认为「大概率下雨」 */
    public static final int RAIN_PROBABILITY_THRESHOLD = 60;

    private final PlanInspector inspector;

    /**
     * 结构化审核判定（V2.2 FR-06）。
     *
     * <p>passed=false（REVISE）表示存在 HIGH 级硬约束问题，需要修复器重规划或用户处理；
     * MEDIUM（时间重叠、模拟天气提示）不阻断确认。
     */
    public record PlanIssue(String type, String severity, String message) {
        public static final String TYPE_BUDGET = "BUDGET_OVERRUN";
        public static final String TYPE_TIME_CONFLICT = "TIME_CONFLICT";
        public static final String TYPE_TRANSFER_TIME = "TRANSFER_TIME";
        public static final String TYPE_WEATHER_OUTDOOR = "WEATHER_OUTDOOR";
        public static final String SEVERITY_HIGH = "HIGH";
        public static final String SEVERITY_MEDIUM = "MEDIUM";
    }

    public record ReviewVerdict(boolean passed, List<PlanIssue> issues, List<String> warnings) {
        /** 便捷投影：问题清单转警告文案（与旧 review() 输出一致） */
        public List<String> warningLines() {
            return warnings;
        }

        public List<PlanIssue> highIssues() {
            return issues.stream()
                    .filter(issue -> PlanIssue.SEVERITY_HIGH.equals(issue.severity()))
                    .toList();
        }
    }

    /** 旧契约：返回警告文案列表（内部委托给结构化判定） */
    public List<String> review(AgentPlan plan, Trip trip) {
        return reviewVerdict(plan, trip).warnings();
    }

    /** 结构化判定：预算 / 转场 / 天气（硬约束，HIGH）+ 时间冲突（软提醒，MEDIUM） */
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

        // 2. 转场可行性（硬约束：留的时间连路上都不够）
        //    只对「整份规划」生效：局部修改（MODIFY_ITINERARY）只改一小段，
        //    拿整条既有行程去判硬约束会因为历史数据里早已存在的紧排班而阻止一次合理修改。
        if (IntentResult.PLAN_TRIP.equals(plan.getIntent())) {
            reviewTransfers(plan, trip, issues, warnings);
            // 3. 天气约束（真实预报：雨天排户外 = 硬约束；模拟天气只提示）
            reviewWeather(plan, trip, issues, warnings);
        }

        // 4. 时间冲突检查（应用计划后的行程，逐天两两比对）
        reviewTimeConflicts(plan, trip, issues, warnings);

        boolean passed = issues.stream().noneMatch(issue -> PlanIssue.SEVERITY_HIGH.equals(issue.severity()));
        return new ReviewVerdict(passed, issues, warnings);
    }

    private void reviewTransfers(AgentPlan plan, Trip trip, List<PlanIssue> issues, List<String> warnings) {
        int found = 0;
        for (PlanInspector.DayLeg leg : inspector.transferLegs(plan, trip)) {
            if (found >= MAX_TRANSFER_ISSUES) {
                break;
            }
            int need = leg.route().driveMinutes();
            if (need <= 0 || leg.gapMinutes() >= need) {
                continue;
            }
            String message = "Day " + leg.dayIndex() + "「" + leg.from().getTitle() + "」→「"
                    + leg.to().getTitle() + "」转场驾车约 " + need + " 分钟"
                    + sourceHint(leg.route()) + "，但只留了 " + Math.max(leg.gapMinutes(), 0) + " 分钟";
            issues.add(new PlanIssue(PlanIssue.TYPE_TRANSFER_TIME, PlanIssue.SEVERITY_HIGH, message));
            warnings.add(message);
            found++;
        }
    }

    private void reviewWeather(AgentPlan plan, Trip trip, List<PlanIssue> issues, List<String> warnings) {
        Map<Integer, WeatherLookup> weatherByDay = inspector.weatherByDay(plan, trip);
        if (weatherByDay.isEmpty()) {
            return;
        }
        Map<Integer, List<ItineraryItem>> itemsByDay = inspector.effectiveItemsByDay(plan, trip);
        int found = 0;
        for (Map.Entry<Integer, WeatherLookup> entry : weatherByDay.entrySet()) {
            if (found >= MAX_WEATHER_ISSUES) {
                break;
            }
            WeatherLookup lookup = entry.getValue();
            if (lookup == null || !lookup.available()) {
                // 拿不到真实天气：如实说「本次没做校验」，不能凭不存在的事实判 HIGH（也进不了 issues）
                String reason = lookup == null ? "响应不可用" : lookup.message();
                warnings.add("Day " + entry.getKey() + " 未获取真实天气（" + reason + "），本次未做雨天/户外校验");
                found++;
                continue;
            }
            WeatherInfo weather = lookup.info();
            if (!likelyRainy(weather)) {
                continue;
            }
            List<String> outdoor = itemsByDay.getOrDefault(entry.getKey(), List.of()).stream()
                    .filter(item -> ItineraryItem.TYPE_ATTRACTION.equals(item.getItemType()))
                    .filter(ItineraryItem::isOutdoor)
                    .map(ItineraryItem::getTitle)
                    .toList();
            if (outdoor.isEmpty()) {
                continue;
            }
            // 逐条判断来源：真实预报才允许作为硬约束；模拟数据（仅测试开关）只能提示
            boolean simulated = weather.isSimulated();
            String titles = outdoor.stream().map(t -> "「" + t + "」")
                    .collect(java.util.stream.Collectors.joining("、"));
            String message = "Day " + entry.getKey() + " 预报" + weather.condition()
                    + "（降雨概率 " + weather.rainProbability() + "%" + (simulated ? "，模拟天气" : "") + "），"
                    + "仍安排了户外景点" + titles
                    + (simulated ? "；真实预报接入后会自动改为室内安排" : "，建议换成室内景点");
            // 模拟天气只作提示：拿假数据强制改行程，等于把估算伪装成事实（ADR-006）
            issues.add(new PlanIssue(PlanIssue.TYPE_WEATHER_OUTDOOR,
                    simulated ? PlanIssue.SEVERITY_MEDIUM : PlanIssue.SEVERITY_HIGH, message));
            warnings.add(message);
            found++;
        }
    }

    private void reviewTimeConflicts(AgentPlan plan, Trip trip, List<PlanIssue> issues, List<String> warnings) {
        int conflicts = 0;
        for (Map.Entry<Integer, List<ItineraryItem>> entry : inspector.effectiveItemsByDay(plan, trip).entrySet()) {
            List<ItineraryItem> items = entry.getValue().stream()
                    .filter(i -> i.getStartTime() != null)
                    .sorted(Comparator.comparing(ItineraryItem::getStartTime))
                    .toList();
            for (int i = 0; i < items.size() && conflicts < MAX_TIME_WARNINGS; i++) {
                for (int j = i + 1; j < items.size() && conflicts < MAX_TIME_WARNINGS; j++) {
                    ItineraryItem earlier = items.get(i);
                    ItineraryItem later = items.get(j);
                    if (later.getStartTime().isBefore(PlanInspector.endOf(earlier))) {
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
    }

    /** 是否「大概率下雨」：天况含雨且降雨概率达到阈值（模拟天气的「小雨」概率恒 ≥70%，可被识别） */
    static boolean likelyRainy(WeatherInfo weather) {
        return weather != null && weather.isRainy()
                && weather.rainProbability() >= RAIN_PROBABILITY_THRESHOLD;
    }

    /** 真实路径与估算必须自报家门（ADR-006）：估算时长不能看起来像实测 */
    private static String sourceHint(RouteInfo route) {
        return route != null && com.travel.agent.service.RouteService.SOURCE_ESTIMATED.equals(route.source())
                ? "（估算）" : "";
    }
}