package com.travel.agent.service;

import com.travel.agent.dto.BudgetDtos;
import com.travel.agent.dto.AgentDtos;
import com.travel.agent.entity.Expense;
import com.travel.agent.entity.ItineraryItem;
import com.travel.agent.entity.Trip;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@Service
public class BudgetService {

    /** 行程项类型 → 记账分类，用于「计划 vs 实际」的分类对账 */
    private static final Map<String, String> ITEM_TYPE_TO_CATEGORY = Map.of(
            ItineraryItem.TYPE_HOTEL, Expense.CAT_HOTEL,
            ItineraryItem.TYPE_RESTAURANT, Expense.CAT_FOOD,
            ItineraryItem.TYPE_TRANSPORT, Expense.CAT_TRANSPORT,
            ItineraryItem.TYPE_ATTRACTION, Expense.CAT_TICKET);

    private static final List<String> CATEGORY_ORDER = List.of(
            Expense.CAT_FOOD, Expense.CAT_TRANSPORT, Expense.CAT_HOTEL,
            Expense.CAT_TICKET, Expense.CAT_SHOPPING, Expense.CAT_OTHER);

    public AgentDtos.BudgetSummary summarize(List<ItineraryItem> items) {
        Map<String, BigDecimal> breakdown = new LinkedHashMap<>();
        BigDecimal total = BigDecimal.ZERO;
        for (ItineraryItem item : items) {
            if (item.getCost() == null) continue;
            breakdown.merge(item.getItemType(), item.getCost(), BigDecimal::add);
            total = total.add(item.getCost());
        }
        return AgentDtos.BudgetSummary.builder()
                .total(total)
                .breakdown(breakdown)
                .build();
    }

    public AgentDtos.BudgetSummary summarize(List<ItineraryItem> after, BigDecimal previousTotal) {
        AgentDtos.BudgetSummary summary = summarize(after);
        summary.setPreviousTotal(previousTotal);
        if (previousTotal != null) {
            summary.setDifference(summary.getTotal().subtract(previousTotal));
        }
        return summary;
    }

    public BigDecimal totalOf(List<ItineraryItem> items) {
        return items.stream()
                .map(ItineraryItem::getCost)
                .filter(c -> c != null)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    // ==================== 人数口径（FR-01） ====================
    // 票价/餐费是「人均」、房价是「每间」，交通是「整车」。口径只在这一处定义，
    // 规则规划器与 LLM 结构化规划器都调用它，避免两条路径算出不同总价。

    /** 人数兜底：null/≤0 → 1（升级前的单人成本口径），超过 20 人截断 */
    public static int effectiveParty(Integer partySize) {
        if (partySize == null || partySize <= 0) {
            return 1;
        }
        return Math.min(partySize, 20);
    }

    /** 酒店房间数：双人间口径，两人一间、向上取整，至少 1 间 */
    public static int roomsFor(int partySize) {
        int people = effectiveParty(partySize);
        return Math.max(1, (people + 1) / 2);
    }

    /** 人均单价按人数放大（景点门票 / 餐饮人均）；单价为空按 0 计 */
    public static BigDecimal perPerson(BigDecimal unitPrice, int partySize) {
        if (unitPrice == null) {
            return BigDecimal.ZERO;
        }
        return unitPrice.multiply(BigDecimal.valueOf(effectiveParty(partySize)));
    }

    /**
     * 预算执行摘要：把三套口径放到一张表里——
     * 计划成本（行程项）、行程预算、实际支出（记账），并给出偏差与分类对账。
     */
    public BudgetDtos.BudgetExecution execution(Trip trip, List<ItineraryItem> items, List<Expense> expenses) {
        BigDecimal planned = totalOf(items);
        BigDecimal actual = expenses.stream()
                .map(Expense::getAmount)
                .filter(Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal budget = trip.getBudget();

        Map<String, BigDecimal> plannedByCategory = emptyCategoryMap();
        for (ItineraryItem item : items) {
            if (item.getCost() == null) {
                continue;
            }
            String category = ITEM_TYPE_TO_CATEGORY.getOrDefault(item.getItemType(), Expense.CAT_OTHER);
            plannedByCategory.merge(category, item.getCost(), BigDecimal::add);
        }
        Map<String, BigDecimal> actualByCategory = emptyCategoryMap();
        for (Expense expense : expenses) {
            String category = expense.getCategory() == null ? Expense.CAT_OTHER : expense.getCategory();
            actualByCategory.merge(category, expense.getAmount() == null ? BigDecimal.ZERO : expense.getAmount(), BigDecimal::add);
        }

        List<BudgetDtos.CategoryBudget> categories = CATEGORY_ORDER.stream()
                .map(category -> BudgetDtos.CategoryBudget.builder()
                        .category(category)
                        .planned(plannedByCategory.get(category))
                        .actual(actualByCategory.get(category))
                        .build())
                .filter(c -> c.getPlanned().signum() != 0 || c.getActual().signum() != 0)
                .toList();

        BigDecimal remaining = budget == null ? null : budget.subtract(actual);
        BigDecimal executionRate = budget == null || budget.signum() == 0
                ? null
                : actual.divide(budget, 4, RoundingMode.HALF_UP);

        return BudgetDtos.BudgetExecution.builder()
                .tripId(trip.getId())
                .tripName(trip.getName())
                .budget(budget)
                .planned(planned)
                .actual(actual)
                .remaining(remaining)
                .variance(actual.subtract(planned))
                .executionRate(executionRate)
                .overBudget(budget != null && actual.compareTo(budget) > 0)
                .planOverBudget(budget != null && planned.compareTo(budget) > 0)
                .categories(categories)
                .daily(dailyBreakdown(trip, items, expenses))
                .build();
    }

    /**
     * 按天偏差：计划按行程项 dayIndex 聚合；实际按记账日期映射到行程天
     * （between(startDate, spentAt)+1），行程区间外（行前/行后采购）或日期缺失归 day=0。
     * 只有存在计划或实际的天才出现在结果里。
     */
    private List<BudgetDtos.DailyBudget> dailyBreakdown(Trip trip, List<ItineraryItem> items, List<Expense> expenses) {
        Map<Integer, BigDecimal> plannedByDay = new LinkedHashMap<>();
        for (ItineraryItem item : items) {
            if (item.getCost() == null || item.getDayIndex() == null) {
                continue;
            }
            plannedByDay.merge(item.getDayIndex(), item.getCost(), BigDecimal::add);
        }
        Map<Integer, BigDecimal> actualByDay = new LinkedHashMap<>();
        for (Expense expense : expenses) {
            if (expense.getAmount() == null) {
                continue;
            }
            actualByDay.merge(dayOf(trip, expense), expense.getAmount(), BigDecimal::add);
        }
        java.util.Set<Integer> days = new java.util.TreeSet<>();
        days.addAll(plannedByDay.keySet());
        days.addAll(actualByDay.keySet());
        List<BudgetDtos.DailyBudget> daily = new ArrayList<>();
        for (int day : days) {
            BigDecimal planned = plannedByDay.getOrDefault(day, BigDecimal.ZERO);
            BigDecimal actual = actualByDay.getOrDefault(day, BigDecimal.ZERO);
            daily.add(BudgetDtos.DailyBudget.builder()
                    .day(day)
                    .planned(planned)
                    .actual(actual)
                    .variance(actual.subtract(planned))
                    .build());
        }
        return daily;
    }

    /** 记账日期 → 行程天；行程无日期、支出无日期或不在区间内均归 0（行程外） */
    private static int dayOf(Trip trip, Expense expense) {
        if (trip.getStartDate() == null || expense.getSpentAt() == null) {
            return 0;
        }
        long offset = java.time.temporal.ChronoUnit.DAYS.between(trip.getStartDate(), expense.getSpentAt());
        if (offset < 0 || offset >= trip.totalDays()) {
            return 0;
        }
        return (int) offset + 1;
    }

    private static Map<String, BigDecimal> emptyCategoryMap() {
        Map<String, BigDecimal> map = new LinkedHashMap<>();
        CATEGORY_ORDER.forEach(category -> map.put(category, BigDecimal.ZERO));
        return map;
    }
}
