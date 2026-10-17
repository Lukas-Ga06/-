package com.travel.agent.controller;

import com.travel.agent.dto.ApiResponse;
import com.travel.agent.dto.BudgetDtos;
import com.travel.agent.dto.ItineraryDtos;
import com.travel.agent.dto.TripDtos;
import com.travel.agent.entity.Trip;
import com.travel.agent.entity.User;
import com.travel.agent.service.AuthService;
import com.travel.agent.service.BudgetExecutionService;
import com.travel.agent.service.ItineraryService;
import com.travel.agent.service.OperationHistoryService;
import com.travel.agent.service.TripMapService;
import com.travel.agent.service.TripService;
import com.travel.agent.util.Paging;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/trips")
@RequiredArgsConstructor
public class TripController {

    private final TripService tripService;
    private final ItineraryService itineraryService;
    private final AuthService authService;
    private final TripMapService tripMapService;
    private final OperationHistoryService historyService;
    private final BudgetExecutionService budgetExecutionService;

    @GetMapping
    public ApiResponse<List<TripDtos.TripResponse>> list(
            @RequestParam(defaultValue = "" + Paging.DEFAULT_LIMIT) int limit,
            @RequestParam(defaultValue = "0") int offset,
            Authentication authentication) {
        User user = authService.requireUser(authentication.getName());
        return ApiResponse.ok(Paging.slice(tripService.list(user.getId()), offset, limit));
    }

    /** 首页足迹地图：各行程目的地坐标 */
    @GetMapping("/map")
    public ApiResponse<List<TripDtos.TripMapPoint>> map(Authentication authentication) {
        User user = authService.requireUser(authentication.getName());
        return ApiResponse.ok(tripMapService.points(user.getId()));
    }

    @PostMapping
    public ApiResponse<TripDtos.TripResponse> create(@Valid @RequestBody TripDtos.TripRequest request, Authentication authentication) {
        User user = authService.requireUser(authentication.getName());
        return ApiResponse.ok(TripDtos.TripResponse.from(tripService.create(user.getId(), request)));
    }

    @GetMapping("/{id}")
    public ApiResponse<TripDtos.TripResponse> get(@PathVariable Long id, Authentication authentication) {
        User user = authService.requireUser(authentication.getName());
        return ApiResponse.ok(TripDtos.TripResponse.from(tripService.getOwned(id, user.getId())));
    }

    @PutMapping("/{id}")
    public ApiResponse<TripDtos.TripResponse> update(@PathVariable Long id, @Valid @RequestBody TripDtos.TripRequest request,
                                                     Authentication authentication) {
        User user = authService.requireUser(authentication.getName());
        return ApiResponse.ok(TripDtos.TripResponse.from(tripService.update(id, user.getId(), request)));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable Long id, Authentication authentication) {
        User user = authService.requireUser(authentication.getName());
        tripService.delete(id, user.getId());
        return ApiResponse.ok(null);
    }

    /** 确认 / 取消 / 恢复行程（阶段仍由日期派生） */
    @PutMapping("/{id}/status")
    public ApiResponse<TripDtos.TripResponse> updateStatus(@PathVariable Long id,
                                                           @Valid @RequestBody TripDtos.TripStatusRequest request,
                                                           Authentication authentication) {
        User user = authService.requireUser(authentication.getName());
        return ApiResponse.ok(TripDtos.TripResponse.from(
                tripService.updateStatus(id, user.getId(), request.getStatus())));
    }

    @GetMapping("/{id}/itinerary")
    public ApiResponse<List<ItineraryDtos.ItineraryItemDTO>> itinerary(@PathVariable Long id, Authentication authentication) {
        User user = authService.requireUser(authentication.getName());
        tripService.getOwned(id, user.getId());
        return ApiResponse.ok(itineraryService.listByTrip(id));
    }

    /** 预算执行：计划成本 / 行程预算 / 实际支出 / 偏差 + 分类对账 */
    @GetMapping("/{id}/budget")
    public ApiResponse<BudgetDtos.BudgetExecution> budget(@PathVariable Long id, Authentication authentication) {
        User user = authService.requireUser(authentication.getName());
        return ApiResponse.ok(budgetExecutionService.forTrip(id, user.getId()));
    }

    @PostMapping("/{id}/itinerary")
    public ApiResponse<ItineraryDtos.ItineraryItemDTO> addItem(@PathVariable Long id,
                                                               @Valid @RequestBody ItineraryDtos.AddItemRequest request,
                                                               Authentication authentication) {
        User user = authService.requireUser(authentication.getName());
        Trip trip = tripService.getOwned(id, user.getId());
        var saved = historyService.withHistory(trip, user.getId(), "手动新增行程项：" + request.getTitle(),
                () -> itineraryService.addItem(trip, request));
        return ApiResponse.ok(ItineraryDtos.ItineraryItemDTO.from(saved));
    }
}
