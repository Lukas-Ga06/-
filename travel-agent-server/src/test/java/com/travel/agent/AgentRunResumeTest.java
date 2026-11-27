package com.travel.agent;

import com.travel.agent.entity.AgentRun;
import com.travel.agent.service.AgentRunEventService;
import com.travel.agent.service.AgentTraceService;
import com.travel.agent.support.IntegrationTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * P0：SSE 断线续传（GET /api/agent/runs/{runId}/events 与 Last-Event-ID）。
 *
 * <p>覆盖补发语义（只发 seq &gt; N、不重复）、终态 run 缺终态事件时合成 complete、
 * 运行中轮询到新事件、归属校验 404、非法 Last-Event-ID 明确报错且绝不静默重新规划。
 */
class AgentRunResumeTest extends IntegrationTestSupport {

    @Autowired
    private AgentRunEventService eventService;

    @Autowired
    private AgentTraceService traceService;

    private String newRun(Account account) {
        AgentRun run = traceService.startRun(account.id(), null, "续传测试", "req-" + UUID.randomUUID());
        return run.getRunId();
    }

    private String resumeBody(Account account, String runId, long lastEventId) throws Exception {
        return sseDispatch(get("/api/agent/runs/" + runId + "/events")
                .param("lastEventId", Long.toString(lastEventId))
                .header("Authorization", account.bearer()))
                .getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("终态 run：只补发 seq>N 的事件；缺终态事件时合成 complete（status=FAILED，说明已中断）")
    void terminalRunResumesAndSynthesizesCompleteWhenTerminalEventMissing() throws Exception {
        Account account = registerNew();
        String runId = newRun(account);
        eventService.append(runId, 1, "start", Map.of("sessionId", "s-1"));
        eventService.append(runId, 2, "intent", Map.of("intent", "CHITCHAT"));
        traceService.finishRun(runId, "CHITCHAT", AgentRun.STATUS_FAILED, "执行体异常");

        String body = resumeBody(account, runId, 1);

        assertThat(body).as("只补发 seq>1 的事件：seq=2 带 id")
                .contains("id:" + runId + ":2");
        assertThat(body).as("已确认收到的 seq=1 不重复补发")
                .doesNotContain("id:" + runId + ":1");
        assertThat(body).contains("event:complete");
        assertThat(body).contains("运行已中断");
        assertThat(body).contains("\"status\":\"FAILED\"");
        assertThat(body.split("event:complete", -1).length - 1)
                .as("合成 complete 只能出现一次").isEqualTo(1);
    }

    @Test
    @DisplayName("终态 run：补发集合里已有 complete 时不再合成（不重复、不误报中断）")
    void terminalRunWithPersistedCompleteDoesNotSynthesize() throws Exception {
        Account account = registerNew();
        String runId = newRun(account);
        eventService.append(runId, 1, "start", Map.of("sessionId", "s-1"));
        eventService.append(runId, 2, "complete", Map.of("status", "COMPLETED"));
        traceService.finishRun(runId, "CHITCHAT", AgentRun.STATUS_SUCCEEDED, null);

        String body = resumeBody(account, runId, 0);

        assertThat(body).contains("id:" + runId + ":1");
        assertThat(body).contains("id:" + runId + ":2");
        assertThat(body).contains("event:complete");
        assertThat(body).contains("\"status\":\"COMPLETED\"");
        assertThat(body).doesNotContain("运行已中断");
        assertThat(body.split("event:complete", -1).length - 1).isEqualTo(1);
    }

    @Test
    @DisplayName("RUNNING run：续传连接轮询到新事件并正常收尾（不重复、不卡死）")
    void runningRunResumesNewEventsWhilePolling() throws Exception {
        Account account = registerNew();
        String runId = newRun(account);
        eventService.append(runId, 1, "start", Map.of("sessionId", "s-1"));

        MvcResult started = withSseMockMvc(() -> mockMvc.perform(get("/api/agent/runs/" + runId + "/events")
                        .param("lastEventId", "1")
                        .header("Authorization", account.bearer()))
                .andExpect(status().isOk())
                .andExpect(request().asyncStarted())
                .andReturn());

        // 连接建立后运行继续推进：写新事件并把 run 收尾（模拟引擎线程仍在跑）
        eventService.append(runId, 2, "intent", Map.of("intent", "CHITCHAT"));
        eventService.append(runId, 3, "complete", Map.of("status", "COMPLETED"));
        traceService.finishRun(runId, "CHITCHAT", AgentRun.STATUS_SUCCEEDED, null);

        started.getAsyncResult(15_000);
        MvcResult done = withSseMockMvc(() -> mockMvc.perform(asyncDispatch(started)).andReturn());
        String body = done.getResponse().getContentAsString(StandardCharsets.UTF_8);

        assertThat(body).contains("id:" + runId + ":2");
        assertThat(body).contains("id:" + runId + ":3");
        assertThat(body).doesNotContain("id:" + runId + ":1");
        assertThat(body).contains("event:complete");
        assertThat(body).doesNotContain("运行已中断");
    }

    @Test
    @DisplayName("归属校验：他人的 run 与不存在的 run 一律 404")
    void resumeRejectsForeignAndUnknownRuns() throws Exception {
        Account owner = registerNew();
        String runId = newRun(owner);
        traceService.finishRun(runId, null, AgentRun.STATUS_FAILED, "x");
        Account stranger = registerNew();

        mockMvc.perform(get("/api/agent/runs/" + runId + "/events")
                        .param("lastEventId", "0")
                        .header("Authorization", stranger.bearer()))
                .andExpect(status().isNotFound());

        mockMvc.perform(get("/api/agent/runs/run-does-not-exist/events")
                        .param("lastEventId", "0")
                        .header("Authorization", owner.bearer()))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("GET /stream 带 Last-Event-ID 时续传原 run，不新开 run")
    void streamWithLastEventIdResumesInsteadOfStartingNewRun() throws Exception {
        Account account = registerNew();
        String runId = newRun(account);
        eventService.append(runId, 1, "start", Map.of("sessionId", "s-1"));
        eventService.append(runId, 2, "complete", Map.of("status", "COMPLETED"));
        traceService.finishRun(runId, "CHITCHAT", AgentRun.STATUS_SUCCEEDED, null);
        int runsBefore = traceService.recentRuns(account.id(), 20).size();

        String body = sseDispatch(get("/api/agent/stream")
                .param("message", "这条消息不应被重新规划")
                .header("Authorization", account.bearer())
                .header("Last-Event-ID", runId + ":1"))
                .getResponse().getContentAsString(StandardCharsets.UTF_8);

        assertThat(body).contains("id:" + runId + ":2");
        assertThat(body).doesNotContain("id:" + runId + ":1");
        assertThat(traceService.recentRuns(account.id(), 20))
                .as("带 Last-Event-ID 的请求绝不能新开 run").hasSize(runsBefore);
    }

    @Test
    @DisplayName("Last-Event-ID 非法：明确 400，且不新开 run（绝不静默重新规划）")
    void malformedLastEventIdIsRejectedWithoutStartingNewRun() throws Exception {
        Account account = registerNew();

        mockMvc.perform(get("/api/agent/stream")
                        .param("message", "你好")
                        .header("Authorization", account.bearer())
                        .header("Last-Event-ID", "not-a-run-id"))
                .andExpect(status().isBadRequest());

        mockMvc.perform(get("/api/agent/stream")
                        .param("message", "你好")
                        .header("Authorization", account.bearer())
                        .header("Last-Event-ID", "run-without-seq:"))
                .andExpect(status().isBadRequest());

        assertThat(traceService.recentRuns(account.id(), 20))
                .as("非法 Last-Event-ID 不能退回新开 run").isEmpty();
    }

    @Test
    @DisplayName("EventSource 场景：agent-stream 票据可以打开续传端点（只读）")
    void resumeEndpointAcceptsAgentStreamTicket() throws Exception {
        Account account = registerNew();
        String runId = newRun(account);
        eventService.append(runId, 1, "complete", Map.of("status", "COMPLETED"));
        traceService.finishRun(runId, null, AgentRun.STATUS_SUCCEEDED, null);
        MvcResult ticketResult = mockMvc.perform(post("/api/access-ticket")
                        .param("purpose", "agent-stream")
                        .header("Authorization", account.bearer()))
                .andExpect(status().isOk())
                .andReturn();
        String ticket = body(ticketResult).path("data").path("ticket").asText();

        String body = sseDispatch(get("/api/agent/runs/" + runId + "/events")
                .param("ticket", ticket)
                .param("lastEventId", "0"))
                .getResponse().getContentAsString(StandardCharsets.UTF_8);

        assertThat(body).contains("id:" + runId + ":1");
        assertThat(body).contains("event:complete");
    }
}
