package com.travel.agent.tool.impl;

import com.travel.agent.dto.ItineraryDtos;
import com.travel.agent.entity.ItineraryItem;
import com.travel.agent.entity.Trip;
import com.travel.agent.repository.TripRepository;
import com.travel.agent.service.ItineraryService;
import com.travel.agent.tool.AgentTool;
import com.travel.agent.tool.RiskLevel;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.Map;

/** add_itinerary_item：向行程添加条目（MEDIUM：执行后需用户确认提交） */
@Component
@RequiredArgsConstructor
public class AddItineraryItemTool implements AgentTool {

    private final ItineraryService itineraryService;
    private final TripRepository tripRepository;

    @Override
    public String getName() {
        return "add_itinerary_item";
    }

    @Override
    public String getDescription() {
        return "向指定行程添加一条安排（景点/酒店/餐厅）";
    }

    @Override
    public RiskLevel getRiskLevel() {
        return RiskLevel.MEDIUM;
    }

    @Override
    @SuppressWarnings("unchecked")
    public Object execute(Map<String, Object> args) {
        Long tripId = ((Number) args.get("tripId")).longValue();
        Trip trip = tripRepository.findById(tripId).orElseThrow();
        Map<String, Object> item = (Map<String, Object>) args.get("item");
        ItineraryDtos.AddItemRequest req = new ItineraryDtos.AddItemRequest(
                toInt(item.get("dayIndex")),
                (String) item.get("itemType"),
                (String) item.get("title"),
                (String) item.get("startTime"),
                (String) item.get("endTime"),
                item.get("cost") == null ? null : new java.math.BigDecimal(String.valueOf(item.get("cost"))),
                toDouble(item.get("latitude")),
                toDouble(item.get("longitude")),
                item.get("tags") == null ? null : Arrays.asList(String.valueOf(item.get("tags")).split(",")),
                (String) item.get("description"));
        ItineraryItem saved = itineraryService.addItem(trip, req);
        return ItineraryDtos.ItineraryItemDTO.from(saved);
    }

    private Integer toInt(Object o) {
        return o == null ? null : ((Number) o).intValue();
    }

    private Double toDouble(Object o) {
        return o == null ? null : ((Number) o).doubleValue();
    }
}
