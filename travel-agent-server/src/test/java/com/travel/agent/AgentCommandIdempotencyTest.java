package com.travel.agent;

import com.travel.agent.agent.AgentContext;
import com.travel.agent.agent.AgentEngine;
import com.travel.agent.agent.IntentResult;
import com.travel.agent.dto.AgentDtos;
import com.travel.agent.entity.AgentCommandDedupe;
import com.travel.agent.entity.Memo;
import com.travel.agent.repository.AgentCommandDedupeRepository;
import com.travel.agent.repository.MemoRepository;
import com.travel.agent.service.AgentCommandIdempotency;
import com.travel.agent.support.IntegrationTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 写入命令幂等（P0-01）。
 *
 * <p>线上故障形态：SSE 是 GET + EventSource，消息在 URL 上；刷新 / 前进后退 / 弱网重连
 * 会把同一条「记一下明天办签证」重放一次，于是备忘被重复创建。前端已改成发送前消费 URL 参数，
 * 但只有服务端按 (userId, requestId) 去重才算「无论刷新、重连、重放都只写一次」。
 */
class AgentCommandIdempotencyTest extends IntegrationTestSupport {

    @Autowired
    private AgentEngine agentEngine;

    @Autowired
    private MemoRepository memoRepository;

    @Autowired
    private AgentCommandDedupeRepository dedupeRepository;

    @Autowired
    private AgentCommandIdempotency idempotency;

    private AgentDtos.AgentResult send(Account account, String message, String requestId) {
        AgentContext ctx = new AgentContext();
        ctx.setUserId(account.id());
        ctx.setUsername(account.username());
        ctx.setSessionId(UUID.randomUUID().toString());
        ctx.setMessage(message);
        ctx.setRequestId(requestId);
        return agentEngine.handle(ctx);
    }

    private List<Memo> memosOf(long userId) {
        return memoRepository.findByUserIdOrderByDoneAscCreatedAtDesc(userId);
    }

    @Test
    @DisplayName("同一 requestId 重放两次，只写入一条备忘，且第二次是首次结果的重放")
    void replayWithSameRequestIdWritesOnce() throws Exception {
        Account account = registerNew();
        String requestId = "req-" + UUID.randomUUID();

        AgentDtos.AgentResult first = send(account, "记一下明天办签证", requestId);
        AgentDtos.AgentResult replay = send(account, "记一下明天办签证", requestId);

        assertThat(first.getIntent()).isEqualTo(IntentResult.ADD_MEMO);
        assertThat(memosOf(account.id())).hasSize(1);
        // 重放返回的是「那一次」的答复，不是一条新的「已添加」
        assertThat(replay.getMessage()).isEqualTo(first.getMessage());
        assertThat(replay.getStatus()).isEqualTo(AgentDtos.STATUS_COMPLETED);

        AgentCommandDedupe row = dedupeRepository
                .findByUserIdAndRequestId(account.id(), requestId).orElseThrow();
        assertThat(row.getStatus()).isEqualTo(AgentCommandDedupe.STATUS_COMPLETED);
        assertThat(row.getResultJson()).isNotBlank();
    }

    @Test
    @DisplayName("不同 requestId 视为两条不同命令，各写一条")
    void differentRequestIdsBothWrite() throws Exception {
        Account account = registerNew();

        send(account, "记一下明天办签证", "req-" + UUID.randomUUID());
        send(account, "记一下明天办签证", "req-" + UUID.randomUUID());

        assertThat(memosOf(account.id())).hasSize(2);
    }

    @Test
    @DisplayName("不带 requestId 的旧客户端行为不变：两次都写入")
    void withoutRequestIdBehavesAsBefore() throws Exception {
        Account account = registerNew();

        send(account, "记一下明天办签证", null);
        send(account, "记一下明天办签证", null);

        assertThat(memosOf(account.id())).hasSize(2);
        assertThat(dedupeRepository.findByUserIdAndRequestId(account.id(), null)).isEmpty();
    }

    @Test
    @DisplayName("幂等只覆盖写入类意图：查询类带同一 requestId 也不会被挡")
    void readOnlyIntentIsNotDeduped() throws Exception {
        Account account = registerNew();
        String requestId = "req-" + UUID.randomUUID();

        AgentDtos.AgentResult first = send(account, "我有哪些待办", requestId);
        AgentDtos.AgentResult second = send(account, "我有哪些待办", requestId);

        // 只读意图不占用幂等台账：同一个 requestId 仍然每次都正常回答
        assertThat(first.getStatus()).isEqualTo(AgentDtos.STATUS_COMPLETED);
        assertThat(second.getStatus()).isEqualTo(AgentDtos.STATUS_COMPLETED);
        assertThat(second.getMessage()).isNotBlank();
        assertThat(dedupeRepository.findByUserIdAndRequestId(account.id(), requestId)).isEmpty();
    }

    @Test
    @DisplayName("台账层面：首次 EXECUTE、完成后 REPLAY、并发中 IN_PROGRESS、释放后可重试")
    void serviceClaimsReplaysAndReleases() {
        long userId = 987_654_321L;
        String requestId = "req-svc-" + UUID.randomUUID();
        AgentDtos.AgentResult result = AgentDtos.AgentResult.builder()
                .intent(IntentResult.ADD_MEMO)
                .status(AgentDtos.STATUS_COMPLETED)
                .message("📝 已添加备忘：「明天办签证」")
                .build();

        assertThat(idempotency.claim(userId, requestId, IntentResult.ADD_MEMO).decision())
                .isEqualTo(AgentCommandIdempotency.Decision.EXECUTE);
        // 抢占中：同一 requestId 再来一次不能执行
        assertThat(idempotency.claim(userId, requestId, IntentResult.ADD_MEMO).decision())
                .isEqualTo(AgentCommandIdempotency.Decision.IN_PROGRESS);

        idempotency.complete(userId, requestId, result);
        AgentCommandIdempotency.Outcome replay = idempotency.claim(userId, requestId, IntentResult.ADD_MEMO);
        assertThat(replay.decision()).isEqualTo(AgentCommandIdempotency.Decision.REPLAY);
        assertThat(replay.result().getMessage()).isEqualTo(result.getMessage());

        // 另一个 requestId：释放后应能重新执行（失败不能把命令永久拉黑）
        String retryable = "req-svc-" + UUID.randomUUID();
        assertThat(idempotency.claim(userId, retryable, IntentResult.ADD_MEMO).decision())
                .isEqualTo(AgentCommandIdempotency.Decision.EXECUTE);
        idempotency.release(userId, retryable);
        assertThat(idempotency.claim(userId, retryable, IntentResult.ADD_MEMO).decision())
                .isEqualTo(AgentCommandIdempotency.Decision.EXECUTE);
    }

    @Test
    @DisplayName("HTTP 入口把 requestId 一路传到闸门：同一请求重放两次只写一条")
    void httpEndpointPassesRequestIdThrough() throws Exception {
        Account account = registerNew();
        String requestId = "req-http-" + UUID.randomUUID();
        String body = "{\"message\":\"记一下明天办签证\",\"requestId\":\"%s\"}".formatted(requestId);

        // 请求体完全相同 = 浏览器刷新 / 代理重试 / 手动重放的真实形态
        for (int i = 0; i < 2; i++) {
            mockMvc.perform(post("/api/agent/chat")
                            .header("Authorization", account.bearer())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isOk());
        }

        assertThat(memosOf(account.id())).hasSize(1);
    }

    @Test
    @DisplayName("不同用户使用同一 requestId 互不影响（去重键是 userId + requestId）")
    void requestIdIsScopedPerUser() throws Exception {
        Account alice = registerNew();
        Account bob = registerNew();
        String shared = "req-shared-" + UUID.randomUUID();

        send(alice, "记一下明天办签证", shared);
        send(bob, "记一下明天办签证", shared);

        assertThat(memosOf(alice.id())).hasSize(1);
        assertThat(memosOf(bob.id())).hasSize(1);
    }
}