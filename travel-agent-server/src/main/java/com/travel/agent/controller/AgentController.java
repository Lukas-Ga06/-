package com.travel.agent.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.travel.agent.agent.AgentBudget;
import com.travel.agent.agent.AgentContext;
import com.travel.agent.agent.AgentEngine;
import com.travel.agent.config.AgentBudgetProperties;
import com.travel.agent.dto.ApiResponse;
import com.travel.agent.dto.AgentDtos;
import com.travel.agent.dto.ChatDtos;
import com.travel.agent.entity.AgentRun;
import com.travel.agent.entity.AgentRunEvent;
import com.travel.agent.entity.User;
import com.travel.agent.exception.BusinessException;
import com.travel.agent.llm.LlmProperties;
import com.travel.agent.security.AuthAttributes;
import com.travel.agent.service.AgentRunEventService;
import com.travel.agent.service.AuthService;
import com.travel.agent.util.Paging;
import io.swagger.v3.oas.annotations.Hidden;
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

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
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

    /** 浏览器 EventSource 重连时自动带上的「最后收到的事件 id」，格式 {@code <runId>:<seq>} */
    static final String LAST_EVENT_ID_HEADER = "Last-Event-ID";

    /** 续传轮询间隔：运行中每 500ms 查一次新事件（有界批次，不阻塞容器线程） */
    static final long RESUME_POLL_INTERVAL_MS = 500L;
    /** 单条续传连接的最长等待：运行超过 120s 仍未终态就发 resume_timeout 后关闭 */
    static final long RESUME_MAX_DURATION_MS = 120_000L;
    /** 续传 SseEmitter 的容器超时：比业务上限多留 30s 收尾余量 */
    static final long RESUME_EMITTER_TIMEOUT_MS = RESUME_MAX_DURATION_MS + 30_000L;
    /** 单批补发上限 */
    static final int RESUME_BATCH_SIZE = 200;

    /** runId 的字符集与列宽一致（UUID + 允许前缀是字母数字/下划线/连字符） */
    private static final Pattern RUN_ID_PATTERN = Pattern.compile("[A-Za-z0-9_-]{1,64}");

    /** 助手页作用域（V21） */
    static final String SCOPE_ASSISTANT = "assistant";
    /** 行程页作用域前缀：完整值形如 {@code trip:12} */
    static final String SCOPE_TRIP = "trip:";
    /** 与 conversation_message.scope 列宽一致 */
    static final int SCOPE_MAX_LENGTH = 32;
    private static final Pattern SCOPE_PATTERN = Pattern.compile("[A-Za-z0-9:_-]+");

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
    /** P0：SSE 事件持久化（续传补发按 seq 升序、不重复） */
    private final AgentRunEventService runEventService;
    /** V2.2 确认后同步台账（FR-09） */
    private final com.travel.agent.service.PlanExecutionService executionService;
    /** P1 端到端时间预算配置（默认开启，30s；关闭后行为与升级前一致） */
    private final AgentBudgetProperties budgetProperties;
    /** 单次 LLM 读超时：预算开启时作为每次调用的读超时上限来源 */
    private final LlmProperties llmProperties;

    /** 同步对话：POST /api/agent/chat */
    @PostMapping("/chat")
    public ApiResponse<AgentDtos.AgentResult> chat(@Valid @RequestBody ChatDtos.ChatRequest request,
                                                   Authentication authentication) {
        User user = authService.requireUser(authentication.getName());
        // 同步接口不做票据认证，恒为普通会话
        AgentContext ctx = buildContext(user, request, false);
        // P1：同步接口必须在预算内返回——预算窗口在请求入口创建，随上下文进入引擎的阶段边界检查
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
                             @RequestParam(value = "requestId", required = false) String requestId,
                             @RequestParam(value = "scope", required = false) String scope,
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
        // P0：浏览器 EventSource 重连会带 Last-Event-ID —— 续传原 run，绝不重新规划
        ResumePoint resume = parseResumePoint(httpRequest.getHeader(LAST_EVENT_ID_HEADER));
        if (resume != null) {
            return resumeStream(user, resume.runId(), resume.lastEventId(), httpResponse);
        }
        ChatDtos.ChatRequest request = new ChatDtos.ChatRequest(
                message, tripId, currentDay,
                selType == null ? null : new ChatDtos.SelectedElement(selType, selId, selTitle),
                (lat == null || lng == null) ? null : new ChatDtos.MapLocation(lat, lng),
                sanitizeConversationId(conversationId),
                sanitizeRequestId(requestId),
                sanitizeScope(scope));
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
        // P0：fetch 传输若带 Last-Event-ID（断线后重连），同样走续传而不是重新规划
        ResumePoint resume = parseResumePoint(httpRequest.getHeader(LAST_EVENT_ID_HEADER));
        if (resume != null) {
            return resumeStream(user, resume.runId(), resume.lastEventId(), httpResponse);
        }
        ChatDtos.ChatRequest request = new ChatDtos.ChatRequest(
                body.getMessage(), body.getTripId(), body.getCurrentDay(),
                body.getSelectedElement(), body.getMapCenter(), body.getConversationId(),
                sanitizeRequestId(body.getRequestId()), sanitizeScope(body.getScope()));
        return doStream(user, request, ticketOnly(httpRequest));
    }

    /** GET/POST 两个 stream 端点共用的执行与事件推送逻辑 */
    private SseEmitter doStream(User user, ChatDtos.ChatRequest request, boolean ticketOnly) {
        AgentContext ctx = buildContext(user, request, ticketOnly);

        // P1：SSE 的收尾超时跟着预算走。引擎会在预算内正常收尾并发出 complete 事件；
        // 这里的超时只兜底「引擎卡死」的极端情况，取 预算+30s 且不低于 60s（不能比预算短，
        // 否则慢规划还在预算内就被容器掐断连接，反而制造「悬空/半截」体验）。
        SseEmitter emitter = new SseEmitter(emitterTimeoutMs(ctx.getBudget()));
        // 断连生命周期：onCompletion 在"正常结束"和"客户端半途断开"时都会触发，
        // 用 finished 标记区分两者（见 StreamLifecycle），正常完成绝不误记成断连。
        StreamLifecycle lifecycle = new StreamLifecycle(ctx, meterRegistry);
        emitter.onCompletion(lifecycle::onCompletion);
        emitter.onTimeout(lifecycle::onTimeout);
        emitter.onError(lifecycle::onError);
        ctx.addListener(event -> {
            try {
                SseEmitter.SseEventBuilder frame = SseEmitter.event()
                        .name(event.event())
                        .data(event.data());
                // P0：run 创建后每个事件都带 id「<runId>:<seq>」，客户端断线时凭最后一个 id 续传；
                // run 创建前的事件（旧调用方 / 极端情况）没有 id，行为与升级前一致。
                if (ctx.getRunId() != null && event.seq() > 0) {
                    frame.id(ctx.getRunId() + ":" + event.seq());
                }
                emitter.send(frame);
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
        // FR-06：审核仍有未解决 HIGH 问题的方案，必须由前端显式回传 acknowledgeIssues=true
        return ApiResponse.ok(agentEngine.confirm(request.getPlanId(), user.getId(),
                request.getContentHash(), request.getAcknowledgeIssues()));
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

    /**
     * SSE 断线续传（P0）：GET /api/agent/runs/{runId}/events?lastEventId=N。
     *
     * <p>语义：
     * <ul>
     *   <li>先补发 seq &gt; N 的持久化事件（按 seq 升序、同 seq 不重复）；</li>
     *   <li>run 已终态 → 补发后关闭；若补发集合里没有 complete/cancelled，合成一条
     *       {@code complete}（status=FAILED，说明运行已中断、请重试），绝不让客户端无限等待；</li>
     *   <li>run 仍 RUNNING → 每 {@value #RESUME_POLL_INTERVAL_MS}ms 轮询新事件，直到终态或
     *       {@value #RESUME_MAX_DURATION_MS}ms 上限；超时发 {@code resume_timeout} 后关闭。</li>
     * </ul>
     *
     * <p>鉴权：Cookie / Authorization 走常规 JWT；EventSource 场景可用 {@code ?ticket=}
     * （沿用 JwtAuthenticationFilter 的 agent-stream 票据语义）。归属校验失败一律 404，
     * 不泄露「这个 runId 是否存在」。执行体跑在 {@code agentTaskExecutor} 上，不阻塞容器线程。
     *
     * <p>对 OpenAPI 隐藏：这是 SSE 流端点，与 /api/agent/stream 一样没有可用的 JSON 契约；
     * 公开接口文档不收录，避免契约快照被流式端点污染。
     */
    @Hidden
    @GetMapping(value = "/runs/{runId}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter runEvents(@PathVariable String runId,
                                @RequestParam(value = "lastEventId", required = false) Long lastEventId,
                                HttpServletRequest httpRequest,
                                HttpServletResponse httpResponse,
                                Authentication authentication) {
        User user = authService.requireUser(authentication.getName());
        long afterSeq = lastEventId != null
                ? Math.max(lastEventId, 0L)
                : resumeSeqFromHeader(httpRequest.getHeader(LAST_EVENT_ID_HEADER));
        return resumeStream(user, runId, afterSeq, httpResponse);
    }

    /**
     * 续传一个 run 的 SSE 事件流：归属校验 → 交给 {@code agentTaskExecutor} 补发/轮询。
     *
     * <p>断连语义与实时流隔离：这里没有 AgentContext，也就不会因为客户端从续传端点断开
     * 去取消原始运行（{@link StreamLifecycle} 只挂在 /api/agent/stream 上）。
     */
    private SseEmitter resumeStream(User user, String runId, long afterSeq, HttpServletResponse httpResponse) {
        AgentRun run = traceService.findRun(runId, user.getId())
                .orElseThrow(() -> new BusinessException(404, "运行记录不存在"));
        noReferrer(httpResponse);
        SseEmitter emitter = new SseEmitter(RESUME_EMITTER_TIMEOUT_MS);
        try {
            CompletableFuture.runAsync(() -> resumeLoop(emitter, run, afterSeq), agentTaskExecutor);
        } catch (RejectedExecutionException e) {
            log.warn("Agent 线程池已满，无法续传: runId={}", runId);
            throw new BusinessException(503, "服务器繁忙，请稍后重试");
        }
        return emitter;
    }

    /**
     * 续传循环（在 agentTaskExecutor 线程上执行）：
     * 有界批次补发 → 重读 run 状态 → 终态收尾 / 继续轮询。
     *
     * <p>游标只前进不后退，因此补发集合里不会出现重复 seq；客户端按 seq 幂等。
     * 任何 IO/查询异常都只结束本连接，不影响原始运行。
     */
    private void resumeLoop(SseEmitter emitter, AgentRun initialRun, long afterSeq) {
        String runId = initialRun.getRunId();
        long cursor = Math.max(afterSeq, 0L);
        long deadline = System.currentTimeMillis() + RESUME_MAX_DURATION_MS;
        boolean terminalEventSent = false;
        try {
            while (true) {
                List<AgentRunEvent> batch = runEventService.listAfter(runId, cursor, RESUME_BATCH_SIZE);
                for (AgentRunEvent event : batch) {
                    sendPersisted(emitter, runId, event);
                    cursor = event.getSeqNo();
                    if (AgentRunEventService.isTerminalEvent(event.getEventType())) {
                        terminalEventSent = true;
                    }
                }
                // 每轮重读状态（不持有最初那份快照）：运行可能在补发期间终态/被恢复任务标记 ABANDONED
                String status = traceService.findRun(runId, initialRun.getUserId())
                        .map(AgentRun::getStatus)
                        .orElse(AgentRun.STATUS_ABANDONED);
                boolean running = AgentRun.STATUS_RUNNING.equals(status);
                if (!running) {
                    if (!batch.isEmpty()) {
                        continue; // 先把剩余事件补完再收尾
                    }
                    if (!terminalEventSent) {
                        sendSynthesizedComplete(emitter, status);
                    }
                    completeQuietly(emitter);
                    return;
                }
                if (!batch.isEmpty()) {
                    continue; // 还有新事件：立刻继续拉取，不额外等待
                }
                if (System.currentTimeMillis() >= deadline) {
                    emitter.send(SseEmitter.event().name("resume_timeout").data(Map.of(
                            "runId", runId,
                            "lastEventId", cursor,
                            "message", "续传等待超时，运行仍在进行，请稍后重试")));
                    completeQuietly(emitter);
                    return;
                }
                Thread.sleep(RESUME_POLL_INTERVAL_MS);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            completeQuietly(emitter);
        } catch (Exception e) {
            // 客户端断开 / 查询失败：静默结束本连接，原始运行不受影响
            log.debug("SSE 续传结束: runId={} err={}", runId, e.getMessage());
            completeQuietly(emitter);
        }
    }

    /** 补发一条持久化事件：id 固定为「<runId>:<seq>」，载荷 JSON 解析失败时按文本兜底 */
    private void sendPersisted(SseEmitter emitter, String runId, AgentRunEvent event) throws java.io.IOException {
        SseEmitter.SseEventBuilder frame = SseEmitter.event()
                .id(runId + ":" + event.getSeqNo())
                .name(event.getEventType());
        Object payload = readPayload(event.getDataJson());
        if (payload != null) {
            frame.data(payload);
        }
        emitter.send(frame);
    }

    /** data_json → JsonNode（保持原 JSON 结构）；损坏/截断的文本退化为 JSON 字符串，补发不中断 */
    private Object readPayload(String dataJson) {
        if (dataJson == null || dataJson.isEmpty()) {
            return null;
        }
        try {
            return objectMapper.readTree(dataJson);
        } catch (Exception e) {
            return objectMapper.getNodeFactory().textNode(dataJson);
        }
    }

    /**
     * 终态 run 的补发集合里没有 complete/cancelled 时合成一条，避免客户端永远停在「流式」。
     * 不写库、不带 id：它不是原 run 的事件，只是对「结果已经不可能再来」的明确告知。
     */
    private void sendSynthesizedComplete(SseEmitter emitter, String runStatus) throws java.io.IOException {
        emitter.send(SseEmitter.event()
                .name(AgentRunEventService.EVENT_COMPLETE)
                .data(Map.of(
                        "status", AgentDtos.STATUS_FAILED,
                        "message", "运行已中断（服务重启或超时），请重试",
                        "runStatus", runStatus == null ? AgentRun.STATUS_ABANDONED : runStatus)));
    }

    private static void completeQuietly(SseEmitter emitter) {
        try {
            emitter.complete();
        } catch (Exception e) {
            // 客户端已先断开时 complete() 会抛 IllegalStateException：收尾噪音，不算失败
            log.debug("SSE 续传收尾时连接已结束: {}", e.getMessage());
        }
    }

    /** Last-Event-ID 头 → 续传游标；头不存在返回 0，格式非法抛业务错误（绝不静默重新规划） */
    private static long resumeSeqFromHeader(String header) {
        ResumePoint point = parseResumePoint(header);
        return point == null ? 0L : point.lastEventId();
    }

    /**
     * 解析 Last-Event-ID：{@code <runId>:<seq>}。
     *
     * @return null 表示请求头不存在（新开 run 的常规路径）；格式非法抛 400 业务错误
     */
    static ResumePoint parseResumePoint(String header) {
        if (header == null || header.isBlank()) {
            return null;
        }
        String trimmed = header.trim();
        int separator = trimmed.lastIndexOf(':');
        String runId = separator <= 0 ? null : trimmed.substring(0, separator);
        String seqText = separator <= 0 || separator == trimmed.length() - 1
                ? null : trimmed.substring(separator + 1);
        if (runId == null || !RUN_ID_PATTERN.matcher(runId).matches()) {
            throw new BusinessException(400, "Last-Event-ID 格式非法，应为 <runId>:<seq>");
        }
        long seq;
        try {
            seq = Long.parseLong(seqText);
        } catch (NumberFormatException e) {
            throw new BusinessException(400, "Last-Event-ID 格式非法，应为 <runId>:<seq>");
        }
        if (seq < 0) {
            throw new BusinessException(400, "Last-Event-ID 的 seq 不能为负数");
        }
        return new ResumePoint(runId, seq);
    }

    /** 解析后的续传起点（runId + 已收到的最后 seq） */
    record ResumePoint(String runId, long lastEventId) {
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
    /**
     * 最近会话列表（体验方案 §8.6）：当前用户、按最后活动时间倒序、只读。
     *
     * <p>limit 走统一分页口径（<=0 用默认 20，>200 收敛到 200），与其它列表接口一致；
     * 不提供 offset：会话列表是「最近 N 段」的短列表，翻页没有意义。
     */
    @GetMapping("/conversations")
    public ApiResponse<List<ChatDtos.ConversationSummary>> conversations(
            @RequestParam(defaultValue = "" + Paging.DEFAULT_PAGE_LIMIT) int limit,
            Authentication authentication) {
        User user = authService.requireUser(authentication.getName());
        return ApiResponse.ok(conversationService.summaries(user.getId(), Paging.normalizeLimit(limit)));
    }

    /**
     * 某段会话最近的消息（继续对话回填）：按时间正序，默认 50 条、上限 200。
     * 不属于当前用户的 conversationId 返回空数组（不泄露是否存在）。
     */
    @GetMapping("/conversations/{conversationId}/messages")
    public ApiResponse<ChatDtos.ConversationMessagesView> conversationMessages(
            @PathVariable String conversationId,
            @RequestParam(defaultValue = "50") int limit,
            Authentication authentication) {
        User user = authService.requireUser(authentication.getName());
        return ApiResponse.ok(conversationService.messagesWithScope(user.getId(), conversationId,
                Paging.normalizeLimit(limit)));
    }

    @PostMapping("/conversation/clear")
    public ApiResponse<Void> clearConversation(@Valid @RequestBody ChatDtos.ClearConversationRequest request,
                                               Authentication authentication) {
        User user = authService.requireUser(authentication.getName());
        conversationService.clear(user.getId(), request.getConversationId());
        return ApiResponse.ok(null);
    }

    /**
     * 重命名一段会话（V23）：只改展示标题，不动消息、不影响续聊。
     *
     * <p>标题传空串 = 恢复自动标题（回到「首条用户消息」的推导标题）。
     * 不属于当前用户的 conversationId 也会正常返回 —— 写入按 userId 隔离，改不到别人的会话，
     * 也不需要用 404 告诉调用方「这段会话是否存在」（避免变成存在性探测接口）。
     */
    @PatchMapping("/conversations/{conversationId}")
    public ApiResponse<ChatDtos.ConversationRenameResult> renameConversation(
            @PathVariable String conversationId,
            @Valid @RequestBody ChatDtos.RenameConversationRequest request,
            Authentication authentication) {
        User user = authService.requireUser(authentication.getName());
        String id = sanitizeConversationId(conversationId);
        String title = conversationService.rename(user.getId(), id, request.getTitle());
        return ApiResponse.ok(ChatDtos.ConversationRenameResult.builder()
                // 回传清洗后的 id（非法 id 会被清洗成 null，此时 title 必然也是 null，语义即「什么都没改」）
                .conversationId(id)
                .title(title)
                .build());
    }

    /**
     * 删除一段会话（V23）：消息与标题覆盖一起删，不可恢复。
     *
     * <p>幂等：不存在的会话同样返回成功（重复删除不该报错），且永远删不到别人的数据。
     */
    @DeleteMapping("/conversations/{conversationId}")
    public ApiResponse<Void> deleteConversation(@PathVariable String conversationId,
                                                Authentication authentication) {
        User user = authService.requireUser(authentication.getName());
        conversationService.delete(user.getId(), sanitizeConversationId(conversationId));
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
        ctx.setRequestId(request.getRequestId());
        // 作用域：客户端显式声明优先；没声明时按「这条消息本来就带着的上下文」推导 ——
        // 带 tripId 就是在某个行程里聊，否则 `assistant`。老客户端因此也能得到正确的分组，
        // 且推导出的值一定来自请求本身，不存在「猜用户当时在看哪个页面」。
        ctx.setScope(effectiveScope(request));
        ctx.setMessage(request.getMessage());
        ctx.setCurrentDay(request.getCurrentDay());
        ctx.setSelectedElement(request.getSelectedElement());
        ctx.setMapCenter(request.getMapCenter());
        // P1：同步 /chat 与 SSE 两个入口共用同一套预算语义（从这里开始计时，含排队时间）
        ctx.setBudget(budgetProperties.newBudget(llmProperties.getReadTimeoutMs()));
        return ctx;
    }

    /** SSE 收尾超时：预算关闭时保持既有 180s；开启时给「预算 + 30s」的收尾余量，且不低于 60s */
    private static long emitterTimeoutMs(AgentBudget budget) {
        if (budget == null || !budget.isEnabled()) {
            return 180_000L;
        }
        return Math.max(60_000L, budget.totalMs() + 30_000L);
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

    /**
     * 幂等标识的清洗规则与 conversationId 相同：不合法就当作没传（不做幂等），
     * 绝不把一个任意字符串直接当去重键写库。
     */
    private static String sanitizeRequestId(String requestId) {
        return sanitizeConversationId(requestId);
    }

    /**
     * 作用域的清洗与推导（V21）。
     *
     * <p>显式声明合法就用声明的值；否则按请求本身已有的上下文推导：
     * 带 {@code tripId} 即 {@code trip:<id>}，不带则 {@code assistant}。
     * <b>推导只使用请求里的字段</b>，绝不从数据库或聊天正文反推 —— 那样等于替用户编造归属。
     *
     * <p>返回 null 的只有一种情况：scope 与 tripId 同时缺失（例如内部调用），
     * 此时按「未绑定」处理，与本次改造前完全一致。
     */
    static String effectiveScope(ChatDtos.ChatRequest request) {
        if (request == null) {
            return null;
        }
        String declared = sanitizeScope(request.getScope());
        if (declared != null) {
            return declared;
        }
        Long tripId = request.getTripId();
        return tripId == null ? SCOPE_ASSISTANT : SCOPE_TRIP + tripId;
    }

    /**
     * 作用域字符串的清洗：只允许 {@code [A-Za-z0-9:_-]} 且不超过 32 字符。
     * 不合法时返回 null（按未绑定处理）而不是报错：作用域是展示与续聊的提示，
     * 不该让一个脏字段把整条消息打回 400。
     */
    private static String sanitizeScope(String scope) {
        if (scope == null) {
            return null;
        }
        String trimmed = scope.trim();
        if (trimmed.isEmpty() || trimmed.length() > SCOPE_MAX_LENGTH
                || !SCOPE_PATTERN.matcher(trimmed).matches()) {
            return null;
        }
        return trimmed;
    }
}
