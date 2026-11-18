package com.travel.agent.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.travel.agent.agent.AgentContext;
import com.travel.agent.agent.AgentEngine;
import com.travel.agent.dto.ApiResponse;
import com.travel.agent.dto.AgentDtos;
import com.travel.agent.dto.ChatDtos;
import com.travel.agent.entity.User;
import com.travel.agent.security.AuthAttributes;
import com.travel.agent.service.AuthService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import io.micrometer.core.instrument.MeterRegistry;
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
import java.util.concurrent.atomic.AtomicBoolean;

@RestController
@RequestMapping("/api/agent")
@RequiredArgsConstructor
@Slf4j
public class AgentController {

    /** SSE 断开计数：reason=complete（半途断开）|timeout|error；正常结束不计 */
    static final String SSE_DISCONNECTS_METRIC = "sse.disconnects";

    private final AgentEngine agentEngine;
    private final AuthService authService;
    private final ObjectMapper objectMapper;
    private final com.travel.agent.service.ConversationService conversationService;
    /** Agent 专用线程池：避免阻塞调用挤占 ForkJoin commonPool */
    private final ThreadPoolTaskExecutor agentTaskExecutor;
    /** 指标：sse.disconnects（注入方式与 LLM/规划器一致，不引新依赖） */
    private final MeterRegistry meterRegistry;
    /** V2.2 运行追踪查询（FR-10） */
    private final com.travel.agent.service.AgentTraceService traceService;
    /** V2.2 确认后同步台账（FR-09） */
    private final com.travel.agent.service.PlanExecutionService executionService;

    /** 同步对话：POST /api/agent/chat */
    @PostMapping("/chat")
    public ApiResponse<AgentDtos.AgentResult> chat(@Valid @RequestBody ChatDtos.ChatRequest request,
                                                   Authentication authentication) {
        User user = authService.requireUser(authentication.getName());
        // 同步接口不做票据认证，恒为普通会话
        AgentContext ctx = buildContext(user, request, false);
        return ApiResponse.ok(agentEngine.handle(ctx));
    }

    /**
     * SSE 流式输出：GET /api/agent/stream
     *
     * <p>EventSource 无法自定义请求头，因此浏览器主路径靠 httpOnly Cookie 认证；
     * 只在"连 Cookie 都没有"的场景（跨域 iframe、第三方客户端）才用 {@code ?ticket=} 兜底，
     * 而仅凭票据认证的请求是<b>只读会话</b>（见 {@link AuthAttributes#TICKET_ONLY}）。
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
                             HttpServletRequest httpRequest,
                             HttpServletResponse httpResponse,
                             Authentication authentication) {
        User user = authService.requireUser(authentication.getName());
        noReferrer(httpResponse);
        ChatDtos.ChatRequest request = new ChatDtos.ChatRequest(
                message, tripId, currentDay,
                selType == null ? null : new ChatDtos.SelectedElement(selType, selId, selTitle),
                (lat == null || lng == null) ? null : new ChatDtos.MapLocation(lat, lng),
                sanitizeConversationId(conversationId));
        return doStream(user, request, ticketOnly(httpRequest));
    }

    /**
     * POST 版 SSE 流：结构化规划表单（含参考攻略正文）的入口。
     * 需求文本可长达 2 万字符，走请求体而不是查询参数；浏览器 fetch 自带 Cookie 认证。
     * POST 不接受查询参数票据（{@link com.travel.agent.security.JwtAuthenticationFilter} 只认 GET），
     * 因此这里读到的 {@code ticketOnly} 恒为 false；保留读取是为了两个入口共用同一套语义。
     */
    @PostMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamPost(@Valid @RequestBody ChatDtos.PlanStreamRequest body,
                                 HttpServletRequest httpRequest,
                                 HttpServletResponse httpResponse,
                                 Authentication authentication) {
        User user = authService.requireUser(authentication.getName());
        noReferrer(httpResponse);
        ChatDtos.ChatRequest request = new ChatDtos.ChatRequest(
                body.getMessage(), body.getTripId(), body.getCurrentDay(),
                body.getSelectedElement(), body.getMapCenter(), body.getConversationId());
        return doStream(user, request, ticketOnly(httpRequest));
    }

    /** GET/POST 两个 stream 端点共用的执行与事件推送逻辑 */
    private SseEmitter doStream(User user, ChatDtos.ChatRequest request, boolean ticketOnly) {
        AgentContext ctx = buildContext(user, request, ticketOnly);

        SseEmitter emitter = new SseEmitter(180_000L);
        // 断连生命周期：onCompletion 在"正常结束"和"客户端半途断开"时都会触发，
        // 用 finished 标记区分两者（见 StreamLifecycle），正常完成绝不误记成断连。
        StreamLifecycle lifecycle = new StreamLifecycle(ctx, meterRegistry);
        emitter.onCompletion(lifecycle::onCompletion);
        emitter.onTimeout(lifecycle::onTimeout);
        emitter.onError(lifecycle::onError);
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
                } catch (Exception e) {
                    log.error("SSE 处理异常", e);
                    // 引擎异常不是客户端断连：先置位再收尾，避免 onError 里重复计一次断连
                    lifecycle.markFinished();
                    emitter.completeWithError(e);
                    return;
                }
                lifecycle.markFinished();
                try {
                    emitter.complete();
                } catch (Exception e) {
                    // 客户端已先断开时 complete() 会抛 IllegalStateException：收尾噪音，不算失败
                    log.debug("SSE 收尾时连接已结束: {}", e.getMessage());
                }
            }, agentTaskExecutor);
        } catch (RejectedExecutionException e) {
            // 线程池已满：明确告诉用户，而不是让连接一直挂着
            log.warn("Agent 线程池已满，拒绝请求: user={}", user.getUsername());
            ctx.emit("error", java.util.Map.of("message", "服务器繁忙，请稍后重试"));
            lifecycle.markFinished();
            emitter.complete();
        }
        return emitter;
    }

    /**
     * 一次 SSE 连接的生命周期：区分"正常收尾"与"客户端断开"，断开时计数并取消 Agent 任务。
     *
     * <p>为什么需要 {@code finished}：SseEmitter 的 onCompletion 在引擎正常结束
     * （我们主动 {@code emitter.complete()}）时同样触发，无法从回调本身区分。
     * 因此工作线程在结束时先置位，回调里"未置位"才当作客户端半途断开。
     *
     * <p>包可见是给单测用的：SseEmitter 的回调只能由 Servlet 容器触发，
     * 单测直接驱动本类方法验证计数/取消语义。
     */
    static final class StreamLifecycle {

        private final AgentContext ctx;
        private final MeterRegistry meterRegistry;
        /** 引擎是否已经正常/异常收尾（此时任何回调都不算客户端断连） */
        private final AtomicBoolean finished = new AtomicBoolean(false);
        /** 一次连接只记一次断连：容器可能先后触发多个回调 */
        private final AtomicBoolean counted = new AtomicBoolean(false);

        StreamLifecycle(AgentContext ctx, MeterRegistry meterRegistry) {
            this.ctx = ctx;
            this.meterRegistry = meterRegistry;
        }

        /** 引擎已收尾（正常返回或抛异常）：之后的 onCompletion 不算客户端断开 */
        void markFinished() {
            finished.set(true);
        }

        boolean isFinished() {
            return finished.get();
        }

        void onCompletion() {
            disconnect("complete");
        }

        void onTimeout() {
            disconnect("timeout");
        }

        void onError(Throwable error) {
            if (error != null) {
                log.warn("SSE 连接异常: {}", error.getMessage());
            }
            disconnect("error");
        }

        /**
         * 客户端断开：计数 + 置取消标记，让 AgentEngine 在下一个阶段边界停止烧 LLM 配额。
         * 已经是正常收尾（finished）或本次连接已计过数时直接返回。
         */
        private void disconnect(String reason) {
            if (finished.get()) {
                return;
            }
            if (!counted.compareAndSet(false, true)) {
                return;
            }
            meterRegistry.counter(SSE_DISCONNECTS_METRIC, "reason", reason).increment();
            ctx.cancel();
        }
    }

    /** 确认提交（Human-in-the-loop）：可携带内容指纹（FR-08）校验「确认的就是看到的」 */
    @PostMapping("/confirm")
    public ApiResponse<AgentDtos.AgentResult> confirm(@Valid @RequestBody ChatDtos.ConfirmRequest request,
                                                      Authentication authentication) {
        User user = authService.requireUser(authentication.getName());
        return ApiResponse.ok(agentEngine.confirm(request.getPlanId(), user.getId(), request.getContentHash()));
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

    // ==================== V2.2 运行追踪（FR-10） ====================

    /** 当前用户最近的 Agent 运行（新→旧） */
    @GetMapping("/runs")
    public ApiResponse<java.util.List<com.travel.agent.dto.AgentTraceDtos.RunSummary>> runs(
            @RequestParam(value = "limit", defaultValue = "20") int limit,
            Authentication authentication) {
        User user = authService.requireUser(authentication.getName());
        return ApiResponse.ok(traceService.recentRuns(user.getId(), limit).stream()
                .map(com.travel.agent.dto.AgentTraceDtos::from)
                .toList());
    }

    /** 一次运行的完整追踪：任务链 + 工具调用（归属校验，他人的 run 一律 404） */
    @GetMapping("/runs/{runId}")
    public ApiResponse<com.travel.agent.dto.AgentTraceDtos.RunDetail> runDetail(
            @PathVariable String runId, Authentication authentication) {
        User user = authService.requireUser(authentication.getName());
        com.travel.agent.entity.AgentRun run = traceService.findRun(runId, user.getId())
                .orElseThrow(() -> new com.travel.agent.exception.BusinessException(404, "运行记录不存在"));
        return ApiResponse.ok(com.travel.agent.dto.AgentTraceDtos.RunDetail.builder()
                .run(com.travel.agent.dto.AgentTraceDtos.from(run))
                .tasks(traceService.tasksOf(runId).stream()
                        .map(com.travel.agent.dto.AgentTraceDtos::from).toList())
                .toolCalls(traceService.toolCallsOf(runId).stream()
                        .map(com.travel.agent.dto.AgentTraceDtos::from).toList())
                .build());
    }

    // ==================== V2.2 确认后同步台账（FR-09） ====================

    /** 查询一次确认提交的跨模块同步结果（日程/预算/足迹，归属校验） */
    @GetMapping("/executions/{executionId}")
    public ApiResponse<AgentDtos.ExecutionSummary> executionDetail(
            @PathVariable String executionId, Authentication authentication) {
        User user = authService.requireUser(authentication.getName());
        return ApiResponse.ok(executionService.find(executionId, user.getId()));
    }

    /** 重试失败的同步模块（幂等：成功模块不重复创建） */
    @PostMapping("/executions/{executionId}/retry")
    public ApiResponse<AgentDtos.ExecutionSummary> executionRetry(
            @PathVariable String executionId, Authentication authentication) {
        User user = authService.requireUser(authentication.getName());
        return ApiResponse.ok(executionService.retry(executionId, user.getId()));
    }

    /** 开新会话：清掉当前用户在该会话下的历史消息 */
    @PostMapping("/conversation/clear")
    public ApiResponse<Void> clearConversation(@Valid @RequestBody ChatDtos.ClearConversationRequest request,
                                               Authentication authentication) {
        User user = authService.requireUser(authentication.getName());
        conversationService.clear(user.getId(), request.getConversationId());
        return ApiResponse.ok(null);
    }

    private AgentContext buildContext(User user, ChatDtos.ChatRequest request, boolean ticketOnly) {
        AgentContext ctx = new AgentContext();
        ctx.setUserId(user.getId());
        ctx.setUsername(user.getUsername());
        ctx.setTicketOnly(ticketOnly);
        ctx.setTripId(request.getTripId());
        ctx.setSessionId(UUID.randomUUID().toString());
        ctx.setConversationId(request.getConversationId());
        ctx.setMessage(request.getMessage());
        ctx.setCurrentDay(request.getCurrentDay());
        ctx.setSelectedElement(request.getSelectedElement());
        ctx.setMapCenter(request.getMapCenter());
        return ctx;
    }

    /**
     * SSE 响应不带 Referer：凭证可能出现在查询串里（{@code ?ticket=}），
     * 若页面再跳去第三方链接，URL 会经 Referer 头二次泄漏。
     */
    private static void noReferrer(HttpServletResponse response) {
        response.setHeader("Referrer-Policy", "no-referrer");
    }

    /** 本次请求是否"仅凭查询参数票据"认证（由 JwtAuthenticationFilter 写入请求属性） */
    private static boolean ticketOnly(HttpServletRequest request) {
        return Boolean.TRUE.equals(request.getAttribute(AuthAttributes.TICKET_ONLY));
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
