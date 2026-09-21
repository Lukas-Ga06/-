package com.travel.agent.controller;

import com.travel.agent.dto.ApiResponse;
import com.travel.agent.dto.ItineraryDtos;
import com.travel.agent.dto.TripDtos;
import com.travel.agent.entity.Trip;
import com.travel.agent.entity.User;
import com.travel.agent.service.AuthService;
import com.travel.agent.service.ItineraryService;
import com.travel.agent.service.TripMapService;
import com.travel.agent.service.TripService;
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

    @GetMapping
    public ApiResponse<List<TripDtos.TripResponse>> list(Authentication authentication) {
        User user = authService.requireUser(authentication.getName());
        return ApiResponse.ok(tripService.list(user.getId()));
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

    @GetMapping("/{id}/itinerary")
    public ApiResponse<List<ItineraryDtos.ItineraryItemDTO>> itinerary(@PathVariable Long id, Authentication authentication) {
        User user = authService.requireUser(authentication.getName());
        tripService.getOwned(id, user.getId());
        return ApiResponse.ok(itineraryService.listByTrip(id));
    }

    @PostMapping("/{id}/itinerary")
    public ApiResponse<ItineraryDtos.ItineraryItemDTO> addItem(@PathVariable Long id,
                                                               @Valid @RequestBody ItineraryDtos.AddItemRequest request,
                                                               Authentication authentication) {
        User user = authService.requireUser(authentication.getName());
        Trip trip = tripService.getOwned(id, user.getId());
        return ApiResponse.ok(ItineraryDtos.ItineraryItemDTO.from(itineraryService.addItem(trip, request)));
    }
}
