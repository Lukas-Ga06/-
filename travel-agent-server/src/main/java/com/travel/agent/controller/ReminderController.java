package com.travel.agent.controller;

import com.travel.agent.dto.ApiResponse;
import com.travel.agent.dto.ReminderDtos;
import com.travel.agent.entity.User;
import com.travel.agent.service.AuthService;
import com.travel.agent.service.ReminderService;
import com.travel.agent.util.Paging;
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
    public ApiResponse<ReminderDtos.ReminderBoard> board(
            @RequestParam(defaultValue = "" + Paging.DEFAULT_PAGE_LIMIT) int limit,
            @RequestParam(defaultValue = "0") int offset,
            Authentication authentication) {
        User user = authService.requireUser(authentication.getName());
        int size = Paging.normalizeLimit(limit);
        int from = Paging.normalizeOffset(offset);
        ReminderDtos.ReminderBoard board = reminderService.board(user.getId(), from, size);
        // data 仍是 {items,count,serverTime}（前端按该结构渲染），分页信息放在同级 pagination；
        // count 是提醒总数，items 只是当前页。
        return ApiResponse.ok(board, Paging.meta(board.getCount(), size, from));
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
