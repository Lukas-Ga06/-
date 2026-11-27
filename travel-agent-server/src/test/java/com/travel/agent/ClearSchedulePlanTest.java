package com.travel.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.travel.agent.agent.AgentContext;
import com.travel.agent.agent.AgentEngine;
import com.travel.agent.agent.IntentResult;
import com.travel.agent.dto.AgentDtos;
import com.travel.agent.dto.TripDtos;
import com.travel.agent.entity.OperationHistory;
import com.travel.agent.entity.ScheduleItem;
import com.travel.agent.entity.Trip;
import com.travel.agent.repository.OperationHistoryRepository;
import com.travel.agent.repository.ScheduleItemRepository;
import com.travel.agent.service.OperationHistoryService;
import com.travel.agent.service.ScheduleService;
import com.travel.agent.service.TripService;
import com.travel.agent.support.IntegrationTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.temporal.TemporalAdjusters;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 批量清空日程（CLEAR_SCHEDULE）的确认门闭环。
 *
 * <p>核心不变量：清空不直接落库；用户确认后才在单事务里删日程 + 写 SCHEDULE 域历史；
 * 确认期间范围内日程有任何变化（新增/删除/改时间）方案必须失效；撤销按快照原样还原。
 */
class ClearSchedulePlanTest extends IntegrationTestSupport {

    @Autowired
    private AgentEngine agentEngine;

    @Autowired
    private ScheduleService scheduleService;

    @Autowired
    private ScheduleItemRepository scheduleRepository;

    @Autowired
    private OperationHistoryRepository historyRepository;

    @Autowired
    private OperationHistoryService operationHistoryService;

    @Autowired
    private TripService tripService;

    private AgentDtos.AgentResult run(Account account, String message) {
        return run(account, null, message);
    }

    private AgentDtos.AgentResult run(Account account, String conversationId, String message) {
        AgentContext ctx = new AgentContext();
        ctx.setUserId(account.id());
        ctx.setUsername(account.username());
        ctx.setSessionId(UUID.randomUUID().toString());
        ctx.setConversationId(conversationId);
        ctx.setMessage(message);
        return agentEngine.handle(ctx);
    }

    private static LocalDate nextMonday() {
        return LocalDate.now().with(TemporalAdjusters.next(DayOfWeek.MONDAY));
    }

    /** 下周一 3 条 + 周二 1 条（范围外）——范围外的必须保留 */
    private void seedSchedules(Account account) {
        LocalDate monday = nextMonday();
        scheduleService.add(account.id(), "周一晨会", monday, LocalTime.of(9, 0), null);
        scheduleService.add(account.id(), "周一评审", monday, LocalTime.of(15, 0), null);
        scheduleService.add(account.id(), "周一复盘", monday, LocalTime.of(18, 30), null);
        scheduleService.add(account.id(), "范围外安排", monday.plusDays(1), LocalTime.of(10, 0), null);
    }

    private JsonNode confirm(Account account, AgentDtos.AgentResult plan, String contentHash) throws Exception {
        String request = objectMapper.writeValueAsString(Map.of(
                "planId", plan.getConfirmation().getPlanId(),
                "contentHash", contentHash));
        MvcResult result = mockMvc.perform(post("/api/agent/confirm")
                        .header("Authorization", account.bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request))
                .andExpect(status().isOk())
                .andReturn();
        return body(result).path("data");
    }

    /** 撤销：不带 tripId = 撤销最近一次日程清空；带 tripId = 撤销该行程的历史 */
    private JsonNode undo(Account account, Long tripId) throws Exception {
        String request = tripId == null ? "{}" : objectMapper.writeValueAsString(Map.of("tripId", tripId));
        MvcResult result = mockMvc.perform(post("/api/agent/undo")
                        .header("Authorization", account.bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request))
                .andExpect(status().isOk())
                .andReturn();
        return body(result).path("data");
    }

    private List<OperationHistory> scheduleHistories(Account account) {
        return historyRepository.findAll().stream()
                .filter(h -> Long.valueOf(account.id()).equals(h.getUserId()))
                .filter(h -> OperationHistory.DOMAIN_SCHEDULE.equals(h.getDomain()))
                .toList();
    }

    private List<OperationHistory> tripHistories(Trip trip) {
        return historyRepository.findAll().stream()
                .filter(h -> trip.getId().equals(h.getTripId()))
                .toList();
    }

    private Trip newTrip(Account account) {
        TripDtos.TripRequest request = new TripDtos.TripRequest();
        request.setName("上海差旅");
        request.setDestination("上海");
        request.setStartDate(LocalDate.now().toString());
        request.setEndDate(LocalDate.now().toString());
        request.setBudget(BigDecimal.valueOf(1000));
        return tripService.create(account.id(), request);
    }

    /** 带重复/关联/已完成/提醒忽略等列的日程：撤销重插必须逐列还原，不能只还原日期与标题 */
    private ScheduleItem saveRichSchedule(Account account, LocalDate monday) {
        return scheduleRepository.save(ScheduleItem.builder()
                .userId(account.id())
                .title("周一签证提醒")
                .date(monday)
                .time(LocalTime.of(11, 0))
                .note("带护照与照片")
                .recurrence("WEEKLY")
                .repeatUntil(monday.plusMonths(1))
                .tripId(9001L)
                .itineraryItemId(8001L)
                .travelDay(1)
                .source("AGENT")
                .sourceKey("test-undo-" + UUID.randomUUID())
                .done(true)
                .reminderDismissedAt(monday.atTime(7, 30))
                // 显式给一个创建时间（@PrePersist 只在为空时补当前时间）：撤销后能逐值比对，
                // 不依赖数据库 TIMESTAMP 的纳秒精度
                .createdAt(monday.minusDays(30).atTime(8, 30))
                .build());
    }

    @Test
    @DisplayName("清空下周一所有日程：走确认门，确认后只删范围内日程并写 SCHEDULE 域历史")
    void clearScheduleGoesThroughConfirmGate() throws Exception {
        Account account = registerNew();
        seedSchedules(account);

        AgentDtos.AgentResult plan = run(account, "下周一清空所有日程");

        assertThat(plan.getIntent()).isEqualTo(IntentResult.CLEAR_SCHEDULE);
        assertThat(plan.getStatus()).isEqualTo(AgentDtos.STATUS_NEED_CONFIRMATION);
        assertThat(plan.getDiff().getChanges()).hasSize(3).allSatisfy(change -> {
            assertThat(change.getType()).isEqualTo("REMOVE");
            assertThat(change.getItemType()).isEqualTo("schedule");
            assertThat(change.getTitle()).startsWith("日程：");
        });
        assertThat(plan.getConfirmation().getContentHash()).isNotBlank();
        // 不确认就不落库
        assertThat(scheduleRepository.findByUserIdOrderByDateAscTimeAscIdAsc(account.id())).hasSize(4);

        JsonNode confirmed = confirm(account, plan, plan.getConfirmation().getContentHash());

        assertThat(confirmed.path("status").asText()).isEqualTo(AgentDtos.STATUS_COMPLETED);
        assertThat(confirmed.path("message").asText()).contains("已清空 3 项日程");
        assertThat(scheduleRepository.findByUserIdOrderByDateAscTimeAscIdAsc(account.id()))
                .extracting(ScheduleItem::getTitle)
                .containsExactly("范围外安排");
        List<OperationHistory> histories = scheduleHistories(account);
        assertThat(histories).hasSize(1);
        assertThat(histories.get(0).getTripId()).isNull();
        assertThat(histories.get(0).getOperationType()).isEqualTo(OperationHistory.OP_SCHEDULE_CLEAR);
        assertThat(histories.get(0).getStatus()).isEqualTo(OperationHistory.STATUS_ACTIVE);
        assertThat(histories.get(0).getBeforeState()).contains("周一晨会").contains("周一评审").contains("周一复盘");
    }

    @Test
    @DisplayName("撤销日程清空：按快照还原全部列（日期/时间/标题 + 重复/关联/已完成/提醒），历史置 UNDONE")
    void undoScheduleClearRestoresSchedules() throws Exception {
        Account account = registerNew();
        seedSchedules(account);
        LocalDate monday = nextMonday();
        ScheduleItem rich = saveRichSchedule(account, monday);
        AgentDtos.AgentResult plan = run(account, "下周一清空所有日程");
        confirm(account, plan, plan.getConfirmation().getContentHash());

        JsonNode undone = undo(account, null);

        assertThat(undone.path("status").asText()).isEqualTo(AgentDtos.STATUS_COMPLETED);
        assertThat(undone.path("message").asText()).contains("已撤销").contains("还原 4 项日程");
        List<ScheduleItem> restored = scheduleRepository.findByUserIdOrderByDateAscTimeAscIdAsc(account.id());
        assertThat(restored).hasSize(5);
        assertThat(restored)
                .filteredOn(item -> monday.equals(item.getDate()) && LocalTime.of(9, 0).equals(item.getTime()))
                .extracting(ScheduleItem::getTitle)
                .containsExactly("周一晨会");
        assertThat(restored)
                .filteredOn(item -> monday.equals(item.getDate()) && LocalTime.of(15, 0).equals(item.getTime()))
                .extracting(ScheduleItem::getTitle)
                .containsExactly("周一评审");
        assertThat(restored)
                .filteredOn(item -> monday.equals(item.getDate()) && LocalTime.of(18, 30).equals(item.getTime()))
                .extracting(ScheduleItem::getTitle)
                .containsExactly("周一复盘");
        ScheduleItem restoredRich = restored.stream()
                .filter(item -> "周一签证提醒".equals(item.getTitle()))
                .findFirst().orElseThrow();
        // 快照缺列时这里会退化为默认值：重复规则、关联、来源与完成态都必须原样回来
        assertThat(restoredRich.getRecurrence()).isEqualTo("WEEKLY");
        assertThat(restoredRich.getRepeatUntil()).isEqualTo(monday.plusMonths(1));
        assertThat(restoredRich.getTripId()).isEqualTo(9001L);
        assertThat(restoredRich.getItineraryItemId()).isEqualTo(8001L);
        assertThat(restoredRich.getTravelDay()).isEqualTo(1);
        assertThat(restoredRich.getSource()).isEqualTo("AGENT");
        assertThat(restoredRich.getSourceKey()).isEqualTo(rich.getSourceKey());
        assertThat(restoredRich.isDone()).isTrue();
        assertThat(restoredRich.getReminderDismissedAt()).isEqualTo(monday.atTime(7, 30));
        // @PrePersist 只在为空时补当前时间：快照带原值，撤销后创建时间不漂移
        assertThat(restoredRich.getCreatedAt()).isEqualTo(rich.getCreatedAt());
        assertThat(scheduleHistories(account))
                .allSatisfy(history -> assertThat(history.getStatus()).isEqualTo(OperationHistory.STATUS_UNDONE));
    }

    @Test
    @DisplayName("清空追问后续接：先追问范围，用户只回「下周一」仍生成该范围的待确认方案")
    void clearFollowUpAcceptsDateReply() throws Exception {
        Account account = registerNew();
        seedSchedules(account);
        LocalDate monday = nextMonday();
        String conversationId = "c-" + UUID.randomUUID().toString().replace("-", "");

        AgentDtos.AgentResult ask = run(account, conversationId, "清空所有日程");
        assertThat(ask.getIntent()).isEqualTo(IntentResult.CLEAR_SCHEDULE);
        assertThat(ask.getMessage()).contains("要清空哪段时间的日程");
        assertThat(ask.getConfirmation()).isNull();

        AgentDtos.AgentResult plan = run(account, conversationId, "下周一");

        assertThat(plan.getIntent()).isEqualTo(IntentResult.CLEAR_SCHEDULE);
        assertThat(plan.getStatus()).isEqualTo(AgentDtos.STATUS_NEED_CONFIRMATION);
        assertThat(plan.getDiff().getChanges()).hasSize(3).allSatisfy(change ->
                assertThat(change.getType()).isEqualTo("REMOVE"));
        assertThat(plan.getMessage()).contains("将清空 " + monday + " 的 3 项日程");
        assertThat(plan.getConfirmation().getContentHash()).isNotBlank();
    }

    @Test
    @DisplayName("单次最多 150 项：超过时不生成方案，提示分段时间清空")
    void tooManySchedulesDoesNotProducePlan() throws Exception {
        Account account = registerNew();
        LocalDate monday = nextMonday();
        for (int i = 1; i <= 151; i++) {
            scheduleService.add(account.id(), "批量日程 " + i, monday, LocalTime.of(9, 0), null);
        }

        AgentDtos.AgentResult result = run(account, "下周一清空所有日程");

        assertThat(result.getIntent()).isEqualTo(IntentResult.CLEAR_SCHEDULE);
        assertThat(result.getStatus()).isEqualTo(AgentDtos.STATUS_COMPLETED);
        assertThat(result.getMessage()).isEqualTo("这段时间日程太多（151 项），请分段时间清空（单次上限 150 项）。");
        assertThat(result.getConfirmation()).isNull();
        assertThat(result.getDiff()).isNull();
        assertThat(scheduleRepository.findByUserIdOrderByDateAscTimeAscIdAsc(account.id())).hasSize(151);
    }

    @Test
    @DisplayName("两条撤销链互不覆盖：带 tripId 只撤销行程历史，不带 tripId 只撤销日程清空")
    void undoChainsDoNotOverlap() throws Exception {
        Account account = registerNew();
        seedSchedules(account);
        Trip trip = newTrip(account);
        operationHistoryService.withHistory(trip, account.id(), "手工调整行程", () -> null);
        OperationHistory firstTripHistory = tripHistories(trip).get(0);

        AgentDtos.AgentResult plan = run(account, "下周一清空所有日程");
        confirm(account, plan, plan.getConfirmation().getContentHash());

        // 带 tripId：只回滚行程历史，日程仍是清空后的状态
        JsonNode tripUndo = undo(account, trip.getId());
        assertThat(tripUndo.path("status").asText()).isEqualTo(AgentDtos.STATUS_COMPLETED);
        assertThat(tripUndo.path("message").asText()).contains("已撤销上一步操作");
        assertThat(scheduleRepository.findByUserIdOrderByDateAscTimeAscIdAsc(account.id()))
                .extracting(ScheduleItem::getTitle)
                .containsExactly("范围外安排");
        assertThat(scheduleHistories(account))
                .allSatisfy(history -> assertThat(history.getStatus()).isEqualTo(OperationHistory.STATUS_ACTIVE));
        assertThat(historyRepository.findById(firstTripHistory.getId()).orElseThrow().getStatus())
                .isEqualTo(OperationHistory.STATUS_UNDONE);

        // 不带 tripId：只撤销日程清空，新产生的行程历史保持 ACTIVE
        operationHistoryService.withHistory(trip, account.id(), "再次手工调整行程", () -> null);
        OperationHistory secondTripHistory = tripHistories(trip).stream()
                .filter(history -> OperationHistory.STATUS_ACTIVE.equals(history.getStatus()))
                .findFirst().orElseThrow();

        JsonNode scheduleUndo = undo(account, null);

        assertThat(scheduleUndo.path("message").asText())
                .contains("已撤销上一步日程清空").contains("还原 3 项日程");
        assertThat(scheduleRepository.findByUserIdOrderByDateAscTimeAscIdAsc(account.id())).hasSize(4);
        assertThat(scheduleHistories(account))
                .allSatisfy(history -> assertThat(history.getStatus()).isEqualTo(OperationHistory.STATUS_UNDONE));
        assertThat(historyRepository.findById(secondTripHistory.getId()).orElseThrow().getStatus())
                .isEqualTo(OperationHistory.STATUS_ACTIVE);
    }

    @Test
    @DisplayName("确认前范围内新增日程 → 方案失效、一条都没删、不写历史")
    void scheduleChangeBeforeConfirmInvalidatesPlan() throws Exception {
        Account account = registerNew();
        seedSchedules(account);
        AgentDtos.AgentResult plan = run(account, "下周一清空所有日程");

        // 另一个标签页：在同一个范围内新增了一条日程
        scheduleService.add(account.id(), "确认前新增", nextMonday(), LocalTime.of(12, 0), null);

        JsonNode confirmed = confirm(account, plan, plan.getConfirmation().getContentHash());

        assertThat(confirmed.path("status").asText()).isEqualTo(AgentDtos.STATUS_FAILED);
        assertThat(confirmed.path("message").asText()).contains("日程").contains("已失效");
        assertThat(scheduleRepository.findByUserIdOrderByDateAscTimeAscIdAsc(account.id())).hasSize(5);
        assertThat(scheduleHistories(account)).isEmpty();
    }

    @Test
    @DisplayName("没说范围时追问，不生成待确认方案")
    void clearWithoutRangeAsksForRange() throws Exception {
        Account account = registerNew();
        seedSchedules(account);

        AgentDtos.AgentResult result = run(account, "清空所有日程");

        assertThat(result.getIntent()).isEqualTo(IntentResult.CLEAR_SCHEDULE);
        assertThat(result.getStatus()).isEqualTo(AgentDtos.STATUS_COMPLETED);
        assertThat(result.getMessage()).contains("哪段时间");
        assertThat(result.getConfirmation()).isNull();
        assertThat(scheduleRepository.findByUserIdOrderByDateAscTimeAscIdAsc(account.id())).hasSize(4);
    }

    @Test
    @DisplayName("回归：「明天下午3点开评审会」仍是 ADD_SCHEDULE，不会被清空意图抢走")
    void addScheduleIsNotStolenByClearIntent() throws Exception {
        Account account = registerNew();

        AgentDtos.AgentResult result = run(account, "明天下午3点开评审会");

        assertThat(result.getIntent()).isEqualTo(IntentResult.ADD_SCHEDULE);
        assertThat(scheduleRepository.findByUserIdOrderByDateAscTimeAscIdAsc(account.id())).hasSize(1);
    }
}