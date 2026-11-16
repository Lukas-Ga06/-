package com.travel.agent;

import com.travel.agent.agent.AgentContext;
import com.travel.agent.agent.AgentEngine;
import com.travel.agent.dto.AgentDtos;
import com.travel.agent.dto.TripDtos;
import com.travel.agent.entity.Trip;
import com.travel.agent.repository.ItineraryItemRepository;
import com.travel.agent.repository.OperationHistoryRepository;
import com.travel.agent.repository.TripRepository;
import com.travel.agent.service.ItineraryService;
import com.travel.agent.service.TripService;
import com.travel.agent.support.IntegrationTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 一次性并发探针 + 严格回归（审计用）。
 *
 * 目的：用实测数字回答 arch-review.md P0#1 是「理论风险」还是「已复现缺陷」：
 * 两个线程同时确认【同一个】待确认方案（新建行程场景，planRevision 为 null），
 * 记录两次返回 status、最终行程数、itinerary 条目数、operation_history 增量。
 *
 * 背景（修复前的代码事实）：
 * - AgentEngine.confirm：pendingPlans.peek → 版本校验 → executorAgent.commit → pendingPlans.remove，
 *   四步之间没有任何锁/条件更新，PendingPlanRecord 也没有 @Version；
 * - 新建行程的 planRevision 为 null，因此 confirm 的指纹校验分支被整体跳过；
 * - trip/itinerary_item/operation_history 均无唯一约束兜底。
 * 结果：5/5 轮双落库（duplicatedRounds=5）。
 *
 * 修复后（batch B）：confirm 先用 pending_plan.status 的条件更新抢所有权
 * （PENDING → CONFIRMING，rowcount=1 才允许继续），后到请求返回明确的业务失败；
 * 两个记录型探针保留作趋势观测，严格断言用例 concurrentConfirmCreatesExactlyOneTrip 已启用。
 * 验收硬指标：SUMMARY 输出 duplicatedRounds=0。
 */
class ConcurrentConfirmProbeTest extends IntegrationTestSupport {

    /** 探针轮数：自然竞态窗口与调度有关，多轮统计更可信 */
    private static final int ROUNDS = 5;

    @Autowired
    private AgentEngine agentEngine;

    @Autowired
    private TripService tripService;

    @Autowired
    private ItineraryService itineraryService;

    @Autowired
    private TripRepository tripRepository;

    @Autowired
    private ItineraryItemRepository itineraryItemRepository;

    @Autowired
    private OperationHistoryRepository operationHistoryRepository;

    /** 用真实 AgentEngine 走规则引擎生成一份「新建行程」待确认方案（测试 profile 关闭了 LLM） */
    private AgentDtos.AgentResult askForNewTripPlan(Account account) {
        AgentContext ctx = new AgentContext();
        ctx.setUserId(account.id());
        ctx.setUsername(account.username());
        ctx.setSessionId(UUID.randomUUID().toString());
        ctx.setMessage("帮我规划上海三日游，预算3000元");
        return agentEngine.handle(ctx);
    }

    /** 用真实 AgentEngine 走规则引擎生成一份「改已有行程」待确认方案（带 tripId，才有 revision 校验） */
    private AgentDtos.AgentResult askForExistingTripPlan(Account account, Trip trip) {
        AgentContext ctx = new AgentContext();
        ctx.setUserId(account.id());
        ctx.setUsername(account.username());
        ctx.setSessionId(UUID.randomUUID().toString());
        ctx.setTripId(trip.getId());
        ctx.setMessage("帮我重新规划上海三日游，预算3000元");
        return agentEngine.handle(ctx);
    }

    private Trip newTrip(long userId) {
        TripDtos.TripRequest request = new TripDtos.TripRequest();
        request.setName("并发探针行程");
        request.setDestination("上海");
        request.setBudget(java.math.BigDecimal.valueOf(3000));
        return tripService.create(userId, request);
    }

    private record Outcome(String status, String message, String error) {
    }

    /** confirm 可能抛异常（如 H2 锁超时），捕获为 EXCEPTION 结果，避免探针被单轮异常打断 */
    private Outcome confirmSafely(String planId, long userId) {
        try {
            AgentDtos.AgentResult result = agentEngine.confirm(planId, userId);
            return new Outcome(result.getStatus(), result.getMessage(), null);
        } catch (Exception e) {
            return new Outcome("EXCEPTION", null, e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    @Test
    @DisplayName("并发探针：两线程同时确认同一 plan，记录实际落库数字")
    void probeConcurrentConfirm() throws Exception {
        int duplicatedRounds = 0;
        StringBuilder detail = new StringBuilder();

        for (int round = 1; round <= ROUNDS; round++) {
            Account account = registerNew();
            AgentDtos.AgentResult preview = askForNewTripPlan(account);
            assertThat(preview.getStatus())
                    .as("规则引擎应产出待确认方案")
                    .isEqualTo(AgentDtos.STATUS_NEED_CONFIRMATION);
            String planId = preview.getConfirmation().getPlanId();
            assertThat(planId).isNotBlank();

            long historyBefore = operationHistoryRepository.count();
            ExecutorService pool = Executors.newFixedThreadPool(2);
            CountDownLatch ready = new CountDownLatch(2);
            CountDownLatch start = new CountDownLatch(1);
            List<Future<Outcome>> futures = new ArrayList<>();
            try {
                for (int i = 0; i < 2; i++) {
                    futures.add(pool.submit(() -> {
                        ready.countDown();
                        start.await();
                        return confirmSafely(planId, account.id());
                    }));
                }
                assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
                start.countDown();

                Outcome first = futures.get(0).get(30, TimeUnit.SECONDS);
                Outcome second = futures.get(1).get(30, TimeUnit.SECONDS);

                List<Trip> trips = tripRepository.findByUserIdOrderByCreatedAtDesc(account.id());
                int itemCount = trips.stream()
                        .mapToInt(t -> itineraryItemRepository
                                .findByTripIdOrderByDayIndexAscStartTimeAsc(t.getId()).size())
                        .sum();
                long historyDelta = operationHistoryRepository.count() - historyBefore;
                boolean duplicated = trips.size() > 1;
                if (duplicated) {
                    duplicatedRounds++;
                }

                String line = "round=" + round
                        + " r1=" + first.status() + " r2=" + second.status()
                        + " trips=" + trips.size() + " items=" + itemCount
                        + " historyDelta=" + historyDelta
                        + (first.error() == null ? "" : " e1=" + first.error())
                        + (second.error() == null ? "" : " e2=" + second.error());
                detail.append(line).append('\n');
                System.out.println("[CONCURRENT-CONFIRM-PROBE] " + line);
            } finally {
                pool.shutdownNow();
            }
        }

        System.out.println("[CONCURRENT-CONFIRM-PROBE] SUMMARY rounds=" + ROUNDS
                + " duplicatedRounds=" + duplicatedRounds);
        System.out.println("[CONCURRENT-CONFIRM-PROBE] DETAIL\n" + detail);
        assertThat(detail.toString()).isNotEmpty();
    }

    @Test
    @DisplayName("并发探针：两线程同时确认同一「改已有行程」plan，看同批 ADD 是否落两遍")
    void probeConcurrentConfirmOnExistingTrip() throws Exception {
        int duplicatedRounds = 0;
        StringBuilder detail = new StringBuilder();

        for (int round = 1; round <= ROUNDS; round++) {
            Account account = registerNew();
            Trip trip = newTrip(account.id());
            AgentDtos.AgentResult preview = askForExistingTripPlan(account, trip);
            assertThat(preview.getStatus())
                    .as("规则引擎应产出待确认方案")
                    .isEqualTo(AgentDtos.STATUS_NEED_CONFIRMATION);
            String planId = preview.getConfirmation().getPlanId();
            int itemsBefore = itineraryService.listEntities(trip.getId()).size();
            long historyBefore = operationHistoryRepository.count();

            ExecutorService pool = Executors.newFixedThreadPool(2);
            CountDownLatch ready = new CountDownLatch(2);
            CountDownLatch start = new CountDownLatch(1);
            List<Future<Outcome>> futures = new ArrayList<>();
            try {
                for (int i = 0; i < 2; i++) {
                    futures.add(pool.submit(() -> {
                        ready.countDown();
                        start.await();
                        return confirmSafely(planId, account.id());
                    }));
                }
                assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
                start.countDown();

                Outcome first = futures.get(0).get(30, TimeUnit.SECONDS);
                Outcome second = futures.get(1).get(30, TimeUnit.SECONDS);

                int itemsAfter = itineraryService.listEntities(trip.getId()).size();
                long historyDelta = operationHistoryRepository.count() - historyBefore;
                // 每次成功 commit 都会写 1 条 operation_history：增量 2 = 同一批 ADD 被应用两遍
                boolean duplicated = historyDelta >= 2;
                if (duplicated) {
                    duplicatedRounds++;
                }

                String line = "round=" + round
                        + " r1=" + first.status() + " r2=" + second.status()
                        + " tripCount=" + tripRepository.findByUserIdOrderByCreatedAtDesc(account.id()).size()
                        + " items " + itemsBefore + "->" + itemsAfter
                        + " historyDelta=" + historyDelta
                        + (first.error() == null ? "" : " e1=" + first.error())
                        + (second.error() == null ? "" : " e2=" + second.error());
                detail.append(line).append('\n');
                System.out.println("[CONCURRENT-CONFIRM-EXISTING-PROBE] " + line);
            } finally {
                pool.shutdownNow();
            }
        }

        System.out.println("[CONCURRENT-CONFIRM-EXISTING-PROBE] SUMMARY rounds=" + ROUNDS
                + " duplicatedRounds=" + duplicatedRounds);
        System.out.println("[CONCURRENT-CONFIRM-EXISTING-PROBE] DETAIL\n" + detail);
        assertThat(detail.toString()).isNotEmpty();
    }

    /**
     * 严格回归断言（P0-1 修复后启用）。
     * 修复方式：confirm 先 peek，再用 pending_plan.status 的条件更新抢所有权
     * （PENDING → CONFIRMING，rowcount=1 才继续提交），后到请求拿到 0 行直接返回业务失败；
     * 实体另有 @Version 兜底。修复前本用例 5/5 轮出现两个行程，故当时 @Disabled。
     */
    @Test
    @DisplayName("并发确认同一新建行程 plan：只允许创建一个行程、一条操作历史")
    void concurrentConfirmCreatesExactlyOneTrip() throws Exception {
        Account account = registerNew();
        AgentDtos.AgentResult preview = askForNewTripPlan(account);
        assertThat(preview.getStatus()).isEqualTo(AgentDtos.STATUS_NEED_CONFIRMATION);
        String planId = preview.getConfirmation().getPlanId();
        assertThat(planId).isNotBlank();
        // 计划要新增的条目数（约 15 条，随规划模板变化，因此从 diff 推算而不是写死）
        int expectedItems = preview.getDiff() == null ? 0
                : (int) preview.getDiff().getChanges().stream()
                        .filter(c -> "ADD".equals(c.getType())).count();
        assertThat(expectedItems).as("规则引擎应产出新增条目").isPositive();
        long historyBefore = operationHistoryRepository.count();

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Outcome>> futures = new ArrayList<>();
        Outcome first;
        Outcome second;
        try {
            for (int i = 0; i < 2; i++) {
                futures.add(pool.submit(() -> {
                    start.await();
                    return confirmSafely(planId, account.id());
                }));
            }
            start.countDown();
            // 必须等两个确认都返回（含提交事务）再关池，否则 shutdownNow 会打断尚未完成的提交
            first = futures.get(0).get(30, TimeUnit.SECONDS);
            second = futures.get(1).get(30, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }

        // 断言 1：同一待确认方案被并发确认后只创建一个行程
        List<Trip> trips = tripRepository.findByUserIdOrderByCreatedAtDesc(account.id());
        assertThat(trips).as("并发确认后只应创建一个行程").hasSize(1);
        // 断言 2：计划里的条目只落一遍
        int itemCount = itineraryItemRepository
                .findByTripIdOrderByDayIndexAscStartTimeAsc(trips.get(0).getId()).size();
        assertThat(itemCount).as("计划条目只应落库一遍").isEqualTo(expectedItems);
        // 断言 3：只写一条操作历史（重复提交会写两条同源记录，撤销语义就乱了）
        assertThat(operationHistoryRepository.count() - historyBefore)
                .as("只应写一条操作历史").isEqualTo(1);
        // 断言 4：败方拿到明确的业务失败，而不是异常 / 500
        assertThat(first.error()).as("确认不应抛异常（应返回业务失败）").isNull();
        assertThat(second.error()).as("确认不应抛异常（应返回业务失败）").isNull();
        assertThat(List.of(first.status(), second.status()))
                .as("一个成功、一个业务失败")
                .containsExactlyInAnyOrder(AgentDtos.STATUS_COMPLETED, AgentDtos.STATUS_FAILED);
    }
}