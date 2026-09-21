package com.travel.agent.service;

import com.travel.agent.dto.AgentDtos;
import com.travel.agent.entity.ItineraryItem;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class BudgetService {

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
}
