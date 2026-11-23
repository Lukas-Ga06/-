package com.travel.agent.agent;

import com.travel.agent.dto.AgentDtos;
import com.travel.agent.dto.ItineraryDtos;
import com.travel.agent.entity.ItineraryItem;
import com.travel.agent.entity.Trip;
import com.travel.agent.model.Attraction;
import com.travel.agent.model.CityCatalog;
import com.travel.agent.llm.LlmProperties;
import com.travel.agent.model.Hotel;
import com.travel.agent.model.Restaurant;
import com.travel.agent.model.WeatherInfo;
import com.travel.agent.rag.RagCitation;
import com.travel.agent.rag.RagService;
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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Planner Agent：Intent + 当前行程 + 用户偏好 + 预算 + 天气 + 交通 → 规划候选方案。
 * 只生成操作计划（不直接改库），Executor 确认后提交。
 *
 * <p>边界：规则路径<b>不读会话历史</b>（{@code AgentContext.getHistory()}），规划产物只由
 * 「当前这条消息识别出的意图 + 行程上下文」决定；只有 LLM 结构化路径会把历史以受限摘要块
 * 交给模型（见 {@link StructuredPlanner#historyBlock}），用于理解指代与偏好，不改变任何校验。
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
    /** 用于把"没规划出来"的原因说准确：是城市没有，还是模型没开 */
    private final LlmProperties llmProperties;
    /** LLM 结构化规划：模型从候选清单选择与编排，规则引擎逐条校验，失败回落规则路径 */
    private final StructuredPlanner structuredPlanner;
    /** 候选选择与条目草稿装配（新行程 / 行程修改两条路径共用同一套口径） */
    private final PlannerDraftSupport draftsSupport;
    /** V2.2 自主工具调用循环（FR-03）：LLM 可用时先让研究 Agent 自主收集事实 */
    private final AgentToolLoop toolLoop;
    /** 研究任务记录（receiver=research-agent，任务/工具调用进 run 追踪） */
    private final AgentTaskRecorder taskRecorder;
    /** P1：静态旅行知识进入规划证据链（带 documentId/version/chunkId/score/source） */
    private final RagService ragService;

    public AgentPlan plan(AgentContext ctx, IntentResult intent, Trip trip) {
        String message = ctx.getMessage() == null ? "" : ctx.getMessage();
        // 目的地以需求槽位为准（FR-01）：追问补齐后当前消息往往只有「两个人」，正文里没有城市名
        String slotCity = intent.getSlots() == null ? null : intent.getSlots().destination();
        CityCatalog city = slotCity == null || slotCity.isBlank() ? null : catalog.matchCity(slotCity);
        if (city == null) {
            city = catalog.matchCity(message);
        }
        if (city == null && trip != null) {
            city = catalog.matchCity(trip.getDestination());
        }
        // 内置目录未命中：联网查询生成城市目录（新规划从消息提取城市名，行程修改用行程目的地）
        // P1：联网城市查询本质是一次 LLM 生成（可能几十秒），预算不足时直接跳过并用本地目录兜底，
        // 不能让「城市不在目录里」把端到端预算重新拖爆。
        AgentBudget budgetGate = ctx.getBudget();
        if (city == null && IntentResult.PLAN_TRIP.equals(intent.getIntent())) {
            city = lookupOnlineCity(ctx, budgetGate, slotCity == null || slotCity.isBlank()
                    ? onlineCityName(intent, message) : slotCity);
        } else if (city == null && trip != null) {
            city = lookupOnlineCity(ctx, budgetGate, trip.getDestination());
        }
        // 仍然找不到：明确告知不支持，而不是静默替换成默认城市
        if (city == null) {
            String supported = catalog.cities().stream()
                    .map(CityCatalog::name)
                    .collect(java.util.stream.Collectors.joining("、"));
            return emptyPlan(intent, null, trip, unsupportedCityMessage(
                    guessRequestedCity(message), supported, llmProperties.usable()));
        }
        return switch (intent.getIntent()) {
            case IntentResult.PLAN_TRIP -> planNewTrip(ctx, intent, city, trip, message);
            case IntentResult.MODIFY_ITINERARY -> planModification(ctx, intent, city, trip, message);
            default -> emptyPlan(intent, city, trip, "该意图暂不需要重新规划。");
        };
    }

    // ==================== 新行程规划 ====================

    private AgentPlan planNewTrip(AgentContext ctx, IntentResult intent, CityCatalog city, Trip trip, String message) {
        // 需求槽位（FR-01）：引擎已完成合并与兜底；直接调用规划器（单测/旧调用方）时自行解析
        RequirementSlots slots = intent.getSlots() == null
                ? RequirementSlots.fromMessage(message).fillMissingFrom(RequirementSlots.fromTrip(trip))
                : intent.getSlots();
        int days = slots.days() != null && slots.days() > 0
                ? slots.days()
                // 重新规划已有行程时沿用原行程天数，避免"3 天行程被重排成默认 3 天/其它天数"
                : (trip != null && trip.getStartDate() != null ? trip.totalDays() : 3);
        BigDecimal budget = slots.budget() != null
                ? slots.budget()
                // 同理：没重新说预算就沿用原行程预算，而不是套用默认 5000
                : (trip != null && trip.getBudget() != null ? trip.getBudget() : BigDecimal.valueOf(5000));
        int partySize = BudgetService.effectiveParty(slots.partySize() != null
                ? slots.partySize() : (trip != null ? trip.getPartySize() : null));
        boolean preferSubway = MessageParser.mentions(message, "地铁");
        boolean family = MessageParser.mentions(message, "亲子", "孩子", "带娃", "儿童");

        List<String> preferences = new ArrayList<>();
        if (preferSubway) preferences.add("酒店近地铁");
        if (family) preferences.add("亲子友好");

        // 出发日期：用户明确说的日期优先（国庆/10月1日/下周六…）；没说则沿用原行程日期，再没有就明天
        LocalDate today = LocalDate.now();
        MessageParser.DepartureDate departure = MessageParser.extractDepartureDate(message, today);
        LocalDate startDate = slots.startDate() != null ? slots.startDate()
                : (trip != null && trip.getStartDate() != null
                        ? trip.getStartDate()
                        : (departure.explicit() && departure.date() != null ? departure.date() : today.plusDays(1)));
        if (startDate.isBefore(today)) {
            return emptyPlan(intent, city, trip,
                    "出发日期 " + startDate + " 已经过去了，请确认年份或换一个日期。");
        }

        TripDraft draft = TripDraft.builder()
                .name(city.name() + days + "日游")
                .destination(city.name())
                .startDate(startDate)
                .days(days)
                .partySize(partySize)
                .budget(budget)
                .preferences(preferences)
                .build();

        List<PlanOperation> ops = new ArrayList<>();
        // LLM 结构化规划先行：模型从候选清单里选择与编排，规则引擎逐条校验，失败回落下方规则路径。
        // 之前先跑研究循环（FR-03）：让 research-agent 在只读工具上自主「决策→调用→观察→继续」，
        // 收集到的天气/路线事实作为参考证据进入结构化 Prompt；无 LLM 或循环未完成时不带任何研究块。
        // P1：两个 LLM 阶段都先看剩余预算——预算不够就跳过并留痕，规划本身仍由规则路径完成（绝不空返回）。
        AgentBudget timeBudget = ctx.getBudget();
        // P1：RAG 进入规划证据链。无论 LLM 是否可用都先检索静态旅行知识，
        // 命中的引用（documentId/version/chunkId/score/source）随方案进入 extras 与 SSE。
        List<RagCitation> citations = knowledgeCitations(ctx, city.name(), message);
        List<String> researchNotes = List.of();
        if (llmProperties.usable()) {
            if (timeBudget.allows(AgentBudget.MIN_RESEARCH_STEP_MS)) {
                researchNotes = researchFacts(ctx, city, draft, budget);
            } else {
                ctx.recordBudgetDegradation(AgentBudget.STAGE_RESEARCH,
                        "预算不足，跳过研究取证（未调用只读工具）");
            }
        }
        if (llmProperties.usable()) {
            if (timeBudget.allows(AgentBudget.MIN_STRUCTURED_PLAN_MS)) {
                AgentPlan structured = tryStructuredNewTrip(ctx, intent, city, trip, draft, days, budget, message,
                        researchNotes, timeBudget);
                if (structured != null) {
                    attachKnowledge(structured, citations);
                    return structured;
                }
            } else {
                ctx.recordBudgetDegradation(AgentBudget.STAGE_PLAN,
                        "预算不足，跳过 LLM 结构化规划，改用规则规划");
            }
        }
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
        List<ItineraryItem> drafts = buildDailyDrafts(ctx, city, hotel, days, family, baseDate, partySize);
        BigDecimal total = budgetService.totalOf(drafts);
        // 超预算：降级为最便宜酒店后重算一次
        Hotel cheapest = catalog.cheapestHotel(city);
        if (total.compareTo(budget) > 0 && hotel != null && cheapest != null
                && hotel.pricePerNight() > cheapest.pricePerNight()) {
            hotel = cheapest;
            drafts = buildDailyDrafts(ctx, city, hotel, days, family, baseDate, partySize);
            total = budgetService.totalOf(drafts);
        }
        // 仍超预算（V2.2 FR-06 动态调整）：确定性收敛——逐次移除最贵景点直到进入预算
        Map<String, Object> extras = new HashMap<>();
        List<String> trimmed = trimToBudget(drafts, budget);
        if (!trimmed.isEmpty()) {
            total = budgetService.totalOf(drafts);
            extras.put("budgetAdjusted", "为守住预算已自动精简：" + String.join("、", trimmed));
        }

        for (ItineraryItem item : drafts) {
            ops.add(PlanOperation.builder().type(PlanOperation.ADD).draft(item).detail("新增：" + item.getTitle()).build());
        }

        extras.put("hotelName", hotel == null ? "暂无" : hotel.name());
        extras.put("total", total);
        extras.put("days", days);
        extras.put("citations", citations);

        return AgentPlan.builder()
                .planId(UUID.randomUUID().toString())
                .intent(intent.getIntent())
                .target(intent.getTarget())
                .tripId(trip == null ? null : trip.getId())
                .city(city)
                .tripDraft(draft)
                .operations(ops)
                .focus(AgentDtos.AgentFocus.builder().type("itinerary").day(1).build())
                .reasoning(startDate + " 出发，基于 " + city.name() + days + " 天行程与 ¥" + budget.stripTrailingZeros().toPlainString()
                        + " 预算：酒店选择「" + (hotel == null ? "-" : hotel.name()) + "」"
                        + (preferSubway ? "（近地铁）" : "") + "；景点按「上午户外 + 下午室内」搭配，餐饮就近安排。"
                        + (trimmed.isEmpty() ? "" : " " + extras.get("budgetAdjusted") + "。"))
                .extras(extras)
                .build();
    }

    /**
     * 超预算的确定性收敛（V2.2 FR-06 动态调整，规则路径）：
     * 逐次移除「当前最贵的景点」直到合计进入预算；酒店降级之后仍超才走到这里。
     *
     * <p>只动景点不动餐饮/酒店/交通：门票是弹性项且移除后行程仍成立；
     * 每天至少保留一个景点（每轮只在「该天景点数 > 1」的天里选候选），
     * 极端预算下宁可留下少量超支警告也不产出空行程。
     * 移除项以标题列表返回（进 reasoning 与 extras.budgetAdjusted，重规划原因可追溯）。
     */
    private List<String> trimToBudget(List<ItineraryItem> drafts, BigDecimal budget) {
        List<String> trimmed = new ArrayList<>();
        BigDecimal total = budgetService.totalOf(drafts);
        while (total.compareTo(budget) > 0) {
            // 每天景点计数，用于保证「每天至少 1 个景点」
            Map<Integer, Long> attractionCountByDay = new HashMap<>();
            for (ItineraryItem item : drafts) {
                if (ItineraryItem.TYPE_ATTRACTION.equals(item.getItemType())) {
                    attractionCountByDay.merge(item.getDayIndex(), 1L, Long::sum);
                }
            }
            ItineraryItem candidate = null;
            for (ItineraryItem item : drafts) {
                if (!ItineraryItem.TYPE_ATTRACTION.equals(item.getItemType())) {
                    continue;
                }
                int day = item.getDayIndex() == null ? 1 : item.getDayIndex();
                if (attractionCountByDay.getOrDefault(day, 0L) <= 1) {
                    continue; // 该天只剩这一个景点，保底不动
                }
                if (candidate == null || costOf(item).compareTo(costOf(candidate)) > 0) {
                    candidate = item;
                }
            }
            if (candidate == null) {
                break; // 没有可移除的弹性项：保留超支，交给 Reviewer 警告
            }
            drafts.remove(candidate);
            trimmed.add("「" + candidate.getTitle() + "」");
            total = budgetService.totalOf(drafts);
        }
        return trimmed;
    }

    private static BigDecimal costOf(ItineraryItem item) {
        return item.getCost() == null ? BigDecimal.ZERO : item.getCost();
    }

    private List<ItineraryItem> buildDailyDrafts(AgentContext ctx, CityCatalog city, Hotel hotel,
                                                 int days, boolean family, LocalDate baseDate, int partySize) {
        List<ItineraryItem> drafts = new ArrayList<>();
        List<String> usedAttractions = new ArrayList<>();
        List<String> usedRestaurants = new ArrayList<>();
        // 房价按双人间计（两人一间、向上取整），餐饮与门票按人头计；交通是整车，不随人数放大
        int rooms = BudgetService.roomsFor(partySize);
        BigDecimal hotelCost = hotel == null ? BigDecimal.ZERO
                : BigDecimal.valueOf(hotel.pricePerNight())
                        .multiply(BigDecimal.valueOf(rooms))
                        .multiply(BigDecimal.valueOf(Math.max(days - 1, 1)));

        for (int d = 1; d <= days; d++) {
            if (d == 1 && hotel != null) {
                List<String> hotelTags = new ArrayList<>(List.of("酒店"));
                if (hotel.nearSubway()) hotelTags.add("近地铁");
                if (rooms > 1) hotelTags.add(rooms + "间房");
                drafts.add(draftsSupport.draft(baseDate, d, ItineraryItem.TYPE_HOTEL, "入住 · " + hotel.name(),
                        "09:00", "09:30", hotel.lat(), hotel.lng(), hotelCost, hotelTags, hotel.description()));
            }

            Double prevLat = (d == 1 && hotel != null) ? hotel.lat() : null;
            Double prevLng = (d == 1 && hotel != null) ? hotel.lng() : null;
            BigDecimal transport = BigDecimal.ZERO;

            Attraction morning = draftsSupport.pickAttraction(ctx, city, "outdoor", usedAttractions, family);
            if (morning != null) {
                transport = transport.add(draftsSupport.legTaxiCost(ctx, prevLat, prevLng, morning.lat(), morning.lng()));
                String start = d == 1 ? "10:00" : "09:00";
                String end = d == 1 ? "11:45" : "11:30";
                drafts.add(draftsSupport.draft(baseDate, d, ItineraryItem.TYPE_ATTRACTION, morning.name(), start, end,
                        morning.lat(), morning.lng(),
                        BudgetService.perPerson(BigDecimal.valueOf(morning.ticket()), partySize),
                        morning.tagsWithVenue(), morning.description()));
                usedAttractions.add(morning.name());
                prevLat = morning.lat();
                prevLng = morning.lng();

                Restaurant lunch = draftsSupport.pickRestaurant(ctx, city, morning.lat(), morning.lng(), usedRestaurants);
                if (lunch != null) {
                    transport = transport.add(draftsSupport.legTaxiCost(ctx, prevLat, prevLng, lunch.lat(), lunch.lng()));
                    drafts.add(draftsSupport.draft(baseDate, d, ItineraryItem.TYPE_RESTAURANT, "午餐 · " + lunch.name(),
                            "12:00", "13:30", lunch.lat(), lunch.lng(),
                            BudgetService.perPerson(BigDecimal.valueOf(lunch.avgCost()), partySize),
                            List.of(lunch.cuisine()), lunch.description()));
                    usedRestaurants.add(lunch.name());
                    prevLat = lunch.lat();
                    prevLng = lunch.lng();
                }
            }

            Attraction afternoon = draftsSupport.pickAttraction(ctx, city, "indoor", usedAttractions, family);
            if (afternoon != null) {
                transport = transport.add(draftsSupport.legTaxiCost(ctx, prevLat, prevLng, afternoon.lat(), afternoon.lng()));
                drafts.add(draftsSupport.draft(baseDate, d, ItineraryItem.TYPE_ATTRACTION, afternoon.name(),
                        "14:00", "16:30", afternoon.lat(), afternoon.lng(),
                        BudgetService.perPerson(BigDecimal.valueOf(afternoon.ticket()), partySize),
                        afternoon.tagsWithVenue(), afternoon.description()));
                usedAttractions.add(afternoon.name());
                prevLat = afternoon.lat();
                prevLng = afternoon.lng();

                Restaurant dinner = draftsSupport.pickRestaurant(ctx, city, afternoon.lat(), afternoon.lng(), usedRestaurants);
                if (dinner != null) {
                    transport = transport.add(draftsSupport.legTaxiCost(ctx, prevLat, prevLng, dinner.lat(), dinner.lng()));
                    drafts.add(draftsSupport.draft(baseDate, d, ItineraryItem.TYPE_RESTAURANT, "晚餐 · " + dinner.name(),
                            "18:00", "19:30", dinner.lat(), dinner.lng(),
                            BudgetService.perPerson(BigDecimal.valueOf(dinner.avgCost()), partySize),
                            List.of(dinner.cuisine()), dinner.description()));
                    usedRestaurants.add(dinner.name());
                }
            }

            if (transport.compareTo(BigDecimal.ZERO) > 0) {
                drafts.add(draftsSupport.draft(baseDate, d, ItineraryItem.TYPE_TRANSPORT, "当日交通（打车）",
                        "19:45", "20:00", null, null, transport, List.of("交通"), "Agent 根据路线距离估算的市内交通费用"));
            }
        }
        return drafts;
    }

    // ==================== 行程修改（天气替换 / 选中替换） ====================

    /**
     * P1 行中锁定：只有 PENDING 的条目允许被自动重规划改动。
     * 状态为空的历史数据按 PENDING 处理（兼容升级前数据）。
     */
    static boolean isAdjustable(ItineraryDtos.ItineraryItemDTO item) {
        return item.getStatus() == null || ItineraryItem.STATUS_PENDING.equals(item.getStatus());
    }

    private AgentPlan planModification(AgentContext ctx, IntentResult intent, CityCatalog city, Trip trip, String message) {
        if (trip == null) {
            return emptyPlan(intent, city, null,
                    "请先选择一个行程再进行调整；也可以直接对我说「帮我规划上海三日游」来创建新行程。");
        }
        List<ItineraryDtos.ItineraryItemDTO> fetched =
                (List<ItineraryDtos.ItineraryItemDTO>) tools.invoke(ctx, "get_itinerary", Map.of("tripId", trip.getId()));
        final List<ItineraryDtos.ItineraryItemDTO> items = fetched == null ? List.of() : fetched;
        // P1：修改方案同样带知识依据，便于用户判断替换推荐是否有出处
        List<RagCitation> citations = knowledgeCitations(ctx, city.name(), message);
        // 日期 → 行程第几天：不在行程区间内时按「当前天 + 偏移」顺推，并把这件事告诉用户
        MessageParser.TripDay tripDay = MessageParser.resolveTripDay(trip, ctx.getCurrentDay(), message);
        int scopeDay = tripDay.dayIndex();
        LocalDate targetDate = tripDay.date();
        String dateNote = tripDay.outsideTrip()
                ? "（你提到的日期不在行程 " + trip.getStartDate() + " ~ " + trip.getEndDate()
                        + " 内，已按 Day " + scopeDay + " 处理）"
                : "";
        boolean mentionRain = MessageParser.mentions(message, "雨");
        WeatherInfo weather = (WeatherInfo) tools.invoke(ctx, "query_weather",
                draftsSupport.buildArgs(new HashMap<>(), "city", city.name(), "date", targetDate.toString(), "mentionRain", mentionRain));

        // LLM 结构化修改先行：模型在现有行程与候选清单内做替换/增删，失败回落规则路径。
        // P1：剩余预算不足时直接走规则路径（改一项的规则替换是确定性的、毫秒级完成），并留下降级痕迹。
        AgentBudget modifyBudget = ctx.getBudget();
        if (llmProperties.usable()) {
            if (modifyBudget.allows(AgentBudget.MIN_STRUCTURED_PLAN_MS)) {
                AgentPlan structured = tryStructuredModify(ctx, intent, city, trip, items, weather,
                        trip.getStartDate(), message, dateNote, modifyBudget);
                if (structured != null) {
                    attachKnowledge(structured, citations);
                    return structured;
                }
            } else {
                ctx.recordBudgetDegradation(AgentBudget.STAGE_PLAN,
                        "预算不足，跳过 LLM 结构化修改，改用规则修改");
            }
        }

        List<ItineraryDtos.ItineraryItemDTO> targets = new ArrayList<>();
        // 注意：不要写成三元表达式——int 与 Integer 混用会被拆箱，currentDay 为 null 时直接 NPE
        final Integer dayFilter = resolveDayFilter(message, ctx.getCurrentDay(), scopeDay);

        // P1 行中锁定：已开始/已完成/已跳过是既成事实，自动重规划不得覆盖（只允许动 PENDING）
        if (IntentResult.TARGET_SELECTED.equals(intent.getTarget()) && ctx.getSelectedElement() != null) {
            Long selId = ctx.getSelectedElement().getId();
            String selTitle = ctx.getSelectedElement().getTitle();
            java.util.Optional<ItineraryDtos.ItineraryItemDTO> selected = items.stream()
                    .filter(i -> selId != null && selId.equals(i.getId())).findFirst()
                    .or(() -> items.stream().filter(i -> selTitle != null && selTitle.equals(i.getTitle())).findFirst());
            if (selected.isPresent() && !isAdjustable(selected.get())) {
                return emptyPlan(intent, city, trip,
                        "「" + selected.get().getTitle() + "」已开始或已完成，不能再被自动替换。"
                                + "如需调整，请先在行程里把它重置为未开始，或改选其他安排。");
            }
            selected.ifPresent(targets::add);
        } else {
            items.stream()
                    .filter(i -> ItineraryItem.TYPE_ATTRACTION.equals(i.getItemType()))
                    .filter(i -> i.getTags() != null && i.getTags().contains("室外"))
                    .filter(i -> dayFilter == null || dayFilter.equals(i.getDayIndex()))
                    .filter(PlannerAgent::isAdjustable)
                    .forEach(targets::add);
        }

        if (targets.isEmpty()) {
            long lockedOutdoor = items.stream()
                    .filter(i -> ItineraryItem.TYPE_ATTRACTION.equals(i.getItemType()))
                    .filter(i -> i.getTags() != null && i.getTags().contains("室外"))
                    .filter(i -> !isAdjustable(i))
                    .count();
            String lockedNote = lockedOutdoor > 0
                    ? "该日的 " + lockedOutdoor + " 个户外景点已开始或已完成，按「只调整未开始条目」的规则保持不动。"
                    : "";
            return emptyPlan(intent, city, trip,
                    "在 Day " + scopeDay + " 没有找到需要替换的未开始户外景点。" + lockedNote + dateNote
                            + "你可以在时间轴上点选一个未开始的安排，然后说「这个换成类似的」。");
        }

        List<PlanOperation> ops = new ArrayList<>();
        List<String> exclude = new ArrayList<>(items.stream().map(ItineraryDtos.ItineraryItemDTO::getTitle).toList());
        List<String> replacements = new ArrayList<>();
        if (!dateNote.isEmpty()) {
            replacements.add(dateNote);
        }
        ItineraryDtos.ItineraryItemDTO lastTarget = null;
        int firstDay = targets.get(0).getDayIndex();

        for (ItineraryDtos.ItineraryItemDTO target : targets) {
            boolean rainHere = weather != null && weather.isRainy();
            String venue = rainHere ? "indoor"
                    : (target.getTags() != null && target.getTags().contains("室内")) ? "indoor" : "outdoor";
            Attraction repl = draftsSupport.pickReplacement(ctx, city, venue, target.getLatitude(), target.getLongitude(), exclude);
            if (repl == null) {
                replacements.add("「" + target.getTitle() + "」暂无合适替代");
                continue;
            }
            ops.add(PlanOperation.builder().type(PlanOperation.REMOVE)
                    .targetItemId(target.getId())
                    .original(OperationHistoryService.toEntity(trip, target))
                    .detail("移除「" + target.getTitle() + "」").build());
            ItineraryItem draft = draftsSupport.draft(trip.getStartDate(), target.getDayIndex(), ItineraryItem.TYPE_ATTRACTION,
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
                Restaurant rest = draftsSupport.pickRestaurant(ctx, city, lastTarget.getLatitude(), lastTarget.getLongitude(), new ArrayList<>());
                if (rest != null) {
                    ops.add(PlanOperation.builder().type(PlanOperation.ADD)
                            .draft(draftsSupport.draft(trip.getStartDate(), diningDay, ItineraryItem.TYPE_RESTAURANT,
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
        extras.put("citations", citations);

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

    // ==================== V2.2 研究循环（FR-03 自主工具调用） ====================

    /** 研究循环允许的只读工具白名单：规划期只允许只读工具，写操作一律经确认门 */
    private static final Set<String> RESEARCH_TOOLS = Set.of(
            "query_weather", "search_attraction", "search_restaurant", "search_hotel", "calculate_route",
            "query_travel_knowledge");

    /**
     * 研究循环步骤上限：每步一次 LLM 决策 + 至多一次工具调用，避免整体规划被显著拖慢。
     * P0-B 从 4 提到 6：真实调用里 4 步经常刚够查天气就耗尽，模型来不及输出 FINAL；
     * 每步仍受 AgentBudget 的 MIN_RESEARCH_STEP_MS 门禁约束，预算不足会提前收尾而不是拖爆总预算。
     */
    private static final int RESEARCH_MAX_STEPS = 6;

    /**
     * 让 research-agent 在只读工具上自主「决策→调用→观察→继续」地收集事实（FR-03）。
     *
     * <p>循环未完成（模型不可用 / 预算耗尽且零成功观察 / 输出非法）时<b>仍返回已成功收集到的观察</b>：
     * 预算耗尽是常态（模型会先用几步确认天气/候选），已经查到的事实没有理由丢掉。
     * P0-B 起，步数/时间耗尽但已有成功观察会返回 {@code completed=true, partial=true}，
     * 追踪里显示「研究部分完成」而不是「研究未完成」。
     * 只有「一步都没成功」时才返回空清单，此时结构化规划照旧执行、Prompt 不带研究块，
     * 无 LLM 时行为与从前完全一致。收集到的 notes 只作为参考证据进入结构化 Prompt
     * （见 StructuredPlanner.researchBlock），名称命中、坐标与价格仍由候选清单与校验层决定——
     * 研究结论不能变成编造事实。
     */
    private List<String> researchFacts(AgentContext ctx, CityCatalog city, TripDraft draft, BigDecimal budget) {
        if (ctx.isCancelled()) {
            return List.of();
        }
        String goal = "为「" + city.name() + "」" + draft.getStartDate() + " 出发、共 " + draft.getDays()
                + " 天、" + BudgetService.effectiveParty(draft.getPartySize()) + " 人、预算 ¥"
                + budget.stripTrailingZeros().toPlainString()
                + " 的行程收集决策事实：逐日天气与降雨风险、景点/餐厅/酒店候选是否充足、"
                + "主要相邻地点之间的移动耗时，以及目的地攻略/预约/交通注意事项（知识库检索）。";
        AgentToolLoop.LoopResult result = taskRecorder.around(ctx,
                com.travel.agent.entity.AgentTask.RECEIVER_RESEARCH, "RESEARCH",
                () -> toolLoop.run(ctx, goal, RESEARCH_TOOLS, RESEARCH_MAX_STEPS),
                r -> {
                    // P0-B：完成 / 部分完成 / 未完成三种终态分开写，耗尽不再一律记成「研究未完成」
                    String state = !r.completed() ? "研究未完成"
                            : (r.partial() ? "研究部分完成" : "研究完成");
                    long facts = r.steps().stream().filter(AgentToolLoop.ToolStep::success).count();
                    return state + "：" + r.steps().size() + " 次工具调用，可用事实 " + facts + " 条"
                            + (r.summary() == null || r.summary().isBlank() ? "" : "（" + r.summary() + "）");
                });
        // 未完成也保留已成功的事实：notes() 只取 success=true 的步骤
        return result.notes();
    }

    /**
     * P1：规划期的 RAG 证据检索。
     *
     * <p>直接检索（而不是只依赖研究循环）是为了保证「无论 LLM 是否可用」方案都能带上知识依据：
     * 命中结果同时发 {@code rag_result} SSE 事件，方案 extras 里保留完整引用结构。
     * 相似度低于 0.05 的噪声不进入方案；检索失败只告警，绝不阻断规划。
     */
    private List<RagCitation> knowledgeCitations(AgentContext ctx, String city, String message) {
        if (message == null || message.isBlank()) {
            return List.of();
        }
        try {
            String query = city == null || city.isBlank() ? message : city + " " + message;
            List<RagCitation> hits = ragService.searchWithCitations(query, city, ctx.getUserId(), 3);
            List<RagCitation> top = hits.stream()
                    .filter(citation -> citation.score() >= 0.05)
                    .limit(3)
                    .toList();
            if (!top.isEmpty()) {
                ctx.emit("rag_result", Map.of(
                        "sources", top.stream().map(RagCitation::label).distinct().toList(),
                        "citations", top));
            }
            return top;
        } catch (RuntimeException e) {
            log.warn("规划期知识库检索失败，方案不带知识引用: {}", e.getMessage());
            return List.of();
        }
    }

    /** 把引用挂到结构化方案上（extras + reasoning），前端与确认弹窗可追溯 */
    private static void attachKnowledge(AgentPlan plan, List<RagCitation> citations) {
        if (plan == null || citations == null || citations.isEmpty()) {
            return;
        }
        Map<String, Object> extras = plan.getExtras() == null
                ? new HashMap<>() : new HashMap<>(plan.getExtras());
        extras.put("citations", citations);
        plan.setExtras(extras);
        String reasoning = plan.getReasoning() == null ? "" : plan.getReasoning();
        if (!reasoning.contains("知识依据")) {
            String labels = citations.stream().map(RagCitation::label).distinct()
                    .collect(java.util.stream.Collectors.joining("、"));
            plan.setReasoning(reasoning + (reasoning.isEmpty() ? "" : " ") + "知识依据：" + labels + "。");
        }
    }

    // ==================== LLM 结构化规划组装 ====================

    /** 结构化新行程：LLM 输出的条目序列 → REMOVE（重规划）+ ADD 计划；失败返回 null 走规则路径 */
    private AgentPlan tryStructuredNewTrip(AgentContext ctx, IntentResult intent, CityCatalog city,
                                           Trip trip, TripDraft draft, int days, BigDecimal budget, String message,
                                           List<String> researchNotes, AgentBudget timeBudget) {
        StructuredPlanner.TripDraftView view = new StructuredPlanner.TripDraftView(
                city.name(), days, draft.getStartDate(), budget,
                BudgetService.effectiveParty(draft.getPartySize()), draft.getPreferences());
        StructuredPlanner.NewTripParse parse = structuredPlanner.planNewTrip(city, view, message, ctx.getHistory(),
                researchNotes, timeBudget);
        if (parse == null || !parse.ok()) {
            if (parse != null && !parse.warnings().isEmpty()) {
                log.info("结构化规划回落规则路径: {}", parse.warnings());
            }
            return null;
        }
        List<ItineraryItem> drafts = new ArrayList<>(parse.drafts());
        appendTransportLegs(ctx, drafts);

        List<PlanOperation> ops = new ArrayList<>();
        if (trip != null) {
            for (ItineraryItem item : itineraryService.listEntities(trip.getId())) {
                ops.add(PlanOperation.builder().type(PlanOperation.REMOVE)
                        .targetItemId(item.getId()).original(item)
                        .detail("清除原安排：" + item.getTitle()).build());
            }
        }
        for (ItineraryItem item : drafts) {
            ops.add(PlanOperation.builder().type(PlanOperation.ADD).draft(item)
                    .detail("新增：" + item.getTitle()).build());
        }

        BigDecimal total = budgetService.totalOf(drafts);
        String hotelName = drafts.stream()
                .filter(i -> ItineraryItem.TYPE_HOTEL.equals(i.getItemType()))
                .findFirst().map(i -> i.getTitle().replaceFirst("^入住 · ", "")).orElse("暂无");
        Map<String, Object> extras = new HashMap<>();
        extras.put("hotelName", hotelName);
        extras.put("total", total);
        extras.put("days", days);
        String warnings = parse.warnings().isEmpty() ? ""
                : "（模型输出已按候选清单校正：" + String.join("；", parse.warnings().stream().limit(2).toList()) + "）";
        return AgentPlan.builder()
                .planId(UUID.randomUUID().toString())
                .intent(intent.getIntent())
                .target(intent.getTarget())
                .tripId(trip == null ? null : trip.getId())
                .city(city)
                .tripDraft(draft)
                .operations(ops)
                .focus(AgentDtos.AgentFocus.builder().type("itinerary").day(1).build())
                .reasoning((parse.reasoning().isEmpty() ? "按你的需求从候选清单编排。" : parse.reasoning()) + warnings)
                .extras(extras)
                .build();
    }

    /** 结构化修改：LLM 的 REMOVE/ADD 意图 → 校验后的操作计划；失败返回 null 走规则路径 */
    private AgentPlan tryStructuredModify(AgentContext ctx, IntentResult intent, CityCatalog city, Trip trip,
                                          List<ItineraryDtos.ItineraryItemDTO> items, WeatherInfo weather,
                                          LocalDate startDate, String message, String dateNote,
                                          AgentBudget timeBudget) {
        String weatherHint = weather == null ? null
                : weather.condition() + "，降雨概率 " + weather.rainProbability()
                        + "%（" + (weather.isRainy() ? "雨天优先把户外景点换成室内" : "适合户外安排") + "）";
        StructuredPlanner.ModificationParse parse =
                structuredPlanner.planModification(city, items, trip.getStartDate(), weatherHint, message,
                        ctx.getHistory(), timeBudget);
        if (parse == null || !parse.ok()) {
            return null;
        }
        // P1 行中锁定复核：模型不得替换已开始/已完成/已跳过的条目；命中即回退规则路径
        java.util.Set<Long> lockedIds = items.stream()
                .filter(i -> !isAdjustable(i))
                .map(ItineraryDtos.ItineraryItemDTO::getId)
                .filter(java.util.Objects::nonNull)
                .collect(java.util.stream.Collectors.toSet());
        boolean touchesLocked = parse.removes().stream()
                .anyMatch(remove -> remove.target() != null && lockedIds.contains(remove.target().getId()));
        if (touchesLocked) {
            log.info("结构化修改命中了已开始/已完成条目，按行中锁定规则回退规则路径");
            return null;
        }
        List<PlanOperation> ops = new ArrayList<>();
        List<String> replacements = new ArrayList<>();
        if (!dateNote.isEmpty()) {
            replacements.add(dateNote);
        }
        Integer focusDay = null;
        for (StructuredPlanner.ModificationRemove remove : parse.removes()) {
            ops.add(PlanOperation.builder().type(PlanOperation.REMOVE)
                    .targetItemId(remove.target().getId())
                    .original(OperationHistoryService.toEntity(trip, remove.target()))
                    .detail("移除「" + remove.target().getTitle() + "」").build());
            replacements.add("移除「" + remove.target().getTitle() + "」");
            focusDay = remove.target().getDayIndex();
        }
        for (ItineraryItem add : parse.adds()) {
            ops.add(PlanOperation.builder().type(PlanOperation.ADD).draft(add)
                    .detail("新增「" + add.getTitle() + "」").build());
            replacements.add("新增「" + add.getTitle() + "」");
            focusDay = focusDay == null ? add.getDayIndex() : focusDay;
        }
        if (ops.isEmpty()) {
            return null;
        }
        int day = focusDay != null ? focusDay : 1;
        Map<String, Object> extras = new HashMap<>();
        extras.put("weather", weather);
        extras.put("replacements", replacements);
        extras.put("day", day);
        String warnings = parse.warnings().isEmpty() ? ""
                : "（模型输出已按候选清单校正：" + String.join("；", parse.warnings().stream().limit(2).toList()) + "）";
        return AgentPlan.builder()
                .planId(UUID.randomUUID().toString())
                .intent(intent.getIntent())
                .target(intent.getTarget())
                .tripId(trip.getId())
                .city(city)
                .operations(ops)
                .focus(AgentDtos.AgentFocus.builder().type("itinerary").day(day).build())
                .reasoning((weather == null ? "" : "天气：" + weather.condition() + "（降雨概率 " + weather.rainProbability() + "%）；")
                        + "替换方案：" + String.join("，", replacements) + warnings)
                .extras(extras)
                .build();
    }

    /** 结构化输出逐天补打车交通：按条目坐标链估算，与规则路径的交通条目口径一致 */
    private void appendTransportLegs(AgentContext ctx, List<ItineraryItem> drafts) {
        Map<Integer, List<ItineraryItem>> byDay = new java.util.LinkedHashMap<>();
        for (ItineraryItem item : drafts) {
            byDay.computeIfAbsent(item.getDayIndex(), k -> new ArrayList<>()).add(item);
        }
        LocalDate baseDate = drafts.get(0).getStartTime() != null
                ? drafts.get(0).getStartTime().toLocalDate()
                : LocalDate.now();
        List<ItineraryItem> extra = new ArrayList<>();
        for (Map.Entry<Integer, List<ItineraryItem>> entry : byDay.entrySet()) {
            Double prevLat = null;
            Double prevLng = null;
            BigDecimal transport = BigDecimal.ZERO;
            for (ItineraryItem item : entry.getValue()) {
                if (item.getLatitude() != null && item.getLongitude() != null) {
                    transport = transport.add(draftsSupport.legTaxiCost(ctx, prevLat, prevLng, item.getLatitude(), item.getLongitude()));
                    prevLat = item.getLatitude();
                    prevLng = item.getLongitude();
                }
            }
            if (transport.compareTo(BigDecimal.ZERO) > 0) {
                extra.add(draftsSupport.draft(baseDate, entry.getKey(), ItineraryItem.TYPE_TRANSPORT, "当日交通（打车）",
                        "19:45", "20:00", null, null, transport, List.of("交通"),
                        "Agent 根据路线距离估算的市内交通费用"));
            }
        }
        drafts.addAll(extra);
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

    /** 修改范围的"天"：说了相对日期就用它，否则用当前聚焦的天（可能为空=全部） */
    private static Integer resolveDayFilter(String message, Integer currentDay, int scopeDay) {
        if (MessageParser.relativeDayOffset(message) != MessageParser.NO_RELATIVE_DATE) {
            return scopeDay;
        }
        return currentDay;
    }

    /** 从 LLM 约束或消息句式中提取目录外城市名 */
    private String onlineCityName(IntentResult intent, String message) {
        Object dest = intent.getConstraints() == null ? null : intent.getConstraints().get("destination");
        if (dest instanceof String s && !s.isBlank()) {
            return s.trim();
        }
        return MessageParser.guessCityName(message);
    }

    /** 联网查询城市目录（经 ToolInvoker 以便聊天流展示 tool_call 过程）；预算不足时跳过并留痕 */
    private CityCatalog lookupOnlineCity(AgentContext ctx, AgentBudget budget, String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        if (!budget.allowsLlmCall(AgentBudget.MIN_LLM_CALL_MS)) {
            ctx.recordBudgetDegradation(AgentBudget.STAGE_PLAN,
                    "预算不足，跳过联网城市查询（联网生成目录需一次 LLM 调用）");
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

    static String unsupportedCityMessage(String requested, String supported, boolean llmUsable) {
        String reason = llmUsable
                ? "联网查询也没找到这座城市"
                : "当前未配置大模型（LLM_API_KEY），无法联网生成城市数据";
        return "抱歉，暂时没能为「" + requested + "」生成行程：" + reason + "。\n"
                + "本地已开通：" + supported + "；配置 LLM_API_KEY 后任意城市都能自动规划。";
    }
}

