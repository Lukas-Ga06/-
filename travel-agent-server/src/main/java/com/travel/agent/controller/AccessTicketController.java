package com.travel.agent.controller;

import com.travel.agent.dto.ApiResponse;
import com.travel.agent.exception.BusinessException;
import com.travel.agent.security.AccessTicketService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 短期只读票据签发入口。需要登录态（Cookie 或 Authorization 头），
 * 票据本身只能用于 SSE 与照片原图这两类无法带请求头的场景。
 *
 * <p>用途由 {@code purpose} 指定（可选，默认 {@code agent-stream}）：
 * <ul>
 *   <li>{@code agent-stream} → {@code GET /api/agent/stream}</li>
 *   <li>{@code media-read} → {@code GET /api/photos/{id}/raw}</li>
 * </ul>
 * 用途与路径不匹配的票据一律拒绝，票据之间不能互相挪用。
 */
@RestController
@RequiredArgsConstructor
public class AccessTicketController {

    private final AccessTicketService accessTicketService;

    @PostMapping("/api/access-ticket")
    public ApiResponse<AccessTicketService.Ticket> issue(
            @RequestParam(value = "purpose", required = false) String purpose,
            Authentication authentication) {
        String aud = AccessTicketService.normalizePurpose(purpose);
        if (aud == null) {
            throw new BusinessException(400, "purpose 不合法，仅支持 media-read 或 agent-stream");
        }
        return ApiResponse.ok(accessTicketService.issue(authentication.getName(), aud));
    }
}