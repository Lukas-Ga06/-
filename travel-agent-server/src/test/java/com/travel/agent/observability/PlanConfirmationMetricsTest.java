package com.travel.agent.observability;

import com.travel.agent.agent.AgentPlan;
import com.travel.agent.agent.AgentStageTimer;
import com.travel.agent.agent.PendingPlanStore;
import com.travel.agent.agent.PlanConfirmationService;
import com.travel.agent.agent.PlanRevisionService;
import com.travel.agent.dto.AgentDtos;
import com.travel.agent.llm.LlmProperties;
import com.travel.agent.service.AgentTraceService;
import com.travel.agent.service.ConfirmCommitTx;
import com.travel.agent.service.OperationHistoryService;
import com.travel.agent.service.PlanExecutionService;
import com.travel.agent.service.TripService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 确认提交指标（T5）：{@code travel.confirm.outcome{result=success|conflict|failed}}。
 *
 * <p>分支语义必须与产品行为一致：
 * <ul>
 *   <li>success：行程已落库、方案被消费；</li>
 *   <li>conflict：并发抢占失败 / 内容指纹不符 / 行程版本已变（可重试，不是故障）；</li>
 *   <li>failed：方案不存在或过期 / 风险门拒绝 / 提交抛异常（要人看）。</li>
 * </ul>
 */
class PlanConfirmationMetricsTest {

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final TravelMetrics metrics = new TravelMetrics(registry, new LlmProperties());

    private final PendingPlanStore pendingPlans = mock(PendingPlanStore.class);
    private final ConfirmCommitTx confirmCommitTx = mock(ConfirmCommitTx.class);
    private final TripService tripService = mock(TripService.class);
    private final PlanRevisionService planRevisionService = mock(PlanRevisionService.class);
    private final OperationHistoryService operationHistoryService = mock(OperationHistoryService.class);
    private final AgentStageTimer stageTimer = new AgentStageTimer(registry);
    private final AgentTraceService traceService = mock(AgentTraceService.class);
    private final PlanExecutionService executionService = mock(PlanExecutionService.class);

    private PlanConfirmationService service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        service = new PlanConfirmationService(pendingPlans, confirmCommitTx, tripService, planRevisionService,
                operationHistoryService, stageTimer, traceService, executionService, metrics);
        // 追踪服务直通：确认指标测试不关心 run/task 落库
        when(traceService.task(any(), any(), any(), any(), any(), any()))
                .thenAnswer(invocation -> ((Supplier<?>) invocation.getArgument(4)).get());
    }

    /** 未创建过的 tag 组合按 0 处理（registry.get 会抛 MeterNotFoundException，不适合断言"没记"） */
    private double confirmCount(String result) {
        return registry.find(TravelMetrics.CONFIRM_OUTCOME).tag("result", result).counters().stream()
                .mapToDouble(io.micrometer.core.instrument.Counter::count).sum();
    }

    private PendingPlanStore.PendingPlan pending(String planId, Long userId, String contentHash) {
        AgentPlan plan = AgentPlan.builder().planId(planId).operations(List.of()).build();
        return new PendingPlanStore.PendingPlan(planId, userId, null, plan, null, null, contentHash,
                Instant.now());
    }

    @Test
    @DisplayName("成功提交 → result=success")
    void successPathCountsSuccess() {
        when(pendingPlans.peek("p-ok", 7L)).thenReturn(pending("p-ok", 7L, null));
        when(pendingPlans.claim("p-ok", 7L)).thenReturn(Optional.of("token-ok"));
        when(confirmCommitTx.commit(any(), any(), any(), any())).thenReturn(AgentDtos.AgentResult.builder()
                .planId("p-ok").tripId(11L).status(AgentDtos.STATUS_COMPLETED).message("已提交").build());

        AgentDtos.AgentResult result = service.confirm("p-ok", 7L);

        assertThat(result.getStatus()).isEqualTo(AgentDtos.STATUS_COMPLETED);
        assertThat(confirmCount("success")).isEqualTo(1.0);
        assertThat(confirmCount("conflict")).isZero();
        assertThat(confirmCount("failed")).isZero();
    }

    @Test
    @DisplayName("并发抢占失败（方案正在处理/已被确认）→ result=conflict")
    void claimConflictCountsConflict() {
        when(pendingPlans.peek("p-race", 7L)).thenReturn(pending("p-race", 7L, null));
        when(pendingPlans.claim("p-race", 7L)).thenReturn(Optional.empty());

        AgentDtos.AgentResult result = service.confirm("p-race", 7L);

        assertThat(result.getStatus()).isEqualTo(AgentDtos.STATUS_FAILED);
        assertThat(confirmCount("conflict")).isEqualTo(1.0);
        assertThat(confirmCount("success")).isZero();
    }

    @Test
    @DisplayName("内容指纹不符（用户确认的不是看到的内容）→ result=conflict，且方案不被消费")
    void contentHashConflictCountsConflict() {
        when(pendingPlans.peek("p-hash", 7L)).thenReturn(pending("p-hash", 7L, "stored-hash"));
        when(pendingPlans.claim("p-hash", 7L)).thenReturn(Optional.of("token-hash"));

        AgentDtos.AgentResult result = service.confirm("p-hash", 7L, "another-hash");

        assertThat(result.getStatus()).isEqualTo(AgentDtos.STATUS_FAILED);
        assertThat(confirmCount("conflict")).isEqualTo(1.0);
        verify(pendingPlans).release("p-hash", "token-hash");
    }

    @Test
    @DisplayName("方案不存在/已过期 → result=failed")
    void missingPlanCountsFailed() {
        when(pendingPlans.peek("p-gone", 7L)).thenReturn(null);

        AgentDtos.AgentResult result = service.confirm("p-gone", 7L);

        assertThat(result.getStatus()).isEqualTo(AgentDtos.STATUS_FAILED);
        assertThat(confirmCount("failed")).isEqualTo(1.0);
        assertThat(confirmCount("conflict")).isZero();
    }

    @Test
    @DisplayName("提交抛异常 → result=failed 且异常继续上抛（不吞业务错误）")
    void commitExceptionCountsFailedAndRethrows() {
        when(pendingPlans.peek("p-boom", 7L)).thenReturn(pending("p-boom", 7L, null));
        when(pendingPlans.claim("p-boom", 7L)).thenReturn(Optional.of("token-boom"));
        when(confirmCommitTx.commit(any(), any(), any(), any())).thenThrow(new IllegalStateException("db down"));

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.confirm("p-boom", 7L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("db down");

        assertThat(confirmCount("failed")).isEqualTo(1.0);
        verify(pendingPlans).release("p-boom", "token-boom");
    }
}