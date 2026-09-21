package com.travel.agent.controller;

import com.travel.agent.dto.ApiResponse;
import com.travel.agent.entity.ScheduleItem;
import com.travel.agent.entity.User;
import com.travel.agent.service.AuthService;
import com.travel.agent.service.ScheduleService;
import com.travel.agent.util.Dates;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
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

    public record AddScheduleRequest(
            @NotBlank(message = "不能为空")
            @Size(max = 200, message = "长度不能超过 200 个字符") String title,
            @Pattern(regexp = "^\\d{4}-\\d{2}-\\d{2}$", message = "格式需为 yyyy-MM-dd") String date,
            @Pattern(regexp = "^\\d{2}:\\d{2}(:\\d{2})?$", message = "格式需为 HH:mm") String time,
            @Size(max = 500, message = "长度不能超过 500 个字符") String note) {}

    @GetMapping
    public ApiResponse<List<ScheduleItem>> list(Authentication authentication) {
        User user = authService.requireUser(authentication.getName());
        return ApiResponse.ok(scheduleService.list(user.getId()));
    }

    @PostMapping
    public ApiResponse<ScheduleItem> add(@Valid @RequestBody AddScheduleRequest request, Authentication authentication) {
        User user = authService.requireUser(authentication.getName());
        LocalDate date = request.date() == null || request.date().isBlank()
                ? LocalDate.now() : Dates.parseDate(request.date(), "日程日期");
        java.time.LocalTime time = Dates.parseTime(request.time(), "日程时间");
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
