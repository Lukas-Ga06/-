package com.travel.agent.controller;

import com.travel.agent.dto.ApiResponse;
import com.travel.agent.entity.DailyReport;
import com.travel.agent.entity.User;
import com.travel.agent.service.AuthService;
import com.travel.agent.service.DailyReportService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;

/** 日报生成与查询 */
@RestController
@RequestMapping("/api/office")
@RequiredArgsConstructor
public class ReportController {

    private final DailyReportService dailyReportService;
    private final AuthService authService;

    @PostMapping("/report/generate")
    public ApiResponse<DailyReport> generate(Authentication authentication) {
        User user = authService.requireUser(authentication.getName());
        return ApiResponse.ok(dailyReportService.generate(user.getId(), LocalDate.now()));
    }

    @GetMapping("/report")
    public ApiResponse<List<DailyReport>> reportList(Authentication authentication) {
        User user = authService.requireUser(authentication.getName());
        return ApiResponse.ok(dailyReportService.list(user.getId()));
    }
}
