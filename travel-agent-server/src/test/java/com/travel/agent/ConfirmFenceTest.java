package com.travel.agent;

import com.travel.agent.agent.AgentPlan;
import com.travel.agent.agent.IntentResult;
import com.travel.agent.agent.PendingPlanStore;
import com.travel.agent.agent.PlanConfirmationService;
import com.travel.agent.agent.PlanOperation;
import com.travel.agent.agent.TripDraft;
import com.travel.agent.dto.AgentDtos;
import com.travel.agent.entity.ItineraryItem;
import com.travel.agent.entity.PendingPlanRecord;
import com.travel.agent.repository.PendingPlanRepository;
import com.travel.agent.repository.PlanExecutionRepository;
import com.travel.agent.repository.PlanOutboxRepository;
import com.travel.agent.repository.TripRepository;
import com.travel.agent.service.ConfirmCommitTx;
import com.travel.agent.support.IntegrationTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * P0：确认提交的租约栅栏（fence）。
 *
 * <p>不变量：提交事务内 {@code deleteClaimed(planId, token)} 必须影响 1 行。
 * 若方案行在提交前已被清扫回 PENDING、或被别的请求重新抢走（令牌不同），
 * 栅栏拿到 0 行 → 抛异常回滚整个提交事务：行程 / 条目 / 操作历史 / 执行台账 / Outbox 一条都不留。
 *
 * <p>这是「租约过期后旧持有者仍能重复落库」的最终防线——清扫把行放回可确认状态只是让用户能重试，
 * 不负责阻止旧请求落库。
 */
class ConfirmFenceTest extends IntegrationTestSupport {

    @Autowired
    private PendingPlanStore pendingPlans;
    @Autowired
    private PendingPlanRepository pendingPlanRepository;
    @Autowired
    private ConfirmCommitTx confirmCommitTx;
    @Autowired
    private PlanConfirmationService planConfirmation;
    @Autowired
    private TripRepository tripRepository;
    @Autowired
    private PlanExecutionRepository executionRepository;
    @Autowired
    private PlanOutboxRepository outboxRepository;

    // ==================== 测试数据 ====================

    private AgentPlan newTripPlan(String planId) {
        ItineraryItem item = ItineraryItem.builder()
                .dayIndex(1)
                .itemType(ItineraryItem.TYPE_ATTRACTION)
                .title("外滩")
                .cost(BigDecimal.valueOf(60))
                .build();
        return AgentPlan.builder()
                .planId(planId)
                .intent(IntentResult.PLAN_TRIP)
                .tripId(null)
                .tripDraft(TripDraft.builder()
                        .name("栅栏测试行程")
                        .destination("上海")
                        .startDate(LocalDate.of(2027, 5, 1))
                        .days(3)
                        .budget(BigDecimal.valueOf(3000))
                        .preferences(List.of())
                        .build())
                .operations(List.of(PlanOperation.builder()
                        .type(PlanOperation.ADD).draft(item).detail("新增：外滩").build()))
                .reasoning("测试：提交栅栏")
                .build();
    }

    private AgentDtos.AgentResult previewOf(String planId) {
        return AgentDtos.AgentResult.builder()
                .planId(planId)
                .status(AgentDtos.STATUS_NEED_CONFIRMATION)
                .message("已生成方案，请确认")
                .confirmation(AgentDtos.ConfirmationRequest.builder()
                        .planId(planId).summary("新增 1 项").riskLevel("MEDIUM").build())
                .build();
    }

    private String putPendingPlan(Long userId) {
        String planId = "plan-fence-" + userId + "-" + System.nanoTime();
        pendingPlans.put(new PendingPlanStore.PendingPlan(
                planId, userId, null, newTripPlan(planId), previewOf(planId), null, null, Instant.now()));
        return planId;
    }

    /**
     * 制造「旧租约仍想提交」的场景：
     * claim 拿到令牌 → 领取时间改旧 → 清扫把行放回 PENDING（模拟另一个请求/清扫任务已经接管）。
     */
    private String claimThenSweep(String planId, Long userId) {
        String token = pendingPlans.claim(planId, userId).orElseThrow();
        PendingPlanRecord record = pendingPlanRepository.findById(planId).orElseThrow();
        record.setClaimedAt(LocalDateTime.now().minusMinutes(30));
        pendingPlanRepository.save(record);
        pendingPlans.sweepStale(LocalDateTime.now().minusMinutes(5));
        assertThat(pendingPlanRepository.findById(planId).orElseThrow().getStatus())
                .as("清扫后方案回到 PENDING，可被其它请求接管")
                .isEqualTo(PendingPlanRecord.STATUS_PENDING);
        return token;
    }

    // ==================== 栅栏 ====================

    @Test
    @DisplayName("栅栏：claim→sweepStale→用原令牌提交，提交事务整体回滚（行程/台账/Outbox 均无新增）")
    void fenceRollsBackCommitAfterLeaseIsSwept() throws Exception {
        Account account = registerNew();
        String planId = putPendingPlan(account.id());
        String staleToken = claimThenSweep(planId, account.id());
        PendingPlanStore.PendingPlan pending = pendingPlans.peek(planId, account.id());
        assertThat(pending).isNotNull();

        assertThatThrownBy(() -> confirmCommitTx.commit(pending.plan(), account.id(), planId, staleToken))
                .as("失效租约上的提交必须抛异常")
                .isInstanceOf(ConfirmCommitTx.LeaseLostException.class);

        assertThat(tripRepository.findByUserIdOrderByCreatedAtDesc(account.id()))
                .as("回滚后不应有任何行程")
                .isEmpty();
        assertThat(executionRepository.findByPlanId(planId)).isEmpty();
        assertThat(outboxRepository.findByPlanIdOrderByIdAsc(planId))
                .as("回滚后不应残留 Outbox 事件")
                .isEmpty();
        assertThat(pendingPlanRepository.findById(planId))
                .as("方案行未被消费，用户仍可重试")
                .isPresent();
    }

    @Test
    @DisplayName("栅栏拦截后：方案已被清扫回 PENDING，重新确认仍然只落一次库")
    void planCanBeConfirmedAgainAfterFenceRejectedStaleCommit() throws Exception {
        Account account = registerNew();
        String planId = putPendingPlan(account.id());
        String staleToken = claimThenSweep(planId, account.id());
        PendingPlanStore.PendingPlan pending = pendingPlans.peek(planId, account.id());

        assertThatThrownBy(() -> confirmCommitTx.commit(pending.plan(), account.id(), planId, staleToken))
                .isInstanceOf(ConfirmCommitTx.LeaseLostException.class);

        AgentDtos.AgentResult result = planConfirmation.confirm(planId, account.id());
        assertThat(result.getStatus()).isEqualTo(AgentDtos.STATUS_COMPLETED);
        assertThat(tripRepository.findByUserIdOrderByCreatedAtDesc(account.id()))
                .as("成功确认只创建一个行程")
                .hasSize(1);
        assertThat(executionRepository.findByPlanId(planId)).isPresent();
        assertThat(outboxRepository.findByPlanIdOrderByIdAsc(planId)).hasSize(3);
        assertThat(pendingPlanRepository.findById(planId)).isEmpty();
    }
}