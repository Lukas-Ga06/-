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
    /** P1：手工增删改也写旅行事件（回顾页可追溯） */
    private final com.travel.agent.service.TripEventService tripEventService;

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
        tripEventService.record(trip.getId(), saved.getId(),
                com.travel.agent.entity.TripEvent.TYPE_MANUAL_EDIT, "user",
                "手动修改：「" + saved.getTitle() + "」");
        return ApiResponse.ok(itineraryService.toDto(saved, trip));
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
        tripEventService.record(trip.getId(), null,
                com.travel.agent.entity.TripEvent.TYPE_MANUAL_EDIT, "user",
                "删除行程项：「" + item.getTitle() + "」");
        return ApiResponse.ok(null);
    }

    /**
     * 行程项状态推进（P1）：开始 / 完成 / 跳过 / 重置。
     *
     * <p>完成会同时写 startedAt / completedAt / visitedAt，并追加一条 CHECK_IN 旅行事件；
     * 状态变化同样进撤销链（与本次修改同事务），用户误点「完成」可以撤销。
     */
    @PutMapping("/{id}/status")
    public ApiResponse<ItineraryDtos.ItineraryItemDTO> updateStatus(
            @PathVariable Long id,
            @Valid @RequestBody ItineraryDtos.UpdateStatusRequest request,
            Authentication authentication) {
        User user = authService.requireUser(authentication.getName());
        var item = itineraryService.findById(id);
        Trip trip = tripService.getOwned(item.getTripId(), user.getId());
        var saved = historyService.withHistory(trip, user.getId(),
                "行程项状态：" + item.getTitle() + " → " + request.getStatus(),
                () -> itineraryService.updateStatus(trip, id, request.getStatus()));
        return ApiResponse.ok(itineraryService.toDto(saved, trip));
    }
}
