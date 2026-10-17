package com.travel.agent.controller;

import com.travel.agent.dto.ApiResponse;
import com.travel.agent.dto.ReminderDtos;
import com.travel.agent.entity.User;
import com.travel.agent.service.AuthService;
import com.travel.agent.service.ReminderService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

/** 提醒板：日程 / 出发提醒（实时计算，前端轮询） */
@RestController
@RequestMapping("/api/reminders")
@RequiredArgsConstructor
public class ReminderController {

    private final ReminderService reminderService;
    private final AuthService authService;

    @GetMapping
    public ApiResponse<ReminderDtos.ReminderBoard> board(Authentication authentication) {
        User user = authService.requireUser(authentication.getName());
        return ApiResponse.ok(reminderService.board(user.getId()));
    }

    @PostMapping("/schedule/{id}/dismiss")
    public ApiResponse<Void> dismissSchedule(@PathVariable Long id, Authentication authentication) {
        User user = authService.requireUser(authentication.getName());
        reminderService.dismissSchedule(user.getId(), id);
        return ApiResponse.ok(null);
    }

    @PostMapping("/trip/{id}/dismiss")
    public ApiResponse<Void> dismissTrip(@PathVariable Long id, Authentication authentication) {
        User user = authService.requireUser(authentication.getName());
        reminderService.dismissTrip(user.getId(), id);
        return ApiResponse.ok(null);
    }
}
