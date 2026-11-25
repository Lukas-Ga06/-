package com.travel.agent.release;

import com.travel.agent.agent.AgentContext;
import com.travel.agent.agent.AgentEngine;
import com.travel.agent.dto.AgentDtos;
import com.travel.agent.dto.ItineraryDtos;
import com.travel.agent.dto.TripDtos;
import com.travel.agent.entity.ItineraryItem;
import com.travel.agent.entity.PlanExecution;
import com.travel.agent.entity.PlanOutbox;
import com.travel.agent.entity.Trip;
import com.travel.agent.repository.ItineraryItemRepository;
import com.travel.agent.repository.OperationHistoryRepository;
import com.travel.agent.repository.PlanExecutionRepository;
import com.travel.agent.repository.PlanOutboxRepository;
import com.travel.agent.repository.ScheduleItemRepository;
import com.travel.agent.repository.TripRepository;
import com.travel.agent.service.ItineraryService;
import com.travel.agent.service.PlanOutboxDispatcher;
import com.travel.agent.service.PlanOutboxService;
import com.travel.agent.service.TripService;
import com.travel.agent.support.IntegrationTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;

import java.math.BigDecimal;
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
 * 发布验收套件（P0 可靠执行链路）。
 *
 * <p>这 5 条用例是「可信执行系统」的最小承诺面，CI 里以独立命名步骤运行
 * （见 {@code .github/workflows/ci.yml} 的 Release acceptance）：
 * <ol>
 *   <li>连续两次确认同一方案，只产生一次有效提交；</li>
 *   <li>方案看到后行程被别端修改，旧方案确认必须失败且不落库；</li>
 *   <li>提交成功但跨模块同步未完成时，系统能独立补投（Outbox 崩溃恢复）；</li>
 *   <li>规划中断开连接，后续阶段不再产生无意义调用，也不落库半成品；</li>
 *   <li>LLM 完全不可用时，规则规划器仍产出合法可审核方案。</li>
 * </ol>
 *
 * <p>本类<b>刻意重复</b>了分散在各专项测试里的关键断言：专项测试证明实现细节，
 * 这里只证明「用户可观察的承诺」在合并后的代码上同时成立。断言文案面向发布记录。
 */
@Tag("release-acceptance")
@TestPropertySource(properties = "travel.llm.enabled=false")
class ReleaseAcceptanceTest extends IntegrationTestSupport {

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

    @Autowired
    private PlanOutboxService outboxService;

    @Autowired
    private PlanOutboxDispatcher outboxDispatcher;

    @Autowired
    private PlanOutboxRepository outboxRepository;

    @Autowired
    private PlanExecutionRepository planExecutionRepository;

    @Autowired
    private ScheduleItemRepository scheduleItemRepository;

    private AgentContext context(Account account, Long tripId, String message) {
        AgentContext ctx = new AgentContext();
        ctx.setUserId(account.id());
        ctx.setUsername(account.username());
        ctx.setSessionId(UUID.randomUUID().toString());
        ctx.setTripId(tripId);
        ctx.setMessage(message);
        return ctx;
    }

    private AgentDtos.AgentResult askForNewTripPlan(Account account) {
        return agentEngine.handle(context(account, null, "帮我规划上海三日游，2人，预算3000元"));
    }

    private record Outcome(String status, String message, String error) {
    }

    /** confirm 不应抛异常：业务失败必须以 status=FAILED 返回，否则前端拿到的是 500 */
    private Outcome confirmSafely(String planId, long userId) {
        try {
            AgentDtos.AgentResult result = agentEngine.confirm(planId, userId);
            return new Outcome(result.getStatus(), result.getMessage(), null);
        } catch (RuntimeException e) {
            return new Outcome("EXCEPTION", null, e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    // ==================== 用例 1：双确认只提交一次 ====================

    @Test
    @DisplayName("验收 1/5：并发两次确认同一方案，只创建一个行程、一条操作历史")
    void doubleConfirmCommitsExactlyOnce() throws Exception {
        Account account = registerNew();
        AgentDtos.AgentResult preview = askForNewTripPlan(account);
        assertThat(preview.getStatus()).isEqualTo(AgentDtos.STATUS_NEED_CONFIRMATION);
        String planId = preview.getConfirmation().getPlanId();
        int expectedItems = preview.getDiff() == null ? 0
                : (int) preview.getDiff().getChanges().stream()
                        .filter(c -> "ADD".equals(c.getType())).count();
        assertThat(expectedItems).as("规则引擎应产出新增条目").isPositive();
        long historyBefore = operationHistoryRepository.count();

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        Outcome first;
        Outcome second;
        try {
            List<Future<Outcome>> futures = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                futures.add(pool.submit(() -> {
                    start.await();
                    return confirmSafely(planId, account.id());
                }));
            }
            start.countDown();
            first = futures.get(0).get(30, TimeUnit.SECONDS);
            second = futures.get(1).get(30, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }

        assertThat(first.error()).as("确认不应抛异常").isNull();
        assertThat(second.error()).as("确认不应抛异常").isNull();
        assertThat(List.of(first.status(), second.status()))
                .as("一个成功、一个明确业务失败")
                .containsExactlyInAnyOrder(AgentDtos.STATUS_COMPLETED, AgentDtos.STATUS_FAILED);

        List<Trip> trips = tripRepository.findByUserIdOrderByCreatedAtDesc(account.id());
        assertThat(trips).as("并发确认后只应创建一个行程").hasSize(1);
        assertThat(itineraryItemRepository
                .findByTripIdOrderByDayIndexAscStartTimeAsc(trips.get(0).getId()))
                .as("计划条目只应落库一遍").hasSize(expectedItems);
        assertThat(operationHistoryRepository.count() - historyBefore)
                .as("只应写一条操作历史").isEqualTo(1);
    }

    // ==================== 用例 2：旧方案确认必失败 ====================

    @Test
    @DisplayName("验收 2/5：方案生成后行程被别端修改，旧方案确认失败且不落库")
    void stalePlanCannotBeConfirmed() throws Exception {
        Account account = registerNew();
        TripDtos.TripRequest request = new TripDtos.TripRequest();
        request.setName("验收-版本冲突");
        request.setDestination("上海");
        request.setBudget(BigDecimal.valueOf(3000));
        Trip trip = tripService.create(account.id(), request);
        itineraryService.addItem(trip, new ItineraryDtos.AddItemRequest(
                1, ItineraryItem.TYPE_ATTRACTION, "原有安排", "09:00", "10:00",
                BigDecimal.ZERO, null, null, null, null));

        AgentDtos.AgentResult plan = agentEngine.handle(
                context(account, trip.getId(), "帮我重新规划上海三日游，2人，预算3000元"));
        assertThat(plan.getStatus()).isEqualTo(AgentDtos.STATUS_NEED_CONFIRMATION);

        // 方案看到之后，另一个标签页/设备改了行程
        itineraryService.addItem(trip, new ItineraryDtos.AddItemRequest(
                2, ItineraryItem.TYPE_ATTRACTION, "确认前新增", "09:00", "10:00",
                BigDecimal.ZERO, null, null, null, null));

        AgentDtos.AgentResult result = agentEngine.confirm(plan.getConfirmation().getPlanId(), account.id());

        assertThat(result.getStatus()).as("旧方案必须失败").isEqualTo(AgentDtos.STATUS_FAILED);
        assertThat(result.getMessage()).as("失败原因要能让用户理解").contains("已经发生变化");
        assertThat(itineraryService.listEntities(trip.getId()))
                .as("旧方案不能覆盖别端修改后的行程，也不能落库半成品").hasSize(2);
    }

    // ==================== 用例 4：断线后不再产生无意义调用 ====================

    @Test
    @DisplayName("验收 4/5：规划中断开连接后，引擎在阶段边界停止且不落库半成品")
    void disconnectStopsFurtherWork() throws Exception {
        Account account = registerNew();
        AgentContext ctx = context(account, null, "帮我规划上海三日游，2人，预算3000元");
        List<String> events = new java.util.concurrent.CopyOnWriteArrayList<>();

        // 客户端在 planning 事件之后断开：后续阶段边界必须短路
        ctx.addListener(event -> {
            events.add(event.event());
            if ("planning".equals(event.event())) {
                ctx.cancel();
            }
        });

        AgentDtos.AgentResult result = agentEngine.handle(ctx);

        assertThat(result.getIntent()).isEqualTo(AgentEngine.CANCELLED_INTENT);
        assertThat(result.getStatus()).isEqualTo(AgentDtos.STATUS_FAILED);
        assertThat(events).as("取消后不再产出 complete / 规划事件").doesNotContain("complete");
        assertThat(tripRepository.findByUserIdOrderByCreatedAtDesc(account.id()))
                .as("半途取消不能创建行程").isEmpty();
    }

    // ==================== 用例 3：提交与同步之间崩溃可自愈 ====================

    @Test
    @DisplayName("验收 3/5：行程已提交但同步未跑（进程崩溃）时，Outbox 投递器能独立补齐且不重复")
    void syncRecoversAfterCrashBetweenCommitAndDelivery() throws Exception {
        Account account = registerNew();
        TripDtos.TripRequest request = new TripDtos.TripRequest();
        request.setName("验收-崩溃恢复");
        request.setDestination("上海");
        request.setBudget(BigDecimal.valueOf(3000));
        request.setStartDate(java.time.LocalDate.now().plusDays(3).toString());
        request.setEndDate(java.time.LocalDate.now().plusDays(5).toString());
        Trip trip = tripService.create(account.id(), request);

        String planId = "release-acceptance-" + UUID.randomUUID();
        // 模拟崩溃现场：行程事务已提交（trip 存在）、Outbox 待投递、同步台账根本不存在
        assertThat(outboxService.enqueueAll(planId, account.id(), trip.getId())).isEqualTo(3);
        assertThat(planExecutionRepository.findByPlanId(planId))
                .as("崩溃现场：台账尚未生成").isEmpty();

        assertThat(outboxDispatcher.dispatchDue())
                .as("投递器应接管到期的 3 个模块").isGreaterThanOrEqualTo(3);

        java.util.Optional<PlanExecution> recovered = planExecutionRepository.findByPlanId(planId);
        assertThat(recovered).as("迟到路径必须补建执行台账").isPresent();
        PlanExecution execution = recovered.orElseThrow();
        assertThat(execution.getStatus()).isEqualTo(PlanExecution.STATUS_SUCCEEDED);
        assertThat(execution.getModulesJson())
                .contains(PlanExecution.MODULE_CALENDAR, PlanExecution.MODULE_BUDGET, PlanExecution.MODULE_FOOTPRINT);
        assertThat(scheduleItemRepository.findBySourceKey("plan-sync:" + planId + ":day:1"))
                .as("日程必须由补投真正写出来").isPresent();
        assertThat(outboxRepository.findByPlanIdOrderByIdAsc(planId))
                .extracting(PlanOutbox::getStatus).containsOnly(PlanOutbox.STATUS_DONE);

        // 再投递一轮：幂等，不重复创建日程
        long schedulesBefore = scheduleItemRepository.countByTripId(trip.getId());
        outboxDispatcher.dispatchDue();
        assertThat(scheduleItemRepository.countByTripId(trip.getId()))
                .as("重复投递不得重复创建").isEqualTo(schedulesBefore);
    }

    // ==================== 用例 5：LLM 不可用仍能产出可审核方案 ====================

    @Test
    @DisplayName("验收 5/5：LLM 完全不可用时，规则规划器仍产出合法可审核方案")
    void rulePlannerWorksWithoutLlm() throws Exception {
        Account account = registerNew();
        AgentDtos.AgentResult plan = askForNewTripPlan(account);

        assertThat(plan.getStatus()).as("必须产出待确认方案，而不是失败").isEqualTo(AgentDtos.STATUS_NEED_CONFIRMATION);
        assertThat(plan.getConfirmation()).as("用户能看到确认入口").isNotNull();
        assertThat(plan.getConfirmation().getPlanId()).isNotBlank();
        assertThat(plan.getConfirmation().getContentHash()).as("内容指纹必须绑定").isNotBlank();
        assertThat(plan.getDiff()).as("方案 Diff 必须可展示").isNotNull();
        assertThat(plan.getDiff().getChanges()).as("方案必须有可执行变更").isNotEmpty();
        assertThat(plan.getReview()).as("必须经过审核（风险分级可见）").isNotNull();
    }
}