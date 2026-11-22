package com.travel.agent.agent;

import com.travel.agent.entity.ItineraryItem;
import com.travel.agent.model.Attraction;
import com.travel.agent.model.CityCatalog;
import com.travel.agent.model.Restaurant;
import com.travel.agent.model.RouteInfo;
import com.travel.agent.service.ItineraryService;
import com.travel.agent.tool.ToolInvoker;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Planner 的「候选选择 + 行程条目草稿」构造器。
 *
 * <p>从 {@link PlannerAgent} 抽出的纯装配逻辑：所有取候选都经 {@link ToolInvoker} 调用只读工具
 * （这样聊天流里能看到 tool_call 过程），本类不做状态判断、不改库、不产出 {@code AgentPlan}。
 *
 * <p>新行程（{@code planNewTrip}）与行程修改（{@code planModification}）共用同一套口径，
 * 集中在这里避免两条路径的交通/标签/时间规则漂移。
 */
@Component
@RequiredArgsConstructor
@SuppressWarnings("unchecked")
public class PlannerDraftSupport {

    private final ToolInvoker tools;

    /**
     * 选景点：优先指定 venue（室外/室内），取不到时退化为不限 venue；亲子行程优先带「亲子」标签。
     *
     * @param used 已用景点名（工具层据此排除）
     */
    public Attraction pickAttraction(AgentContext ctx, CityCatalog city, String venue,
                                     List<String> used, boolean family) {
        List<Attraction> list = (List<Attraction>) tools.invoke(ctx, "search_attraction",
                buildArgs(new HashMap<>(), "city", city.name(), "venue", venue, "exclude", String.join(",", used)));
        if ((list == null || list.isEmpty()) && venue != null) {
            list = (List<Attraction>) tools.invoke(ctx, "search_attraction",
                    buildArgs(new HashMap<>(), "city", city.name(), "venue", null, "exclude", String.join(",", used)));
        }
        if (list == null || list.isEmpty()) {
            return null;
        }
        if (family) {
            return list.stream().filter(a -> a.tags().contains("亲子")).findFirst().orElse(list.get(0));
        }
        return list.get(0);
    }

    /** 选替换景点：优先指定 venue 与就近坐标，取不到时退化 venue 再查一次 */
    public Attraction pickReplacement(AgentContext ctx, CityCatalog city, String venue,
                                      Double nearLat, Double nearLng, List<String> exclude) {
        Map<String, Object> args = new HashMap<>();
        args.put("city", city.name());
        args.put("venue", venue);
        args.put("exclude", String.join(",", exclude));
        if (nearLat != null) args.put("nearLat", nearLat);
        if (nearLng != null) args.put("nearLng", nearLng);
        List<Attraction> list = (List<Attraction>) tools.invoke(ctx, "search_attraction", args);
        if ((list == null || list.isEmpty()) && venue != null) {
            args.put("venue", null);
            list = (List<Attraction>) tools.invoke(ctx, "search_attraction", args);
        }
        return (list == null || list.isEmpty()) ? null : list.get(0);
    }

    /** 选餐厅：就近优先，跳过已用过的名字 */
    public Restaurant pickRestaurant(AgentContext ctx, CityCatalog city, Double nearLat, Double nearLng,
                                     List<String> used) {
        Map<String, Object> args = new HashMap<>();
        args.put("city", city.name());
        if (nearLat != null) args.put("nearLat", nearLat);
        if (nearLng != null) args.put("nearLng", nearLng);
        List<Restaurant> list = (List<Restaurant>) tools.invoke(ctx, "search_restaurant", args);
        if (list == null || list.isEmpty()) {
            return null;
        }
        return list.stream().filter(r -> !used.contains(r.name())).findFirst().orElse(list.get(0));
    }

    /** 两点间的打车成本：步行时间超过 25 分钟才计入，否则按 0（口径与规则路径一致） */
    public BigDecimal legTaxiCost(AgentContext ctx, Double fromLat, Double fromLng, double toLat, double toLng) {
        if (fromLat == null || fromLng == null) {
            return BigDecimal.ZERO;
        }
        Object result = tools.invoke(ctx, "calculate_route", Map.of(
                "fromLat", fromLat, "fromLng", fromLng, "toLat", toLat, "toLng", toLng));
        if (result instanceof RouteInfo route && route.walkMinutes() > 25) {
            return BigDecimal.valueOf(route.taxiCost());
        }
        return BigDecimal.ZERO;
    }

    /** 构造一条行程条目草稿：日期由 (baseDate, day, 时间) 推出，标签用逗号拼成内部存储格式 */
    public ItineraryItem draft(LocalDate baseDate, int day, String type, String title,
                               String start, String end, Double lat, Double lng,
                               BigDecimal cost, List<String> tags, String description) {
        return ItineraryItem.builder()
                .dayIndex(day)
                .itemType(type)
                .title(title)
                .startTime(ItineraryService.atTime(baseDate, day, start, start))
                .endTime(ItineraryService.atTime(baseDate, day, end, end))
                .latitude(lat)
                .longitude(lng)
                .cost(cost)
                .tags(tags == null ? null : tags.stream()
                        .filter(t -> t != null && !t.isBlank())
                        .collect(Collectors.joining(",")))
                .description(description)
                .build();
    }

    /** 把 (key, value, key, value, ...) 依次塞进 map，供工具参数构造 */
    public Map<String, Object> buildArgs(Map<String, Object> map, Object... kv) {
        for (int i = 0; i < kv.length; i += 2) {
            map.put((String) kv[i], kv[i + 1]);
        }
        return map;
    }
}