package com.travel.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.travel.agent.agent.AgentContext;
import com.travel.agent.agent.AgentEngine;
import com.travel.agent.agent.IntentResult;
import com.travel.agent.dto.AgentDtos;
import com.travel.agent.entity.OperationHistory;
import com.travel.agent.entity.ScheduleItem;
import com.travel.agent.repository.OperationHistoryRepository;
import com.travel.agent.repository.ScheduleItemRepository;
import com.travel.agent.service.ScheduleService;
import com.travel.agent.support.IntegrationTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

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

    private AgentDtos.AgentResult run(Account account, String message) {
        AgentContext ctx = new AgentContext();
        ctx.setUserId(account.id());
        ctx.setUsername(account.username());
        ctx.setSessionId(UUID.randomUUID().toString());
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

    /** 不带 tripId 的撤销 = 撤销最近一次日程清空（日程不挂在行程上） */
    private JsonNode undoScheduleClear(Account account) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/agent/undo")
                        .header("Authorization", account.bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
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
    @DisplayName("撤销日程清空：按快照还原 3 条日程（日期/时间/标题一致），历史置 UNDONE")
    void undoScheduleClearRestoresSchedules() throws Exception {
        Account account = registerNew();
        seedSchedules(account);
        LocalDate monday = nextMonday();
        AgentDtos.AgentResult plan = run(account, "下周一清空所有日程");
        confirm(account, plan, plan.getConfirmation().getContentHash());

        JsonNode undone = undoScheduleClear(account);

        assertThat(undone.path("status").asText()).isEqualTo(AgentDtos.STATUS_COMPLETED);
        assertThat(undone.path("message").asText()).contains("已撤销").contains("还原 3 项日程");
        List<ScheduleItem> restored = scheduleRepository.findByUserIdOrderByDateAscTimeAscIdAsc(account.id());
        assertThat(restored).hasSize(4);
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
        assertThat(scheduleHistories(account))
                .allSatisfy(history -> assertThat(history.getStatus()).isEqualTo(OperationHistory.STATUS_UNDONE));
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