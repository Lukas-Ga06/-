package com.travel.agent.agent;

import com.travel.agent.dto.AgentDtos;
import com.travel.agent.dto.ItineraryDtos;
import com.travel.agent.entity.ItineraryItem;
import com.travel.agent.entity.Trip;
import com.travel.agent.model.Attraction;
import com.travel.agent.model.CityCatalog;
import com.travel.agent.model.Hotel;
import com.travel.agent.model.Restaurant;
import com.travel.agent.model.WeatherInfo;
import com.travel.agent.service.BudgetService;
import com.travel.agent.service.ItineraryService;
import com.travel.agent.service.OperationHistoryService;
import com.travel.agent.service.TravelCatalogService;
import com.travel.agent.tool.ToolInvoker;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Planner Agent：Intent + 当前行程 + 用户偏好 + 预算 + 天气 + 交通 → 规划候选方案。
 * 只生成操作计划（不直接改库），Executor 确认后提交。
 */
@Component
@RequiredArgsConstructor
@Slf4j
@SuppressWarnings("unchecked")
public class PlannerAgent {

    private final ToolInvoker tools;
    private final TravelCatalogService catalog;
    private final ItineraryService itineraryService;
    private final BudgetService budgetService;

    public AgentPlan plan(AgentContext ctx, IntentResult intent, Trip trip) {
        String message = ctx.getMessage() == null ? "" : ctx.getMessage();
        CityCatalog city = catalog.matchCity(message);
        if (city == null && trip != null) {
            city = catalog.matchCity(trip.getDestination());
        }
        // 内置目录未命中：联网查询生成城市目录（新规划从消息提取城市名，行程修改用行程目的地）
        if (city == null && IntentResult.PLAN_TRIP.equals(intent.getIntent())) {
            city = lookupOnlineCity(ctx, onlineCityName(intent, message));
        } else if (city == null && trip != null) {
            city = lookupOnlineCity(ctx, trip.getDestination());
        }
        // 仍然找不到：明确告知不支持，而不是静默替换成默认城市
        if (city == null) {
            String supported = catalog.cities().stream()
                    .map(CityCatalog::name)
                    .collect(java.util.stream.Collectors.joining("、"));
            return emptyPlan(intent, null, trip,
                    "抱歉，暂时还没有开通「" + guessRequestedCity(message) + "」的规划服务（联网查询也未成功）。目前已开通："
                            + supported + "，选一个我开始规划吧！");
        }
        return switch (intent.getIntent()) {
            case IntentResult.PLAN_TRIP -> planNewTrip(ctx, intent, city, trip, message);
            case IntentResult.MODIFY_ITINERARY -> planModification(ctx, intent, city, trip, message);
            default -> emptyPlan(intent, city, trip, "该意图暂不需要重新规划。");
        };
    }

    // ==================== 新行程规划 ====================

    private AgentPlan planNewTrip(AgentContext ctx, IntentResult intent, CityCatalog city, Trip trip, String message) {
        int days = MessageParser.extractDays(message);
        if (days <= 0) {
            days = 3;
        }
        BigDecimal budget = MessageParser.extractBudget(message);
        if (budget == null) {
            budget = BigDecimal.valueOf(5000);
        }
        boolean preferSubway = MessageParser.mentions(message, "地铁");
        boolean family = MessageParser.mentions(message, "亲子", "孩子", "带娃", "儿童");

        List<String> preferences = new ArrayList<>();
        if (preferSubway) preferences.add("酒店近地铁");
        if (family) preferences.add("亲子友好");

        TripDraft draft = TripDraft.builder()
                .name(city.name() + days + "日游")
                .destination(city.name())
                .startDate(LocalDate.now().plusDays(1))
                .days(days)
                .budget(budget)
                .preferences(preferences)
                .build();

        List<PlanOperation> ops = new ArrayList<>();
        // 重新规划已有行程：先清空原条目
        if (trip != null) {
            for (ItineraryItem item : itineraryService.listEntities(trip.getId())) {
                ops.add(PlanOperation.builder().type(PlanOperation.REMOVE)
                        .targetItemId(item.getId()).original(item)
                        .detail("清除原安排：" + item.getTitle()).build());
            }
        }

        // 酒店预算上限 = 总预算 * 40% / 天数
        BigDecimal perNightCap = budget.multiply(BigDecimal.valueOf(0.4))
                .divide(BigDecimal.valueOf(Math.max(days, 1)), 2, RoundingMode.HALF_UP);
        Map<String, Object> hotelArgs = new HashMap<>();
        hotelArgs.put("city", city.name());
        hotelArgs.put("preferSubway", preferSubway);
        hotelArgs.put("maxPrice", perNightCap.doubleValue());
        List<Hotel> hotels = (List<Hotel>) tools.invoke(ctx, "search_hotel", hotelArgs);
        Hotel hotel = (hotels == null || hotels.isEmpty()) ? catalog.cheapestHotel(city) : hotels.get(0);

        LocalDate baseDate = draft.getStartDate();
        List<ItineraryItem> drafts = buildDailyDrafts(ctx, city, hotel, days, family, baseDate);
        BigDecimal total = budgetService.totalOf(drafts);
        // 超预算：降级为最便宜酒店后重算一次
        Hotel cheapest = catalog.cheapestHotel(city);
        if (total.compareTo(budget) > 0 && hotel != null && cheapest != null
                && hotel.pricePerNight() > cheapest.pricePerNight()) {
            hotel = cheapest;
            drafts = buildDailyDrafts(ctx, city, hotel, days, family, baseDate);
            total = budgetService.totalOf(drafts);
        }

        for (ItineraryItem item : drafts) {
            ops.add(PlanOperation.builder().type(PlanOperation.ADD).draft(item).detail("新增：" + item.getTitle()).build());
        }

        Map<String, Object> extras = new HashMap<>();
        extras.put("hotelName", hotel == null ? "暂无" : hotel.name());
        extras.put("total", total);
        extras.put("days", days);

        return AgentPlan.builder()
                .planId(UUID.randomUUID().toString())
                .intent(intent.getIntent())
                .target(intent.getTarget())
                .tripId(trip == null ? null : trip.getId())
                .city(city)
                .tripDraft(draft)
                .operations(ops)
                .focus(AgentDtos.AgentFocus.builder().type("itinerary").day(1).build())
                .reasoning("基于 " + city.name() + days + " 天行程与 ¥" + budget.stripTrailingZeros().toPlainString()
                        + " 预算：酒店选择「" + (hotel == null ? "-" : hotel.name()) + "」"
                        + (preferSubway ? "（近地铁）" : "") + "；景点按「上午户外 + 下午室内」搭配，餐饮就近安排。")
                .extras(extras)
                .build();
    }

    private List<ItineraryItem> buildDailyDrafts(AgentContext ctx, CityCatalog city, Hotel hotel,
                                                 int days, boolean family, LocalDate baseDate) {
        List<ItineraryItem> drafts = new ArrayList<>();
        List<String> usedAttractions = new ArrayList<>();
        List<String> usedRestaurants = new ArrayList<>();
        BigDecimal hotelCost = hotel == null ? BigDecimal.ZERO
                : BigDecimal.valueOf(hotel.pricePerNight()).multiply(BigDecimal.valueOf(Math.max(days - 1, 1)));

        for (int d = 1; d <= days; d++) {
            if (d == 1 && hotel != null) {
                List<String> hotelTags = new ArrayList<>(List.of("酒店"));
                if (hotel.nearSubway()) hotelTags.add("近地铁");
                drafts.add(draft(baseDate, d, ItineraryItem.TYPE_HOTEL, "入住 · " + hotel.name(),
                        "09:00", "09:30", hotel.lat(), hotel.lng(), hotelCost, hotelTags, hotel.description()));
            }

            Double prevLat = (d == 1 && hotel != null) ? hotel.lat() : null;
            Double prevLng = (d == 1 && hotel != null) ? hotel.lng() : null;
            BigDecimal transport = BigDecimal.ZERO;

            Attraction morning = pickAttraction(ctx, city, "outdoor", usedAttractions, family);
            if (morning != null) {
                transport = transport.add(legTaxiCost(ctx, prevLat, prevLng, morning.lat(), morning.lng()));
                String start = d == 1 ? "10:00" : "09:00";
                String end = d == 1 ? "11:45" : "11:30";
                drafts.add(draft(baseDate, d, ItineraryItem.TYPE_ATTRACTION, morning.name(), start, end,
                        morning.lat(), morning.lng(), BigDecimal.valueOf(morning.ticket()),
                        morning.tagsWithVenue(), morning.description()));
                usedAttractions.add(morning.name());
                prevLat = morning.lat();
                prevLng = morning.lng();

                Restaurant lunch = pickRestaurant(ctx, city, morning.lat(), morning.lng(), usedRestaurants);
                if (lunch != null) {
                    transport = transport.add(legTaxiCost(ctx, prevLat, prevLng, lunch.lat(), lunch.lng()));
                    drafts.add(draft(baseDate, d, ItineraryItem.TYPE_RESTAURANT, "午餐 · " + lunch.name(),
                            "12:00", "13:30", lunch.lat(), lunch.lng(), BigDecimal.valueOf(lunch.avgCost()),
                            List.of(lunch.cuisine()), lunch.description()));
                    usedRestaurants.add(lunch.name());
                    prevLat = lunch.lat();
                    prevLng = lunch.lng();
                }
            }

            Attraction afternoon = pickAttraction(ctx, city, "indoor", usedAttractions, family);
            if (afternoon != null) {
                transport = transport.add(legTaxiCost(ctx, prevLat, prevLng, afternoon.lat(), afternoon.lng()));
                drafts.add(draft(baseDate, d, ItineraryItem.TYPE_ATTRACTION, afternoon.name(),
                        "14:00", "16:30", afternoon.lat(), afternoon.lng(),
                        BigDecimal.valueOf(afternoon.ticket()), afternoon.tagsWithVenue(), afternoon.description()));
                usedAttractions.add(afternoon.name());
                prevLat = afternoon.lat();
                prevLng = afternoon.lng();

                Restaurant dinner = pickRestaurant(ctx, city, afternoon.lat(), afternoon.lng(), usedRestaurants);
                if (dinner != null) {
                    transport = transport.add(legTaxiCost(ctx, prevLat, prevLng, dinner.lat(), dinner.lng()));
                    drafts.add(draft(baseDate, d, ItineraryItem.TYPE_RESTAURANT, "晚餐 · " + dinner.name(),
                            "18:00", "19:30", dinner.lat(), dinner.lng(), BigDecimal.valueOf(dinner.avgCost()),
                            List.of(dinner.cuisine()), dinner.description()));
                    usedRestaurants.add(dinner.name());
                }
            }

            if (transport.compareTo(BigDecimal.ZERO) > 0) {
                drafts.add(draft(baseDate, d, ItineraryItem.TYPE_TRANSPORT, "当日交通（打车）",
                        "19:45", "20:00", null, null, transport, List.of("交通"), "Agent 根据路线距离估算的市内交通费用"));
            }
        }
        return drafts;
    }

    // ==================== 行程修改（天气替换 / 选中替换） ====================

    private AgentPlan planModification(AgentContext ctx, IntentResult intent, CityCatalog city, Trip trip, String message) {
        if (trip == null) {
            return emptyPlan(intent, city, null,
                    "请先选择一个行程再进行调整；也可以直接对我说「帮我规划上海三日游」来创建新行程。");
        }
        List<ItineraryDtos.ItineraryItemDTO> fetched =
                (List<ItineraryDtos.ItineraryItemDTO>) tools.invoke(ctx, "get_itinerary", Map.of("tripId", trip.getId()));
        final List<ItineraryDtos.ItineraryItemDTO> items = fetched == null ? List.of() : fetched;
        LocalDate targetDate = MessageParser.resolveDate(message, trip, ctx.getCurrentDay());
        int scopeDay = MessageParser.dayIndexOf(trip, targetDate);
        boolean mentionRain = MessageParser.mentions(message, "雨");
        WeatherInfo weather = (WeatherInfo) tools.invoke(ctx, "query_weather",
                buildArgs(new HashMap<>(), "city", city.name(), "date", targetDate.toString(), "mentionRain", mentionRain));

        List<ItineraryDtos.ItineraryItemDTO> targets = new ArrayList<>();
        Integer dayFilter = MessageParser.mentions(message, "明天", "后天", "今天") ? scopeDay
                : (ctx.getCurrentDay() != null ? ctx.getCurrentDay() : null);

        if (IntentResult.TARGET_SELECTED.equals(intent.getTarget()) && ctx.getSelectedElement() != null) {
            Long selId = ctx.getSelectedElement().getId();
            String selTitle = ctx.getSelectedElement().getTitle();
            items.stream().filter(i -> selId != null && selId.equals(i.getId())).findFirst()
                    .or(() -> items.stream().filter(i -> selTitle != null && selTitle.equals(i.getTitle())).findFirst())
                    .ifPresent(targets::add);
        } else {
            items.stream()
                    .filter(i -> ItineraryItem.TYPE_ATTRACTION.equals(i.getItemType()))
                    .filter(i -> i.getTags() != null && i.getTags().contains("室外"))
                    .filter(i -> dayFilter == null || dayFilter.equals(i.getDayIndex()))
                    .forEach(targets::add);
        }

        if (targets.isEmpty()) {
            return emptyPlan(intent, city, trip,
                    "在 Day " + scopeDay + " 没有找到需要替换的户外景点。你可以在时间轴上点选一个安排，然后说「这个换成类似的」。");
        }

        List<PlanOperation> ops = new ArrayList<>();
        List<String> exclude = new ArrayList<>(items.stream().map(ItineraryDtos.ItineraryItemDTO::getTitle).toList());
        List<String> replacements = new ArrayList<>();
        ItineraryDtos.ItineraryItemDTO lastTarget = null;
        int firstDay = targets.get(0).getDayIndex();

        for (ItineraryDtos.ItineraryItemDTO target : targets) {
            boolean rainHere = weather != null && weather.isRainy();
            String venue = rainHere ? "indoor"
                    : (target.getTags() != null && target.getTags().contains("室内")) ? "indoor" : "outdoor";
            Attraction repl = pickReplacement(ctx, city, venue, target.getLatitude(), target.getLongitude(), exclude);
            if (repl == null) {
                replacements.add("「" + target.getTitle() + "」暂无合适替代");
                continue;
            }
            ops.add(PlanOperation.builder().type(PlanOperation.REMOVE)
                    .targetItemId(target.getId())
                    .original(OperationHistoryService.toEntity(trip.getId(), target))
                    .detail("移除「" + target.getTitle() + "」").build());
            ItineraryItem draft = draft(trip.getStartDate(), target.getDayIndex(), ItineraryItem.TYPE_ATTRACTION,
                    repl.name(), "14:00", "16:30", repl.lat(), repl.lng(),
                    BigDecimal.valueOf(repl.ticket()), repl.tagsWithVenue(), repl.description());
            if (target.getStartTime() != null) {
                draft.setStartTime(LocalDateTime.parse(target.getStartTime()));
                draft.setEndTime(LocalDateTime.parse(target.getEndTime()));
            }
            ops.add(PlanOperation.builder().type(PlanOperation.ADD).draft(draft)
                    .detail("新增「" + repl.name() + "」").build());
            exclude.add(repl.name());
            replacements.add("「" + target.getTitle() + "」→「" + repl.name() + "」");
            lastTarget = target;
        }

        // 用户同时提出用餐需求
        if (MessageParser.mentions(message, "吃", "餐", "饭", "饿") && lastTarget != null) {
            int diningDay = lastTarget.getDayIndex();
            boolean hasRestaurant = items.stream()
                    .anyMatch(i -> ItineraryItem.TYPE_RESTAURANT.equals(i.getItemType()) && diningDay == i.getDayIndex());
            if (!hasRestaurant) {
                Restaurant rest = pickRestaurant(ctx, city, lastTarget.getLatitude(), lastTarget.getLongitude(), new ArrayList<>());
                if (rest != null) {
                    ops.add(PlanOperation.builder().type(PlanOperation.ADD)
                            .draft(draft(trip.getStartDate(), diningDay, ItineraryItem.TYPE_RESTAURANT,
                                    "晚餐 · " + rest.name(), "18:00", "19:30", rest.lat(), rest.lng(),
                                    BigDecimal.valueOf(rest.avgCost()), List.of(rest.cuisine()), rest.description()))
                            .detail("新增「" + rest.name() + "」").build());
                    replacements.add("新增餐饮「" + rest.name() + "」");
                }
            }
        }

        if (ops.isEmpty()) {
            return emptyPlan(intent, city, trip, "没有找到合适的替换方案，试试更换其他日期或景点。");
        }

        Map<String, Object> extras = new HashMap<>();
        extras.put("weather", weather);
        extras.put("replacements", replacements);
        extras.put("day", lastTarget != null ? lastTarget.getDayIndex() : firstDay);

        return AgentPlan.builder()
                .planId(UUID.randomUUID().toString())
                .intent(intent.getIntent())
                .target(intent.getTarget())
                .tripId(trip.getId())
                .city(city)
                .operations(ops)
                .focus(AgentDtos.AgentFocus.builder().type("itinerary")
                        .day(lastTarget != null ? lastTarget.getDayIndex() : firstDay)
                        .build())
                .reasoning((weather == null ? "" : "天气：" + weather.condition() + "（降雨概率 " + weather.rainProbability() + "%）；")
                        + "替换方案：" + String.join("，", replacements))
                .extras(extras)
                .build();
    }

    // ==================== 公共辅助 ====================

    private AgentPlan emptyPlan(IntentResult intent, CityCatalog city, Trip trip, String reasoning) {
        return AgentPlan.builder()
                .planId(UUID.randomUUID().toString())
                .intent(intent.getIntent())
                .target(intent.getTarget())
                .tripId(trip == null ? null : trip.getId())
                .city(city)
                .operations(new ArrayList<>())
                .reasoning(reasoning)
                .build();
    }

    /** 从 LLM 约束或消息句式中提取目录外城市名 */
    private String onlineCityName(IntentResult intent, String message) {
        Object dest = intent.getConstraints() == null ? null : intent.getConstraints().get("destination");
        if (dest instanceof String s && !s.isBlank()) {
            return s.trim();
        }
        return MessageParser.guessCityName(message);
    }

    /** 联网查询城市目录（经 ToolInvoker 以便聊天流展示 tool_call 过程） */
    private CityCatalog lookupOnlineCity(AgentContext ctx, String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        Object result = tools.invoke(ctx, "search_city_online", Map.of("city", name));
        return result instanceof CityCatalog c ? c : null;
    }

    /** 粗略提取用户提到的目的地名（仅用于"暂不支持"提示文案） */
    private String guessRequestedCity(String message) {
        var m = java.util.regex.Pattern
                .compile("([\\u4e00-\\u9fa5]{2,4}?)(?:[0-9一二两三四五六七八]+日游|周末游|亲子游|游)")
                .matcher(message);
        if (m.find()) {
            return m.group(1);
        }
        var m2 = java.util.regex.Pattern.compile("去([\\u4e00-\\u9fa5]{2,4})").matcher(message);
        if (m2.find()) {
            return m2.group(1);
        }
        return "该目的地";
    }

    private Attraction pickAttraction(AgentContext ctx, CityCatalog city, String venue,
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

    private Attraction pickReplacement(AgentContext ctx, CityCatalog city, String venue,
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

    private Restaurant pickRestaurant(AgentContext ctx, CityCatalog city, Double nearLat, Double nearLng,
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

    private BigDecimal legTaxiCost(AgentContext ctx, Double fromLat, Double fromLng, double toLat, double toLng) {
        if (fromLat == null || fromLng == null) {
            return BigDecimal.ZERO;
        }
        Object result = tools.invoke(ctx, "calculate_route", Map.of(
                "fromLat", fromLat, "fromLng", fromLng, "toLat", toLat, "toLng", toLng));
        if (result instanceof com.travel.agent.model.RouteInfo route && route.walkMinutes() > 25) {
            return BigDecimal.valueOf(route.taxiCost());
        }
        return BigDecimal.ZERO;
    }

    private ItineraryItem draft(LocalDate baseDate, int day, String type, String title,
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

    private Map<String, Object> buildArgs(Map<String, Object> map, Object... kv) {
        for (int i = 0; i < kv.length; i += 2) {
            map.put((String) kv[i], kv[i + 1]);
        }
        return map;
    }

    static String hhmm(LocalDateTime time) {
        return time == null ? null : time.format(DateTimeFormatter.ofPattern("HH:mm"));
    }
}
