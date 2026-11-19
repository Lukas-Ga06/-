package com.travel.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.travel.agent.agent.AgentEngine;
import com.travel.agent.agent.IntentAgent;
import com.travel.agent.agent.IntentResult;
import com.travel.agent.entity.AgentTask;
import com.travel.agent.service.AgentTraceService;
import com.travel.agent.support.IntegrationTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * P1 验收：慢依赖（这里是可控的慢意图阶段）+ 小预算（250ms）下的端到端行为。
 *
 * <p>覆盖验收口径：
 * <ol>
 *   <li>同步 {@code /api/agent/chat} 在「预算 + 有限余量」内返回，且仍给出用户可见、可追踪的结果；</li>
 *   <li>SSE {@code /api/agent/stream} 仍发出收尾事件（complete），不把连接悬着；</li>
 *   <li>降级可观测：回复里如实说明「跳过了什么」，运行追踪里能查到 DEGRADE 任务。</li>
 * </ol>
 *
 * <p>这里故意把预算设成 250ms、意图阶段模拟 300ms 慢依赖：预算必然在 intent 之后耗尽，
 * 于是「跳过审核/润色 → 规则规划兜底 → 仍返回待确认方案」这条降级路径被真实走过一遍。
 * 不访问网络、不调用真实大模型（测试 profile 的 LLM 本来就是关闭的）。
 */
@SpringBootTest(properties = {
        "travel.agent.budget.enabled=true",
        "travel.agent.budget.total-ms=250"
})
class AgentBudgetIntegrationTest extends IntegrationTestSupport {

    private static final long TOTAL_MS = 250L;
    /** 模拟的慢意图耗时：必须大于总预算，才能稳定触发预算耗尽 */
    private static final long SLOW_INTENT_MS = 300L;
    /** 断言用的余量：覆盖 MockMvc/H2/线程调度抖动，但绝不能接近「5 个阶段全跑」的旧耗时 */
    private static final long MARGIN_MS = 2000L;
    private static final String PLAN_MESSAGE = "帮我规划上海三日游，2人，预算3000元";

    @MockitoBean
    private IntentAgent intentAgent;

    @Autowired
    private AgentTraceService traceService;

    /** 慢意图：返回一个规划意图，但先睡 300ms（模拟 LLM 慢依赖） */
    private void slowIntent() {
        when(intentAgent.detect(any(), any())).thenAnswer(invocation -> {
            Thread.sleep(SLOW_INTENT_MS);
            return IntentResult.builder()
                    .intent(IntentResult.PLAN_TRIP)
                    .target(IntentResult.TARGET_NONE)
                    .build();
        });
    }

    private String chatBody(Account account) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/agent/chat")
                        .header("Authorization", account.bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"" + PLAN_MESSAGE + "\"}"))
                .andExpect(status().isOk())
                .andReturn();
        return result.getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    private long millisSince(long startedNanos) {
        return (System.nanoTime() - startedNanos) / 1_000_000L;
    }

    @Test
    @DisplayName("同步 /chat：慢意图吃掉预算后仍在预算+余量内返回待确认方案，并如实标注降级")
    void syncChatReturnsWithinBudgetAndReportsDegradation() throws Exception {
        Account account = registerNew();
        slowIntent();

        long started = System.nanoTime();
        String body = chatBody(account);
        long elapsedMs = millisSince(started);

        JsonNode data = objectMapper.readTree(body).path("data");
        assertThat(elapsedMs)
                .as("慢意图已耗时 %dms，超过预算 %dms 后必须尽快返回；实际 %dms", SLOW_INTENT_MS, TOTAL_MS, elapsedMs)
                .isBetween(SLOW_INTENT_MS, TOTAL_MS + MARGIN_MS);
        assertThat(data.path("status").asText()).isEqualTo("NEED_CONFIRMATION");
        assertThat(data.path("planId").asText()).as("降级也必须产出可确认的方案").isNotBlank();
        assertThat(data.path("message").asText())
                .as("用户可见文案必须说明预算耗尽与降级内容，不得静默假成功")
                .contains("时间预算")
                .contains("跳过审核与重规划");
        assertThat(data.path("warnings").toString()).contains("时间预算");

        // 追踪：本轮运行能查到 DEGRADE 任务，summary 写明降级阶段
        List<com.travel.agent.entity.AgentRun> runs = traceService.recentRuns(account.id(), 5);
        assertThat(runs).isNotEmpty();
        String runId = runs.get(0).getRunId();
        assertThat(traceService.tasksOf(runId))
                .as("运行追踪里必须能查到「预算耗尽 → 降级了哪个阶段」")
                .anySatisfy(task -> {
                    assertThat(task.getTaskType()).isEqualTo(AgentEngine.BUDGET_TASK_TYPE);
                    assertThat(task.getSummary()).contains("review").contains("预算");
                })
                .allSatisfy(task -> assertThat(task.getStatus()).isNotEqualTo(AgentTask.STATUS_FAILED));
    }

    @Test
    @DisplayName("SSE /stream：预算耗尽后仍发出 budget_degraded 与 complete 收尾事件，连接不悬着")
    void sseEmitsClosingEventAfterBudgetDegradation() throws Exception {
        Account account = registerNew();
        slowIntent();

        long started = System.nanoTime();
        String body = sseDispatch(get("/api/agent/stream")
                .header("Authorization", account.bearer())
                .param("message", PLAN_MESSAGE))
                .getResponse().getContentAsString(StandardCharsets.UTF_8);
        long elapsedMs = millisSince(started);

        assertThat(elapsedMs).isBetween(SLOW_INTENT_MS, TOTAL_MS + MARGIN_MS);
        assertThat(body)
                .as("降级事件 + 收尾事件都要下发（客户端不会一直等）")
                .contains("event:budget_degraded")
                .contains("event:complete");
        assertThat(body).contains("时间预算");
    }
}