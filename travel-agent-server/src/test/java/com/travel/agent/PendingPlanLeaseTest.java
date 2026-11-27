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
import com.travel.agent.entity.PlanExecution;
import com.travel.agent.entity.PlanOutbox;
import com.travel.agent.repository.ItineraryItemRepository;
import com.travel.agent.repository.PendingPlanRepository;
import com.travel.agent.repository.PlanExecutionRepository;
import com.travel.agent.repository.PlanOutboxRepository;
import com.travel.agent.repository.TripRepository;
import com.travel.agent.support.IntegrationTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * P0：确认租约（claim token + 清扫）的行为契约。
 *
 * <p>覆盖三件事：
 * <ul>
 *   <li><b>令牌语义</b>：claim 发放令牌；CONFIRMING 期间第二次 claim 拿不到；错误令牌 release 无效；</li>
 *   <li><b>崩溃恢复</b>：claim 后进程崩溃（不 release）→ claimed_at 变旧 → 清扫回 PENDING → 可以再次确认；</li>
 *   <li><b>并发确认</b>：两个线程同时确认同一方案只成功一次，只创建一个行程。</li>
 * </ul>
 */
class PendingPlanLeaseTest extends IntegrationTestSupport {

    @Autowired
    private PendingPlanStore pendingPlans;
    @Autowired
    private PendingPlanRepository pendingPlanRepository;
    @Autowired
    private PlanConfirmationService planConfirmation;
    @Autowired
    private TripRepository tripRepository;
    @Autowired
    private ItineraryItemRepository itineraryItemRepository;
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
                        .name("租约测试行程")
                        .destination("上海")
                        .startDate(LocalDate.of(2027, 5, 1))
                        .days(3)
                        .budget(BigDecimal.valueOf(3000))
                        .preferences(List.of())
                        .build())
                .operations(List.of(PlanOperation.builder()
                        .type(PlanOperation.ADD).draft(item).detail("新增：外滩").build()))
                .reasoning("测试：确认租约")
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
        String planId = "plan-lease-" + userId + "-" + System.nanoTime();
        pendingPlans.put(new PendingPlanStore.PendingPlan(
                planId, userId, null, newTripPlan(planId), previewOf(planId), null, null, Instant.now()));
        return planId;
    }

    /** 模拟「claim 之后进程崩溃」：把领取时间改到很久以前（只改库里的租约时间，不 release） */
    private void ageClaim(String planId, long minutes) {
        PendingPlanRecord record = pendingPlanRepository.findById(planId).orElseThrow();
        record.setClaimedAt(LocalDateTime.now().minusMinutes(minutes));
        pendingPlanRepository.save(record);
    }

    // ==================== 令牌语义 ====================

    @Test
    @DisplayName("claim 发放令牌：CONFIRMING 期间不能再 claim，错误令牌 release 无效，正确令牌可释放")
    void claimIssuesTokenAndOnlyMatchingTokenCanRelease() throws Exception {
        Account account = registerNew();
        String planId = putPendingPlan(account.id());

        Optional<String> token = pendingPlans.claim(planId, account.id());
        assertThat(token).isPresent();
        assertThat(pendingPlans.claim(planId, account.id()))
                .as("同一方案在 CONFIRMING 期间不能再被 claim")
                .isEmpty();

        PendingPlanRecord claimed = pendingPlanRepository.findById(planId).orElseThrow();
        assertThat(claimed.getStatus()).isEqualTo(PendingPlanRecord.STATUS_CONFIRMING);
        assertThat(claimed.getClaimToken()).isEqualTo(token.get());
        assertThat(claimed.getClaimedAt()).isNotNull();

        assertThat(pendingPlans.release(planId, "not-my-token"))
                .as("令牌不匹配不能释放")
                .isFalse();
        assertThat(pendingPlanRepository.findById(planId).orElseThrow().getStatus())
                .isEqualTo(PendingPlanRecord.STATUS_CONFIRMING);

        assertThat(pendingPlans.release(planId, token.get())).isTrue();
        PendingPlanRecord released = pendingPlanRepository.findById(planId).orElseThrow();
        assertThat(released.getStatus()).isEqualTo(PendingPlanRecord.STATUS_PENDING);
        assertThat(released.getClaimToken()).isNull();
        assertThat(released.getClaimedAt()).isNull();
    }

    // ==================== 崩溃恢复 ====================

    @Test
    @DisplayName("崩溃模拟：claim 后不 release、等 claimed_at 变旧 → 清扫回 PENDING → 可再次确认")
    void staleClaimIsSweptAndPlanCanBeConfirmedAgain() throws Exception {
        Account account = registerNew();
        String planId = putPendingPlan(account.id());

        String abandonedToken = pendingPlans.claim(planId, account.id()).orElseThrow();
        // 崩溃：既没有 release，也没有提交；租约到期后必须由清扫任务恢复
        ageClaim(planId, 30);
        pendingPlans.sweepStale(LocalDateTime.now().minusMinutes(5));

        PendingPlanRecord swept = pendingPlanRepository.findById(planId).orElseThrow();
        assertThat(swept.getStatus())
                .as("过期 CONFIRMING 必须回到 PENDING")
                .isEqualTo(PendingPlanRecord.STATUS_PENDING);
        assertThat(swept.getClaimToken()).isNull();
        assertThat(swept.getClaimedAt()).isNull();
        assertThat(pendingPlans.release(planId, abandonedToken))
                .as("已被清扫的租约令牌不能再操作这一行")
                .isFalse();

        // 恢复后用户可以重新确认，并且只落一次库
        AgentDtos.AgentResult result = planConfirmation.confirm(planId, account.id());
        assertThat(result.getStatus()).isEqualTo(AgentDtos.STATUS_COMPLETED);
        assertThat(result.getTripId()).isNotNull();
        assertThat(tripRepository.findByUserIdOrderByCreatedAtDesc(account.id())).hasSize(1);
        assertThat(pendingPlanRepository.findById(planId))
                .as("提交栅栏已消费方案行")
                .isEmpty();
        assertThat(outboxRepository.findByPlanIdOrderByIdAsc(planId))
                .as("确认提交同事务写入三条 Outbox 事件")
                .hasSize(3);
    }

    // ==================== 并发确认 ====================

    @Test
    @DisplayName("两次并发 confirm：只成功一次，只创建一个行程与一条执行台账")
    void concurrentConfirmSucceedsExactlyOnce() throws Exception {
        Account account = registerNew();
        String planId = putPendingPlan(account.id());

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<String>> futures = new ArrayList<>();
        List<String> statuses = new ArrayList<>();
        try {
            for (int i = 0; i < 2; i++) {
                futures.add(pool.submit(() -> {
                    start.await();
                    try {
                        return planConfirmation.confirm(planId, account.id()).getStatus();
                    } catch (RuntimeException e) {
                        return "EXCEPTION:" + e.getClass().getSimpleName();
                    }
                }));
            }
            start.countDown();
            for (Future<String> future : futures) {
                statuses.add(future.get(30, TimeUnit.SECONDS));
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(statuses)
                .as("一个成功、一个明确业务失败（不是异常）")
                .containsExactlyInAnyOrder(AgentDtos.STATUS_COMPLETED, AgentDtos.STATUS_FAILED);
        assertThat(tripRepository.findByUserIdOrderByCreatedAtDesc(account.id()))
                .as("并发确认只能创建一个行程")
                .hasSize(1);
        Long tripId = tripRepository.findByUserIdOrderByCreatedAtDesc(account.id()).get(0).getId();
        assertThat(itineraryItemRepository.findByTripIdOrderByDayIndexAscStartTimeAsc(tripId))
                .as("计划条目只落一遍")
                .hasSize(1);
        assertThat(executionRepository.findByPlanId(planId)).isPresent();
        List<PlanOutbox> rows = outboxRepository.findByPlanIdOrderByIdAsc(planId);
        assertThat(rows).hasSize(3);
        assertThat(rows).allSatisfy(row ->
                assertThat(row.getStatus()).isEqualTo(PlanOutbox.STATUS_DONE));
        assertThat(executionRepository.findByPlanId(planId).orElseThrow().getStatus())
                .isEqualTo(PlanExecution.STATUS_SUCCEEDED);
    }
}