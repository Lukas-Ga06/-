package com.travel.agent.controller;

import com.travel.agent.dto.ApiResponse;
import com.travel.agent.entity.HealthRecord;
import com.travel.agent.entity.User;
import com.travel.agent.service.AuthService;
import com.travel.agent.service.HealthService;
import com.travel.agent.service.LlmAssistantService;
import com.travel.agent.util.Dates;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/** 健康数据监测（小米手环等设备数据导入/手动记录） */
@RestController
@RequestMapping("/api/health")
@RequiredArgsConstructor
public class HealthController {

    private final HealthService healthService;
    private final AuthService authService;
    private final LlmAssistantService llmAssistantService;

    public record HealthRecordRequest(
            @Pattern(regexp = "^\\d{4}-\\d{2}-\\d{2}$", message = "格式需为 yyyy-MM-dd") String date,
            @Min(value = 0, message = "不能为负数") Integer steps,
            @DecimalMin(value = "0", message = "不能为负数") Double sleepHours,
            @Min(value = 0, message = "不能为负数") Integer heartRate,
            @DecimalMin(value = "0", message = "不能为负数") Double weight) {}

    @PostMapping("/record")
    public ApiResponse<HealthRecord> upsert(@Valid @RequestBody HealthRecordRequest request, Authentication authentication) {
        User user = authService.requireUser(authentication.getName());
        LocalDate date = request.date() == null || request.date().isBlank()
                ? LocalDate.now() : Dates.parseDate(request.date(), "记录日期");
        return ApiResponse.ok(healthService.upsert(user.getId(), date,
                request.steps(), request.sleepHours(), request.heartRate(), request.weight()));
    }

    @PostMapping("/seed-demo")
    public ApiResponse<Map<String, Integer>> seedDemo(Authentication authentication) {
        User user = authService.requireUser(authentication.getName());
        int created = healthService.seedDemo(user.getId());
        return ApiResponse.ok(Map.of("created", created));
    }

    @GetMapping("/records")
    public ApiResponse<List<HealthRecord>> records(Authentication authentication) {
        User user = authService.requireUser(authentication.getName());
        return ApiResponse.ok(healthService.last7Days(user.getId()));
    }

    @GetMapping("/summary")
    public ApiResponse<Map<String, Object>> summary(Authentication authentication) {
        User user = authService.requireUser(authentication.getName());
        HealthRecord latest = healthService.latest(user.getId());
        double[] avg = healthService.weeklyAverages(user.getId());
        String advice = llmAssistantService.healthAdvice(latest, avg[0], avg[1]);
        return ApiResponse.ok(Map.of(
                "latest", latest == null ? Map.of() : latest,
                "avgSteps", (int) avg[0],
                "avgSleep", Math.round(avg[1] * 10) / 10.0,
                "days", (int) avg[2],
                "advice", advice));
    }
}
