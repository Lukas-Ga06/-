package com.travel.agent.controller;

import com.travel.agent.dto.ApiResponse;
import com.travel.agent.security.AccessTicketService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 短期只读票据签发入口。需要登录态（Authorization 头），
 * 票据本身只用于 SSE 与照片原图这类无法带请求头的场景。
 */
@RestController
@RequiredArgsConstructor
public class AccessTicketController {

    private final AccessTicketService accessTicketService;

    @PostMapping("/api/access-ticket")
    public ApiResponse<AccessTicketService.Ticket> issue(Authentication authentication) {
        return ApiResponse.ok(accessTicketService.issue(authentication.getName()));
    }
}
