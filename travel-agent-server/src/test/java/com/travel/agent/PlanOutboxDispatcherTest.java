package com.travel.agent;

import com.travel.agent.dto.AgentDtos;
import com.travel.agent.dto.TripDtos;
import com.travel.agent.entity.PlanExecution;
import com.travel.agent.entity.PlanOutbox;
import com.travel.agent.entity.Trip;
import com.travel.agent.repository.PlanExecutionRepository;
import com.travel.agent.repository.PlanOutboxRepository;
import com.travel.agent.repository.ScheduleItemRepository;
import com.travel.agent.service.PlanExecutionService;
import com.travel.agent.service.PlanOutboxDispatcher;
import com.travel.agent.service.PlanOutboxService;
import com.travel.agent.service.TripService;
import com.travel.agent.support.IntegrationTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * P0：事务性 Outbox 投递器。
 *
 * <p>覆盖三条关键路径：
 * <ul>
 *   <li><b>崩溃恢复 / 迟到补建</b>：「行程已提交 + 3 条 PENDING + 无执行台账」时，
 *       投递器必须补建台账与同步日程，且重复投递不重复创建；</li>
 *   <li><b>失败退避</b>：模块失败时 attempts+1、next_attempt_at 后移，不会在退避窗口内被立即重投；</li>
 *   <li><b>DISPATCHING 租约恢复</b>：投递器领走行后进程崩溃（行停在 DISPATCHING），
 *       超过租约后由下一轮恢复成 PENDING 并 attempts+1 后补投。</li>
 * </ul>
 */
class PlanOutboxDispatcherTest extends IntegrationTestSupport {

    @Autowired
    private PlanOutboxDispatcher dispatcher;
    @Autowired
    private PlanOutboxService outboxService;
    @Autowired
    private PlanOutboxRepository outboxRepository;
    @Autowired
    private PlanExecutionRepository executionRepository;
    @Autowired
    private ScheduleItemRepository scheduleItemRepository;
    @Autowired
    private PlanExecutionService executionService;
    @Autowired
    private TripService tripService;

    /** 三天行程（日历模块需要出发日期才能生成时间块） */
    private Trip newTrip(Account account) {
        TripDtos.TripRequest request = new TripDtos.TripRequest();
        request.setName("Outbox 测试行程");
        request.setDestination("上海");
        request.setStartDate(LocalDate.now().plusDays(10).toString());
        request.setEndDate(LocalDate.now().plusDays(12).toString());
        request.setBudget(BigDecimal.valueOf(3000));
        return tripService.create(account.id(), request);
    }

    private List<PlanOutbox> rows(String planId) {
        return outboxRepository.findByPlanIdOrderByIdAsc(planId);
    }

    private boolean allDone(String planId) {
        return rows(planId).stream().allMatch(row -> PlanOutbox.STATUS_DONE.equals(row.getStatus()));
    }

    private boolean allAttempted(String planId) {
        return rows(planId).stream().allMatch(row -> row.getAttempts() >= 1);
    }

    /** 反复驱动投递直到该 plan 的行全部 DONE（后台调度器也可能同时投递，因此以最终状态为准） */
    private void dispatchUntilDone(String planId) {
        for (int i = 0; i < 40 && !allDone(planId); i++) {
            dispatcher.dispatchDue();
            sleep(50L);
        }
        assertThat(allDone(planId)).as("Outbox 行应在若干轮投递后全部 DONE: planId=" + planId).isTrue();
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // ==================== 崩溃恢复 / 迟到补建 ====================

    @Test
    @DisplayName("崩溃恢复：行程已提交 + 3 条 PENDING + 无台账 → 投递器补建台账与日程，重复投递不重复创建")
    void dispatcherRebuildsLedgerAfterCrash() throws Exception {
        Account account = registerNew();
        Trip trip = newTrip(account);
        String planId = "plan-outbox-late-" + System.nanoTime();
        assertThat(outboxService.enqueueAll(planId, account.id(), trip.getId())).isEqualTo(3);

        // 先把到期时间推到未来：让「已提交但同步未跑」的状态可以被稳定断言，
        // 而不是靠时序赌后台调度器没有抢先一步。
        for (PlanOutbox row : rows(planId)) {
            row.setNextAttemptAt(LocalDateTime.now().plusMinutes(5));
            outboxRepository.save(row);
        }
        assertThat(executionRepository.findByPlanId(planId))
                .as("模拟崩溃现场：行程已提交，但没有任何执行台账")
                .isEmpty();

        // 到期后由投递器补投（等价于进程重启后第一轮投递）
        for (PlanOutbox row : rows(planId)) {
            row.setNextAttemptAt(LocalDateTime.now().minusSeconds(1));
            outboxRepository.save(row);
        }
        dispatchUntilDone(planId);

        assertThat(rows(planId)).hasSize(3).allSatisfy(row ->
                assertThat(row.getStatus()).isEqualTo(PlanOutbox.STATUS_DONE));

        PlanExecution execution = executionRepository.findByPlanId(planId).orElseThrow();
        assertThat(execution.getStatus()).isEqualTo(PlanExecution.STATUS_SUCCEEDED);
        AgentDtos.ExecutionSummary summary = executionService.find(execution.getExecutionId(), account.id());
        assertThat(summary.getModules())
                .extracting(AgentDtos.ExecutionSummary.ExecutionModule::getModule)
                .containsExactly("calendar", "budget", "footprint");
        assertThat(summary.getModules()).allMatch(module -> "SUCCEEDED".equals(module.getStatus()));
        for (int day = 1; day <= 3; day++) {
            assertThat(scheduleItemRepository.findBySourceKey("plan-sync:" + planId + ":day:" + day))
                    .as("迟到补建也必须补齐第 " + day + " 天的日程")
                    .isPresent();
        }

        // 再跑一轮：没有待投递行，不重复创建
        dispatcher.dispatchDue();
        assertThat(executionRepository.findByPlanId(planId).orElseThrow().getId())
                .isEqualTo(execution.getId());
        long synced = scheduleItemRepository.findByUserIdOrderByDateAscTimeAscIdAsc(account.id()).stream()
                .filter(item -> item.getSourceKey() != null
                        && item.getSourceKey().startsWith("plan-sync:" + planId))
                .count();
        assertThat(synced).as("重复投递不应重复创建日程").isEqualTo(3);
    }

    // ==================== 失败退避 ====================

    @Test
    @DisplayName("失败退避：模块失败 → attempts+1、next_attempt_at 后移，退避窗口内不重复投递")
    void failedModuleBacksOffInsteadOfHotLooping() throws Exception {
        Account account = registerNew();
        long missingTripId = 900_000_000L + Math.floorMod(System.nanoTime(), 10_000);
        String planId = "plan-outbox-fail-" + System.nanoTime();
        assertThat(outboxService.enqueueAll(planId, account.id(), missingTripId)).isEqualTo(3);

        for (int i = 0; i < 40 && !allAttempted(planId); i++) {
            dispatcher.dispatchDue();
            sleep(50L);
        }

        LocalDateTime now = LocalDateTime.now();
        List<PlanOutbox> failed = rows(planId);
        assertThat(failed).hasSize(3);
        assertThat(failed).allSatisfy(row -> {
            assertThat(row.getAttempts()).as("失败一次 attempts=1").isEqualTo(1);
            assertThat(row.getStatus()).isEqualTo(PlanOutbox.STATUS_PENDING);
            assertThat(row.getNextAttemptAt()).as("退避后的下次投递时间必须在未来").isAfter(now);
            assertThat(row.getLastError()).contains("行程不存在");
        });

        // 退避窗口内再跑一轮：不会重复投递（attempts 不变）
        dispatcher.dispatchDue();
        assertThat(rows(planId)).allSatisfy(row -> assertThat(row.getAttempts()).isEqualTo(1));
    }

    // ==================== DISPATCHING 租约恢复 ====================

    @Test
    @DisplayName("DISPATCHING 崩溃恢复：领取超租约 → 改回 PENDING 且 attempts+1 → 补投成功置 DONE")
    void staleDispatchingRowsAreRecoveredAndDispatched() throws Exception {
        Account account = registerNew();
        Trip trip = newTrip(account);
        String planId = "plan-outbox-stale-" + System.nanoTime();
        assertThat(outboxService.enqueueAll(planId, account.id(), trip.getId())).isEqualTo(3);

        // 模拟投递器领走三行后进程崩溃：先推远到期时间挡住后台调度器，再把行改成旧的 DISPATCHING
        for (PlanOutbox row : rows(planId)) {
            row.setNextAttemptAt(LocalDateTime.now().plusMinutes(5));
            row.setStatus(PlanOutbox.STATUS_DISPATCHING);
            row.setClaimedAt(LocalDateTime.now().minusMinutes(30));
            outboxRepository.save(row);
        }

        dispatchUntilDone(planId);

        assertThat(rows(planId)).allSatisfy(row -> {
            assertThat(row.getStatus()).isEqualTo(PlanOutbox.STATUS_DONE);
            assertThat(row.getAttempts()).as("租约恢复记一次消耗").isEqualTo(1);
        });
        assertThat(executionRepository.findByPlanId(planId)).isPresent();
        assertThat(scheduleItemRepository.findBySourceKey("plan-sync:" + planId + ":day:1")).isPresent();
    }
}