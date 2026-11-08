package com.travel.agent.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.travel.agent.agent.AgentContext;
import com.travel.agent.agent.AgentEngine;
import com.travel.agent.dto.ApiResponse;
import com.travel.agent.dto.AgentDtos;
import com.travel.agent.dto.ChatDtos;
import com.travel.agent.entity.User;
import com.travel.agent.service.AuthService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.RejectedExecutionException;

@RestController
@RequestMapping("/api/agent")
@RequiredArgsConstructor
@Slf4j
public class AgentController {

    private final AgentEngine agentEngine;
    private final AuthService authService;
    private final ObjectMapper objectMapper;
    private final com.travel.agent.service.ConversationService conversationService;
    /** Agent 专用线程池：避免阻塞调用挤占 ForkJoin commonPool */
    private final ThreadPoolTaskExecutor agentTaskExecutor;

    /** 同步对话：POST /api/agent/chat */
    @PostMapping("/chat")
    public ApiResponse<AgentDtos.AgentResult> chat(@Valid @RequestBody ChatDtos.ChatRequest request,
                                                   Authentication authentication) {
        User user = authService.requireUser(authentication.getName());
        AgentContext ctx = buildContext(user, request);
        return ApiResponse.ok(agentEngine.handle(ctx));
    }

    /**
     * SSE 流式输出：GET /api/agent/stream
     * EventSource 无法带 Header，token 通过查询参数传递（JwtAuthenticationFilter 已支持）。
     */
    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@RequestParam("message") String message,
                             @RequestParam(value = "tripId", required = false) Long tripId,
                             @RequestParam(value = "currentDay", required = false) Integer currentDay,
                             @RequestParam(value = "conversationId", required = false) String conversationId,
                             @RequestParam(value = "selType", required = false) String selType,
                             @RequestParam(value = "selId", required = false) Long selId,
                             @RequestParam(value = "selTitle", required = false) String selTitle,
                             @RequestParam(value = "lat", required = false) Double lat,
                             @RequestParam(value = "lng", required = false) Double lng,
                             Authentication authentication) {
        User user = authService.requireUser(authentication.getName());
        ChatDtos.ChatRequest request = new ChatDtos.ChatRequest(
                message, tripId, currentDay,
                selType == null ? null : new ChatDtos.SelectedElement(selType, selId, selTitle),
                (lat == null || lng == null) ? null : new ChatDtos.MapLocation(lat, lng),
                sanitizeConversationId(conversationId));
        return doStream(user, request);
    }

    /**
     * POST 版 SSE 流：结构化规划表单（含参考攻略正文）的入口。
     * 需求文本可长达 2 万字符，走请求体而不是查询参数；浏览器 fetch 自带 Cookie 认证。
     */
    @PostMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamPost(@Valid @RequestBody ChatDtos.PlanStreamRequest body,
                                 Authentication authentication) {
        User user = authService.requireUser(authentication.getName());
        ChatDtos.ChatRequest request = new ChatDtos.ChatRequest(
                body.getMessage(), body.getTripId(), body.getCurrentDay(),
                body.getSelectedElement(), body.getMapCenter(), body.getConversationId());
        return doStream(user, request);
    }

    /** GET/POST 两个 stream 端点共用的执行与事件推送逻辑 */
    private SseEmitter doStream(User user, ChatDtos.ChatRequest request) {
        AgentContext ctx = buildContext(user, request);

        SseEmitter emitter = new SseEmitter(180_000L);
        ctx.addListener(event -> {
            try {
                emitter.send(SseEmitter.event().name(event.event()).data(event.data()));
            } catch (Exception e) {
                log.warn("SSE 事件发送失败: {}", e.getMessage());
            }
        });
        try {
            CompletableFuture.runAsync(() -> {
                try {
                    agentEngine.handle(ctx);
                    emitter.complete();
                } catch (Exception e) {
                    log.error("SSE 处理异常", e);
                    emitter.completeWithError(e);
                }
            }, agentTaskExecutor);
        } catch (RejectedExecutionException e) {
            // 线程池已满：明确告诉用户，而不是让连接一直挂着
            log.warn("Agent 线程池已满，拒绝请求: user={}", user.getUsername());
            ctx.emit("error", java.util.Map.of("message", "服务器繁忙，请稍后重试"));
            emitter.complete();
        }
        return emitter;
    }

    /** 确认提交（Human-in-the-loop） */
    @PostMapping("/confirm")
    public ApiResponse<AgentDtos.AgentResult> confirm(@Valid @RequestBody ChatDtos.ConfirmRequest request,
                                                      Authentication authentication) {
        User user = authService.requireUser(authentication.getName());
        return ApiResponse.ok(agentEngine.confirm(request.getPlanId(), user.getId()));
    }

    @PostMapping("/cancel")
    public ApiResponse<AgentDtos.AgentResult> cancel(@Valid @RequestBody ChatDtos.ConfirmRequest request,
                                                     Authentication authentication) {
        User user = authService.requireUser(authentication.getName());
        return ApiResponse.ok(agentEngine.cancel(request.getPlanId(), user.getId()));
    }

    @PostMapping("/undo")
    public ApiResponse<AgentDtos.AgentResult> undo(@Valid @RequestBody ChatDtos.UndoRequest request,
                                                   Authentication authentication) {
        User user = authService.requireUser(authentication.getName());
        return ApiResponse.ok(agentEngine.undo(request.getTripId(), user.getId()));
    }

    /** 取回待确认方案：前端刷新后据此恢复确认弹窗 */
    @GetMapping("/plans/{planId}")
    public ApiResponse<AgentDtos.AgentResult> pending(@PathVariable String planId, Authentication authentication) {
        User user = authService.requireUser(authentication.getName());
        return ApiResponse.ok(agentEngine.pendingPreview(planId, user.getId()));
    }

    /** 开新会话：清掉当前用户在该会话下的历史消息 */
    @PostMapping("/conversation/clear")
    public ApiResponse<Void> clearConversation(@Valid @RequestBody ChatDtos.ClearConversationRequest request,
                                               Authentication authentication) {
        User user = authService.requireUser(authentication.getName());
        conversationService.clear(user.getId(), request.getConversationId());
        return ApiResponse.ok(null);
    }

    private AgentContext buildContext(User user, ChatDtos.ChatRequest request) {
        AgentContext ctx = new AgentContext();
        ctx.setUserId(user.getId());
        ctx.setUsername(user.getUsername());
        ctx.setTripId(request.getTripId());
        ctx.setSessionId(UUID.randomUUID().toString());
        ctx.setConversationId(request.getConversationId());
        ctx.setMessage(request.getMessage());
        ctx.setCurrentDay(request.getCurrentDay());
        ctx.setSelectedElement(request.getSelectedElement());
        ctx.setMapCenter(request.getMapCenter());
        return ctx;
    }

    /** SSE 查询参数不经过 Bean Validation，这里做与 DTO 一致的清洗（超长/非法字符直接视为单轮） */
    private static String sanitizeConversationId(String conversationId) {
        if (conversationId == null) {
            return null;
        }
        String trimmed = conversationId.trim();
        if (trimmed.isEmpty() || trimmed.length() > 64 || !trimmed.matches("[A-Za-z0-9_-]*")) {
            return null;
        }
        return trimmed;
    }
}
