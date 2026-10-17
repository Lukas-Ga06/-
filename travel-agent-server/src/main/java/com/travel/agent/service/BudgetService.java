package com.travel.agent.service;

import com.travel.agent.dto.BudgetDtos;
import com.travel.agent.dto.AgentDtos;
import com.travel.agent.entity.Expense;
import com.travel.agent.entity.ItineraryItem;
import com.travel.agent.entity.Trip;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
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
                .build();
    }

    private static Map<String, BigDecimal> emptyCategoryMap() {
        Map<String, BigDecimal> map = new LinkedHashMap<>();
        CATEGORY_ORDER.forEach(category -> map.put(category, BigDecimal.ZERO));
        return map;
    }
}
