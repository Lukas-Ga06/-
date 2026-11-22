package com.travel.agent.tool.impl;

import com.travel.agent.dto.ItineraryDtos;
import com.travel.agent.service.ItineraryService;
import com.travel.agent.tool.AgentTool;
import com.travel.agent.tool.RiskLevel;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/** get_itinerary：获取当前行程 */
@Component
@RequiredArgsConstructor
public class GetItineraryTool implements AgentTool {

    private final ItineraryService itineraryService;

    @Override
    public String getName() {
        return "get_itinerary";
    }

    @Override
    public String getDescription() {
        return "获取指定行程的完整行程单";
    }

    @Override
    public RiskLevel getRiskLevel() {
        return RiskLevel.LOW;
    }

    @Override
    public Object execute(Map<String, Object> args) {
        Long tripId = ((Number) args.get("tripId")).longValue();
        List<ItineraryDtos.ItineraryItemDTO> items = itineraryService.listByTrip(tripId);
        return items;
    }
}
