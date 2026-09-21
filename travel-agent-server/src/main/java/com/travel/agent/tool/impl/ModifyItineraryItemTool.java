package com.travel.agent.tool.impl;

import com.travel.agent.dto.ItineraryDtos;
import com.travel.agent.entity.ItineraryItem;
import com.travel.agent.entity.Trip;
import com.travel.agent.repository.ItineraryItemRepository;
import com.travel.agent.repository.TripRepository;
import com.travel.agent.service.ItineraryService;
import com.travel.agent.tool.AgentTool;
import com.travel.agent.tool.RiskLevel;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.Map;

/** modify_itinerary_item：修改行程条目 */
@Component
@RequiredArgsConstructor
public class ModifyItineraryItemTool implements AgentTool {

    private final ItineraryService itineraryService;
    private final TripRepository tripRepository;
    private final ItineraryItemRepository itemRepository;

    @Override
    public String getName() {
        return "modify_itinerary_item";
    }

    @Override
    public String getDescription() {
        return "修改行程中的某条安排（时间/标题/费用等）";
    }

    @Override
    public RiskLevel getRiskLevel() {
        return RiskLevel.MEDIUM;
    }

    @Override
    public Object execute(Map<String, Object> args) {
        Long tripId = ((Number) args.get("tripId")).longValue();
        Long itemId = ((Number) args.get("itemId")).longValue();
        Trip trip = tripRepository.findById(tripId).orElseThrow();
        ItineraryItem existing = itemRepository.findById(itemId)
                .filter(i -> i.getTripId().equals(tripId))
                .orElseThrow(() -> new com.travel.agent.exception.BusinessException(404, "行程条目不存在"));
        Map<String, Object> patch = (Map<String, Object>) args.get("patch");
        ItineraryDtos.UpdateItemRequest req = new ItineraryDtos.UpdateItemRequest(
                patch.get("dayIndex") == null ? null : ((Number) patch.get("dayIndex")).intValue(),
                (String) patch.get("itemType"),
                (String) patch.get("title"),
                (String) patch.get("startTime"),
                (String) patch.get("endTime"),
                patch.get("cost") == null ? null : new java.math.BigDecimal(String.valueOf(patch.get("cost"))),
                patch.get("latitude") == null ? null : ((Number) patch.get("latitude")).doubleValue(),
                patch.get("longitude") == null ? null : ((Number) patch.get("longitude")).doubleValue(),
                patch.get("tags") == null ? null : Arrays.asList(String.valueOf(patch.get("tags")).split(",")),
                (String) patch.get("description"));
        ItineraryItem saved = itineraryService.updateItem(trip, existing.getId(), req);
        return ItineraryDtos.ItineraryItemDTO.from(saved);
    }
}
