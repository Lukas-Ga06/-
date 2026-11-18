package com.travel.agent;

import com.travel.agent.agent.AgentContext;
import com.travel.agent.agent.AgentEngine;
import com.travel.agent.agent.IntentAgent;
import com.travel.agent.agent.IntentResult;
import com.travel.agent.dto.AgentDtos;
import com.travel.agent.support.IntegrationTestSupport;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * SSE 断连与取消：{@code ctx.cancel()} 之后引擎必须在阶段边界短路（不再调 LLM/工具），
 * 而正常收尾的连接不能被误记成断连。
 *
 * <p>断连回调本身（onCompletion/onTimeout/onError）由 Servlet 容器触发，
 * 这里用 {@code AgentController.StreamLifecycle} 的包可见单测覆盖（见 SseStreamLifecycleTest），
 * 本类验证"取消标记 → 引擎短路"和"正常完成不计数"两件事。
 */
class SseDisconnectTest extends IntegrationTestSupport {

    private static final String DISCONNECT_METRIC = "sse.disconnects";

    @Autowired
    private MeterRegistry meterRegistry;

    @Autowired
    private AgentEngine agentEngine;

    @MockitoBean
    private IntentAgent intentAgent;

    private double disconnectCount(String reason) {
        Counter counter = meterRegistry.find(DISCONNECT_METRIC).tag("reason", reason).counter();
        return counter == null ? 0.0 : counter.count();
    }

    private AgentContext context(Account account, String message) {
        AgentContext ctx = new AgentContext();
        ctx.setUserId(account.id());
        ctx.setUsername(account.username());
        ctx.setSessionId(UUID.randomUUID().toString());
        ctx.setMessage(message);
        return ctx;
    }

    /** GET /api/agent/stream 跑到结束，返回 SSE 文本 */
    private String stream(Account account, String message) throws Exception {
        return sseDispatch(get("/api/agent/stream")
                .header("Authorization", account.bearer())
                .param("message", message))
                .getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("正常跑完的 SSE 不算断连：complete/cancelled 计数都不增加，complete 事件照常下发")
    void normalCompletionIsNotCountedAsDisconnect() throws Exception {
        Account account = registerNew();
        when(intentAgent.detect(any(), any())).thenReturn(IntentResult.builder()
                .intent(IntentResult.CHITCHAT)
                .build());

        double completeBefore = disconnectCount("complete");
        double cancelledBefore = disconnectCount("cancelled");

        String body = stream(account, "你好");

        assertThat(body).as("正常结束仍然发 complete 事件").contains("complete");
        assertThat(disconnectCount("complete")).isEqualTo(completeBefore);
        assertThat(disconnectCount("cancelled")).isEqualTo(cancelledBefore);
    }

    @Test
    @DisplayName("已取消的 ctx 在 intent 之前就短路：不调用意图识别，记一次 cancelled")
    void cancelledContextShortCircuitsBeforeIntent() throws Exception {
        Account account = registerNew();
        AgentContext ctx = context(account, "帮我规划上海三日游");
        double cancelledBefore = disconnectCount("cancelled");

        ctx.cancel();
        AgentDtos.AgentResult result = agentEngine.handle(ctx);

        assertThat(result.getIntent()).isEqualTo(AgentEngine.CANCELLED_INTENT);
        assertThat(result.getStatus()).isEqualTo(AgentDtos.STATUS_FAILED);
        assertThat(disconnectCount("cancelled")).isEqualTo(cancelledBefore + 1.0);
        // 短路发生在阶段边界：意图识别（LLM 入口）一次都没被调用
        verifyNoInteractions(intentAgent);
    }

    @Test
    @DisplayName("intent 之后被取消：不再进入 plan，也不下发 intent/complete 事件")
    void cancelAfterIntentStopsBeforePlan() throws Exception {
        Account account = registerNew();
        // 意图识别进行中客户端断开：detect 返回前把取消标记置位
        when(intentAgent.detect(any(), any())).thenAnswer(invocation -> {
            AgentContext running = invocation.getArgument(0);
            running.cancel();
            return IntentResult.builder().intent(IntentResult.PLAN_TRIP).build();
        });

        AgentContext ctx = context(account, "帮我规划上海三日游");
        List<String> events = new CopyOnWriteArrayList<>();
        ctx.addListener(event -> events.add(event.event()));
        double cancelledBefore = disconnectCount("cancelled");

        AgentDtos.AgentResult result = agentEngine.handle(ctx);

        assertThat(result.getIntent()).isEqualTo(AgentEngine.CANCELLED_INTENT);
        assertThat(events).as("只有 start 与 run（V2.2 追踪标识）；取消后不再规划、不再 complete")
                .containsExactly("start", "run");
        assertThat(disconnectCount("cancelled")).isEqualTo(cancelledBefore + 1.0);
    }

    @Test
    @DisplayName("同一轮多次命中取消检查也只记一次 cancelled")
    void cancellationIsCountedOncePerRun() throws Exception {
        Account account = registerNew();
        AgentContext ctx = context(account, "帮我规划上海三日游");
        ctx.cancel();
        double cancelledBefore = disconnectCount("cancelled");

        // 直接调用两次 handle 模拟重入：每次运行各计一次，不会因为内部分支多反而多记
        agentEngine.handle(ctx);
        agentEngine.handle(ctx);

        assertThat(disconnectCount("cancelled")).isEqualTo(cancelledBefore + 2.0);
    }
}