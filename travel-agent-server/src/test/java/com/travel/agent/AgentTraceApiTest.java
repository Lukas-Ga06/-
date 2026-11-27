package com.travel.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.travel.agent.support.IntegrationTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * V2.2 FR-10 / FR-02：Agent 运行追踪的查询契约。
 *
 * <p>一次对话（同步 chat）= 一次 run：消息摘要、意图、状态可查；
 * 规划类 run 内的阶段任务链（INTENT → PLAN → PREVIEW → REVIEW）与
 * 工具调用（规则路径也会经 ToolInvoker 落 trace）都能按 runId 回查；
 * 追踪数据按用户隔离，他人的 run 一律 404。
 */
class AgentTraceApiTest extends IntegrationTestSupport {

    private JsonNode chat(Account account, String message) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/agent/chat")
                        .header("Authorization", account.bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"" + message + "\"}"))
                .andExpect(status().isOk())
                .andReturn();
        return body(result).path("data");
    }

    private JsonNode recentRuns(Account account) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/agent/runs?limit=10")
                        .header("Authorization", account.bearer()))
                .andExpect(status().isOk())
                .andReturn();
        return body(result).path("data");
    }

    private JsonNode runDetail(Account account, String runId) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/agent/runs/" + runId)
                        .header("Authorization", account.bearer()))
                .andExpect(status().isOk())
                .andReturn();
        return body(result).path("data");
    }

    @Test
    @DisplayName("闲聊对话也建立 run：完成后可查，含意图任务与状态")
    void chatCreatesQueryableRun() throws Exception {
        Account account = registerNew();
        JsonNode result = chat(account, "你好");
        assertThat(result.path("intent").asText()).isEqualTo("CHITCHAT");

        JsonNode runs = recentRuns(account);
        assertThat(runs.isArray()).isTrue();
        assertThat(runs.size()).isGreaterThanOrEqualTo(1);
        JsonNode latest = runs.get(0);
        assertThat(latest.path("runId").asText()).isNotBlank();
        assertThat(latest.path("status").asText()).isEqualTo("SUCCEEDED");
        assertThat(latest.path("intent").asText()).isEqualTo("CHITCHAT");
        assertThat(latest.path("message").asText()).isEqualTo("你好");

        JsonNode detail = runDetail(account, latest.path("runId").asText());
        assertThat(detail.path("run").path("runId").asText()).isEqualTo(latest.path("runId").asText());
        // 闲聊只有意图任务，没有工具调用
        assertThat(detail.path("tasks").isArray()).isTrue();
        assertThat(detail.path("tasks").size()).isGreaterThanOrEqualTo(1);
        assertThat(detail.path("tasks").get(0).path("receiver").asText()).isEqualTo("intent-agent");
        assertThat(detail.path("toolCalls").size()).isEqualTo(0);
        // 第二阶段：闲聊同样收尾花费快照（LLM/工具计数可以为 0，但行必须在）
        assertThat(detail.path("spend").isObject()).isTrue();
        assertThat(detail.path("spend").path("toolCalls").asInt()).isZero();
    }

    @Test
    @DisplayName("规划对话的任务链与工具调用可按 runId 回查")
    void planningRunRecordsTaskChainAndToolCalls() throws Exception {
        Account account = registerNew();
        JsonNode result = chat(account, "帮我规划上海三日游，2人，预算3000元");
        assertThat(result.path("status").asText()).isEqualTo("NEED_CONFIRMATION");

        JsonNode runs = recentRuns(account);
        JsonNode latest = runs.get(0);
        assertThat(latest.path("intent").asText()).isEqualTo("PLAN_TRIP");
        // P0-07：状态是聚合结论，不再是「HTTP 200 就算成功」。测试环境没有真实天气/供应商，
        // 规划会带 MEDIUM 过程警告（如「模拟天气」）——有警告时必须是 PARTIAL_SUCCESS，
        // 没有警告时才是 SUCCEEDED。断言二者一致，避免把带警告的结果伪装成绿色。
        boolean hasWarnings = result.path("warnings").size() > 0;
        assertThat(latest.path("status").asText())
                .isEqualTo(hasWarnings ? "PARTIAL_SUCCESS" : "SUCCEEDED");

        JsonNode detail = runDetail(account, latest.path("runId").asText());
        List<String> taskTypes = new ArrayList<>();
        detail.path("tasks").forEach(t -> {
            taskTypes.add(t.path("taskType").asText());
            assertThat(t.path("status").asText()).isEqualTo("SUCCEEDED");
        });
        // 规划链路：意图 → 规划 → 预览 → 审核（返回顺序即 id 顺序）
        assertThat(taskTypes).containsSubsequence("INTENT", "PLAN", "PREVIEW", "REVIEW");
        // 规则路径的固定工具调用也留痕：search_hotel / search_attraction / calculate_route…
        assertThat(detail.path("toolCalls").size()).isGreaterThanOrEqualTo(3);
        List<String> tools = new ArrayList<>();
        detail.path("toolCalls").forEach(c -> {
            assertThat(c.path("success").asBoolean()).isTrue();
            tools.add(c.path("toolName").asText());
        });
        assertThat(tools).contains("search_hotel");

        // 第二阶段：详情接口直接给出本轮花费快照（V29），不必再翻日志
        JsonNode spend = detail.path("spend");
        assertThat(spend.isObject()).as("运行详情必须能查到花费快照").isTrue();
        assertThat(spend.path("llmCalls").asInt()).as("LLM 调用数应落库").isGreaterThanOrEqualTo(0);
        assertThat(spend.path("toolCalls").asInt()).as("工具调用数应与 toolCalls 追踪一致").isGreaterThanOrEqualTo(3);
        assertThat(spend.path("totalTokens").asLong()).isGreaterThanOrEqualTo(0);
        assertThat(spend.hasNonNull("costUsd")).isTrue();
        assertThat(spend.path("exhausted").isNull() || spend.path("exhausted").isMissingNode())
                .as("默认上限宽松，正常规划不应触顶").isTrue();
    }

    @Test
    @DisplayName("运行追踪按用户隔离：他人的 runId 与未知 runId 一律 404")
    void runDetailIsIsolatedPerUser() throws Exception {
        Account owner = registerNew();
        chat(owner, "在吗");
        String runId = recentRuns(owner).get(0).path("runId").asText();

        Account stranger = registerNew();
        mockMvc.perform(get("/api/agent/runs/" + runId)
                        .header("Authorization", stranger.bearer()))
                .andExpect(status().isNotFound());

        mockMvc.perform(get("/api/agent/runs/run-does-not-exist")
                        .header("Authorization", owner.bearer()))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("未认证访问追踪端点被拒绝")
    void traceEndpointsRequireAuth() throws Exception {
        mockMvc.perform(get("/api/agent/runs")).andExpect(status().isUnauthorized());
    }
}
