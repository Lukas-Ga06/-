package com.travel.agent.controller;

import com.travel.agent.dto.ApiResponse;
import com.travel.agent.dto.ItineraryDtos;
import com.travel.agent.entity.Trip;
import com.travel.agent.entity.User;
import com.travel.agent.service.AuthService;
import com.travel.agent.service.ItineraryService;
import com.travel.agent.service.OperationHistoryService;
import com.travel.agent.service.TripService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/itinerary")
@RequiredArgsConstructor
public class ItineraryController {

    private final ItineraryService itineraryService;
    private final TripService tripService;
    private final AuthService authService;
    private final OperationHistoryService historyService;

    @PutMapping("/{id}")
    public ApiResponse<ItineraryDtos.ItineraryItemDTO> update(@PathVariable Long id,
                                                              @Valid @RequestBody ItineraryDtos.UpdateItemRequest request,
                                                              Authentication authentication) {
        User user = authService.requireUser(authentication.getName());
        var item = itineraryService.findById(id);
        Trip trip = tripService.getOwned(item.getTripId(), user.getId());
        // 手工修改同样进撤销链（与本次修改同事务）
        var saved = historyService.withHistory(trip, user.getId(), "手动修改行程项",
                () -> itineraryService.updateItem(trip, id, request));
        return ApiResponse.ok(ItineraryDtos.ItineraryItemDTO.from(saved));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable Long id, Authentication authentication) {
        User user = authService.requireUser(authentication.getName());
        var item = itineraryService.findById(id);
        Trip trip = tripService.getOwned(item.getTripId(), user.getId());
        historyService.withHistory(trip, user.getId(), "手动删除行程项：" + item.getTitle(), () -> {
            itineraryService.deleteItem(trip, id);
            return null;
        });
        return ApiResponse.ok(null);
    }
}
