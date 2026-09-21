package com.travel.agent.controller;

import com.travel.agent.dto.ApiResponse;
import com.travel.agent.entity.ScheduleItem;
import com.travel.agent.entity.User;
import com.travel.agent.service.AuthService;
import com.travel.agent.service.ScheduleService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;

@RestController
@RequestMapping("/api/schedule")
@RequiredArgsConstructor
public class ScheduleController {

    private final ScheduleService scheduleService;
    private final AuthService authService;

    public record AddScheduleRequest(String title, String date, String time, String note) {}

    @GetMapping
    public ApiResponse<List<ScheduleItem>> list(Authentication authentication) {
        User user = authService.requireUser(authentication.getName());
        return ApiResponse.ok(scheduleService.list(user.getId()));
    }

    @PostMapping
    public ApiResponse<ScheduleItem> add(@RequestBody AddScheduleRequest request, Authentication authentication) {
        User user = authService.requireUser(authentication.getName());
        LocalDate date = request.date() == null || request.date().isBlank()
                ? LocalDate.now() : LocalDate.parse(request.date());
        java.time.LocalTime time = request.time() == null || request.time().isBlank()
                ? null : java.time.LocalTime.parse(request.time());
        return ApiResponse.ok(scheduleService.add(user.getId(), request.title(), date, time, request.note()));
    }

    @PostMapping("/{id}/toggle")
    public ApiResponse<ScheduleItem> toggle(@PathVariable Long id, Authentication authentication) {
        User user = authService.requireUser(authentication.getName());
        return ApiResponse.ok(scheduleService.toggle(user.getId(), id));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable Long id, Authentication authentication) {
        User user = authService.requireUser(authentication.getName());
        scheduleService.delete(user.getId(), id);
        return ApiResponse.ok(null);
    }
}
