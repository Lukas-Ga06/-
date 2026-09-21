package com.travel.agent.tool.impl;

import com.travel.agent.dto.AgentDtos;
import com.travel.agent.service.BudgetService;
import com.travel.agent.service.ItineraryService;
import com.travel.agent.tool.AgentTool;
import com.travel.agent.tool.RiskLevel;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Map;

/** calculate_budget：预算计算 */
@Component
@RequiredArgsConstructor
public class CalculateBudgetTool implements AgentTool {

    private final ItineraryService itineraryService;
    private final BudgetService budgetService;

    @Override
    public String getName() {
        return "calculate_budget";
    }

    @Override
    public String getDescription() {
        return "计算行程总花费与分项预算";
    }

    @Override
    public RiskLevel getRiskLevel() {
        return RiskLevel.LOW;
    }

    @Override
    public Object execute(Map<String, Object> args) {
        Long tripId = ((Number) args.get("tripId")).longValue();
        AgentDtos.BudgetSummary summary = budgetService.summarize(itineraryService.listEntities(tripId));
        return summary;
    }
}
