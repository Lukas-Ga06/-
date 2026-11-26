package com.travel.agent;

import com.travel.agent.agent.AgentSpend;
import com.travel.agent.entity.AgentRunSpend;
import com.travel.agent.repository.AgentRunSpendRepository;
import com.travel.agent.service.AgentRunSpendService;
import com.travel.agent.service.AgentTraceService;
import com.travel.agent.support.IntegrationTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 第二阶段：每轮花费落库（V29）的持久化契约。
 *
 * <p>覆盖：快照字段一一落库、同一 run 幂等覆盖、空参数不写、保留期清理。
 * 端到端「真实请求后能查到花费行」见 {@code AgentSpendIntegrationTest} 与
 * {@code AgentTraceApiTest}（详情接口的 spend 字段）。
 */
class AgentRunSpendTest extends IntegrationTestSupport {

    @Autowired
    private AgentRunSpendService spendService;

    @Autowired
    private AgentRunSpendRepository repository;

    @Autowired
    private AgentTraceService traceService;

    private static String newRunId() {
        return "run-spend-" + UUID.randomUUID();
    }

    @Test
    @DisplayName("快照字段完整落库：计数/token/费用/触顶项与运行 id、用户 id")
    void recordsSnapshotFields() {
        String runId = newRunId();
        spendService.record(runId, 42L, new AgentSpend.Snapshot(
                3, 2, 1, 120, 80, 0.001234d, AgentSpend.Limit.TOOL_CALLS));

        AgentRunSpend row = repository.findByRunId(runId).orElseThrow();
        assertThat(row.getUserId()).isEqualTo(42L);
        assertThat(row.getLlmCalls()).isEqualTo(3);
        assertThat(row.getToolCalls()).isEqualTo(2);
        assertThat(row.getRetries()).isEqualTo(1);
        assertThat(row.getPromptTokens()).isEqualTo(120L);
        assertThat(row.getCompletionTokens()).isEqualTo(80L);
        assertThat(row.totalTokens()).isEqualTo(200L);
        assertThat(row.getCostUsd()).isEqualByComparingTo(new BigDecimal("0.001234"));
        assertThat(row.getExhausted()).isEqualTo("tool_calls");
        assertThat(row.getCreatedAt()).isNotNull();
    }

    @Test
    @DisplayName("同一 run 重复收尾只保留一条，覆盖为最新快照（不重复翻倍）")
    void recordIsIdempotentPerRun() {
        String runId = newRunId();
        spendService.record(runId, 7L, new AgentSpend.Snapshot(
                1, 1, 0, 10, 10, 0.0001d, null));
        spendService.record(runId, 7L, new AgentSpend.Snapshot(
                4, 5, 2, 400, 200, 0.004d, AgentSpend.Limit.TOKENS));

        assertThat(repository.findByRunId(runId)).hasValueSatisfying(row -> {
            assertThat(row.getLlmCalls()).isEqualTo(4);
            assertThat(row.getToolCalls()).isEqualTo(5);
            assertThat(row.getRetries()).isEqualTo(2);
            assertThat(row.getPromptTokens()).isEqualTo(400L);
            assertThat(row.getCompletionTokens()).isEqualTo(200L);
            assertThat(row.getExhausted()).isEqualTo("tokens");
        });
    }

    @Test
    @DisplayName("空 runId / 空快照是空操作：不写行、不抛异常")
    void skipsBlankInputs() {
        spendService.record(null, 1L, new AgentSpend.Snapshot(1, 1, 0, 1, 1, 0d, null));
        spendService.record("  ", 1L, new AgentSpend.Snapshot(1, 1, 0, 1, 1, 0d, null));
        spendService.record(newRunId(), 1L, null);
        assertThat(spendService.findByRunId("  ")).isEmpty();
    }

    @Test
    @DisplayName("保留期清理：超过 14 天的花费行随 Agent 追踪一起删除，新行保留")
    void purgeExpiredRemovesOnlyOldSpendRows() {
        String oldRunId = newRunId();
        String freshRunId = newRunId();
        AgentRunSpend old = AgentRunSpend.builder()
                .runId(oldRunId).userId(1L).costUsd(BigDecimal.ZERO)
                .createdAt(LocalDateTime.now().minusDays(AgentTraceService.RETENTION_DAYS + 1))
                .build();
        repository.save(old);
        spendService.record(freshRunId, 1L, new AgentSpend.Snapshot(1, 0, 0, 1, 1, 0d, null));

        traceService.purgeExpired();

        assertThat(repository.findByRunId(oldRunId)).as("超过保留期的花费行应被清理").isEmpty();
        assertThat(repository.findByRunId(freshRunId)).as("保留期内不应误删").isPresent();
    }
}