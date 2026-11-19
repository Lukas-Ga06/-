package com.travel.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.travel.agent.agent.AgentEngine;
import com.travel.agent.agent.IntentAgent;
import com.travel.agent.agent.IntentResult;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * P1 开关验证：{@code travel.agent.budget.enabled=false} 时行为与升级前完全一致。
 *
 * <p>断言口径：不出现预算降级提示/事件，审核照常执行（{@code review} 有值），
 * 运行追踪里也不会有 DEGRADE 任务。这样「默认开启」与「可关闭」两个方向都有测试兜底。
 */
@SpringBootTest(properties = "travel.agent.budget.enabled=false")
class AgentBudgetDisabledTest extends IntegrationTestSupport {

    private static final String PLAN_MESSAGE = "帮我规划上海三日游，2人，预算3000元";

    @MockitoBean
    private IntentAgent intentAgent;

    @Autowired
    private AgentTraceService traceService;

    @Test
    @DisplayName("预算关闭：审核照常执行、无预算提示、追踪里没有 DEGRADE 任务")
    void disabledBudgetKeepsLegacyPipeline() throws Exception {
        Account account = registerNew();
        when(intentAgent.detect(any(), any())).thenReturn(IntentResult.builder()
                .intent(IntentResult.PLAN_TRIP)
                .target(IntentResult.TARGET_NONE)
                .build());

        MvcResult result = mockMvc.perform(post("/api/agent/chat")
                        .header("Authorization", account.bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"" + PLAN_MESSAGE + "\"}"))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode data = objectMapper.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8))
                .path("data");

        assertThat(data.path("status").asText()).isEqualTo("NEED_CONFIRMATION");
        assertThat(data.path("message").asText())
                .as("关闭开关后不应出现任何预算降级文案")
                .doesNotContain("时间预算");
        assertThat(data.path("review").path("attempts").asInt(-1))
                .as("关闭开关后审核阶段必须照常执行（review 非空）")
                .isGreaterThanOrEqualTo(0);

        assertThat(traceService.recentRuns(account.id(), 5)).isNotEmpty();
        String runId = traceService.recentRuns(account.id(), 5).get(0).getRunId();
        assertThat(traceService.tasksOf(runId))
                .as("关闭开关后不应出现预算降级任务")
                .noneSatisfy(task -> assertThat(task.getTaskType()).isEqualTo(AgentEngine.BUDGET_TASK_TYPE));
    }
}