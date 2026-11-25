package com.travel.agent;

import com.travel.agent.agent.AgentContext;
import com.travel.agent.agent.AgentEngine;
import com.travel.agent.agent.IntentAgent;
import com.travel.agent.agent.IntentResult;
import com.travel.agent.entity.AgentRun;
import com.travel.agent.entity.AgentRunEvent;
import com.travel.agent.service.AgentRunEventService;
import com.travel.agent.service.AgentTraceService;
import com.travel.agent.support.IntegrationTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * P0：SSE 事件持久化（agent_run_event）。
 *
 * <p>验证事件按 seq 升序落库、listAfter 严格补发（不含游标本身、同 seq 不重复）、
 * 以及引擎真实执行时事件序列完整且以终态事件收尾 —— 断线续传的数据基础。
 */
class AgentRunEventServiceTest extends IntegrationTestSupport {

    @Autowired
    private AgentRunEventService eventService;

    @Autowired
    private AgentTraceService traceService;

    @Autowired
    private AgentEngine agentEngine;

    @MockitoBean
    private IntentAgent intentAgent;

    private String newRun(Account account) {
        AgentRun run = traceService.startRun(account.id(), null, "事件持久化测试", "req-" + UUID.randomUUID());
        return run.getRunId();
    }

    @Test
    @DisplayName("事件按 seq 升序持久化；listAfter 只补发游标之后的事件且不重复")
    void eventsArePersistedInOrderAndListedAfterCursorWithoutDuplicates() throws Exception {
        Account account = registerNew();
        String runId = newRun(account);

        eventService.append(runId, 1, "start", Map.of("sessionId", "s-1"));
        eventService.append(runId, 2, "intent", Map.of("intent", "CHITCHAT"));
        eventService.append(runId, 3, "complete", Map.of("status", "COMPLETED"));

        assertThat(eventService.eventsOf(runId)).extracting(AgentRunEvent::getSeqNo)
                .containsExactly(1, 2, 3);
        assertThat(eventService.maxSeq(runId)).isEqualTo(3);

        List<AgentRunEvent> afterOne = eventService.listAfter(runId, 1);
        assertThat(afterOne).extracting(AgentRunEvent::getSeqNo).containsExactly(2, 3);
        assertThat(afterOne).extracting(AgentRunEvent::getEventType).containsExactly("intent", "complete");

        // 有界批次：从 0 开始最多 2 条
        assertThat(eventService.listAfter(runId, 0, 2)).extracting(AgentRunEvent::getSeqNo)
                .containsExactly(1, 2);
        // 游标已在末尾：补发集合为空（不重发同一 seq）
        assertThat(eventService.listAfter(runId, 3)).isEmpty();

        // 同一 seq 重复写入：静默失败（唯一键），既有数据不变
        eventService.append(runId, 3, "complete", Map.of("status", "COMPLETED"));
        assertThat(eventService.eventsOf(runId)).hasSize(3);
        assertThat(eventService.listAfter(runId, 2)).extracting(AgentRunEvent::getSeqNo)
                .containsExactly(3);
    }

    @Test
    @DisplayName("引擎真实执行时事件序列从 1 开始、严格递增，并以 complete 收尾")
    void enginePersistsCompleteEventSequence() throws Exception {
        Account account = registerNew();
        when(intentAgent.detect(any(), any())).thenReturn(IntentResult.builder()
                .intent(IntentResult.CHITCHAT)
                .build());

        AgentContext ctx = new AgentContext();
        ctx.setUserId(account.id());
        ctx.setUsername(account.username());
        ctx.setSessionId(UUID.randomUUID().toString());
        ctx.setMessage("你好");

        agentEngine.handle(ctx);

        List<AgentRunEvent> events = eventService.eventsOf(ctx.getRunId());
        assertThat(events).isNotEmpty();
        List<Integer> seqs = events.stream().map(AgentRunEvent::getSeqNo).toList();
        assertThat(seqs).containsExactlyElementsOf(
                java.util.stream.IntStream.rangeClosed(1, seqs.size()).boxed().toList());
        assertThat(events.get(0).getEventType()).isEqualTo("start");
        assertThat(events).extracting(AgentRunEvent::getEventType).contains("run");
        assertThat(events.get(events.size() - 1).getEventType()).isEqualTo("complete");

        // run 收尾：终态写入 agent_run，且 stage 是终态名
        AgentRun run = traceService.findRun(ctx.getRunId(), account.id()).orElseThrow();
        assertThat(run.getStatus()).isEqualTo(AgentRun.STATUS_SUCCEEDED);
        assertThat(run.getStage()).isEqualTo(AgentRun.STAGE_DONE);
    }

    @Test
    @DisplayName("实时 SSE 帧带 id「<runId>:<seq>」，seq 与持久化事件一一对应")
    void liveSseFramesCarryPersistedEventIds() throws Exception {
        Account account = registerNew();
        when(intentAgent.detect(any(), any())).thenReturn(IntentResult.builder()
                .intent(IntentResult.CHITCHAT)
                .build());

        String body = sseDispatch(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .get("/api/agent/stream")
                .param("message", "你好")
                .header("Authorization", account.bearer()))
                .getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);

        List<String> ids = body.lines()
                .filter(line -> line.startsWith("id:"))
                .map(line -> line.substring("id:".length()))
                .toList();
        assertThat(ids).isNotEmpty();
        String runId = ids.get(0).substring(0, ids.get(0).lastIndexOf(':'));
        assertThat(ids).allSatisfy(id -> assertThat(id).startsWith(runId + ":"));
        List<Integer> frameSeqs = ids.stream()
                .map(id -> Integer.parseInt(id.substring(id.lastIndexOf(':') + 1)))
                .toList();
        assertThat(frameSeqs).containsExactlyElementsOf(
                java.util.stream.IntStream.rangeClosed(1, frameSeqs.size()).boxed().toList());
        assertThat(eventService.eventsOf(runId)).extracting(AgentRunEvent::getSeqNo)
                .containsExactlyElementsOf(frameSeqs);
    }

    @Test
    @DisplayName("按 run 清理：只删该 run 的事件，其他 run 不受影响")
    void purgeByRunOnlyDeletesThatRun() throws Exception {
        Account account = registerNew();
        String first = newRun(account);
        String second = newRun(account);
        eventService.append(first, 1, "start", Map.of());
        eventService.append(second, 1, "start", Map.of());

        assertThat(eventService.purgeByRun(first)).isEqualTo(1L);
        assertThat(eventService.eventsOf(first)).isEmpty();
        assertThat(eventService.eventsOf(second)).hasSize(1);
    }
}
