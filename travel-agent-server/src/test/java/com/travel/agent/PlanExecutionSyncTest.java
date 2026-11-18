package com.travel.agent;

import com.travel.agent.agent.AgentPlan;
import com.travel.agent.agent.IntentResult;
import com.travel.agent.agent.PendingPlanStore;
import com.travel.agent.agent.PlanConfirmationService;
import com.travel.agent.agent.PlanOperation;
import com.travel.agent.agent.TripDraft;
import com.travel.agent.dto.AgentDtos;
import com.travel.agent.entity.ItineraryItem;
import com.travel.agent.entity.PlanExecution;
import com.travel.agent.entity.ScheduleItem;
import com.travel.agent.repository.PlanExecutionRepository;
import com.travel.agent.repository.ScheduleItemRepository;
import com.travel.agent.repository.TripRepository;
import com.travel.agent.service.PlanExecutionService;
import com.travel.agent.service.ScheduleService;
import com.travel.agent.support.IntegrationTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * V2.2 FR-08 / FR-09：确认门内容指纹 + 确认后跨模块幂等同步。
 *
 * <p>核心验收（对应设计文档 §4 关键场景）：
 * <ul>
 *   <li>正常闭环：确认后日程/预算/足迹三模块同步成功，台账可查；</li>
 *   <li>重复同步：同一 planId 只有一份逻辑执行，日程不重复创建（幂等键）；</li>
 *   <li>内容指纹：携带错误指纹的确认被拒绝且方案保留；正确指纹通过；不携带时走旧语义；</li>
 *   <li>部分失败：单模块失败 → PARTIAL_FAILED，失败模块可重试且重试成功后不重复；</li>
 *   <li>清理：撤销（删除）行程时，同步出来的日程与执行台账一并清理。</li>
 * </ul>
 */
class PlanExecutionSyncTest extends IntegrationTestSupport {

    @Autowired
    private PendingPlanStore pendingPlans;
    @Autowired
    private PlanConfirmationService planConfirmation;
    @Autowired
    private PlanExecutionService executionService;
    @Autowired
    private PlanExecutionRepository executionRepository;
    @Autowired
    private ScheduleItemRepository scheduleItemRepository;
    @Autowired
    private ScheduleService scheduleService;
    @Autowired
    private TripRepository tripRepository;

    // ==================== 测试数据 ====================

    private AgentPlan newTripPlan(String planId) {
        ItineraryItem item = ItineraryItem.builder()
                .dayIndex(1)
                .itemType(ItineraryItem.TYPE_ATTRACTION)
                .title("外滩")
                .startTime(LocalDateTime.of(2027, 5, 1, 10, 0))
                .endTime(LocalDateTime.of(2027, 5, 1, 11, 30))
                .cost(BigDecimal.valueOf(60))
                .build();
        return AgentPlan.builder()
                .planId(planId)
                .intent(IntentResult.PLAN_TRIP)
                .tripId(null)
                .tripDraft(TripDraft.builder()
                        .name("同步测试行程")
                        .destination("上海")
                        .startDate(LocalDate.of(2027, 5, 1))
                        .days(3)
                        .budget(BigDecimal.valueOf(3000))
                        .preferences(List.of())
                        .build())
                .operations(List.of(PlanOperation.builder()
                        .type(PlanOperation.ADD).draft(item).detail("新增：外滩").build()))
                .reasoning("测试：新行程确认后同步")
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
        String planId = "plan-sync-" + userId + "-" + System.nanoTime();
        pendingPlans.put(new PendingPlanStore.PendingPlan(
                planId, userId, null, newTripPlan(planId), previewOf(planId), null, null, Instant.now()));
        return planId;
    }

    // ==================== FR-09 正常闭环与幂等 ====================

    @Test
    @DisplayName("确认后同步日程/预算/足迹：三模块成功，台账可查，日程带幂等键")
    void confirmSyncsAllModules() throws Exception {
        Account account = registerNew();
        String planId = putPendingPlan(account.id());

        AgentDtos.AgentResult result = planConfirmation.confirm(planId, account.id());
        assertThat(result.getStatus()).isEqualTo(AgentDtos.STATUS_COMPLETED);
        assertThat(result.getTripId()).isNotNull();
        assertThat(result.getMessage()).contains("已同步");

        // 执行台账：三模块全部成功
        AgentDtos.ExecutionSummary execution = result.getExecution();
        assertThat(execution).isNotNull();
        assertThat(execution.getStatus()).isEqualTo(PlanExecution.STATUS_SUCCEEDED);
        assertThat(execution.getModules()).extracting(AgentDtos.ExecutionSummary.ExecutionModule::getModule)
                .containsExactly("calendar", "budget", "footprint");
        assertThat(execution.getModules())
                .allMatch(m -> "SUCCEEDED".equals(m.getStatus()));

        // 日程：3 天 3 条，带 source_key 幂等键
        List<ScheduleItem> items = scheduleService.list(account.id()).stream()
                .filter(s -> s.getSourceKey() != null && s.getSourceKey().startsWith("plan-sync:" + planId))
                .toList();
        assertThat(items).hasSize(3);
        assertThat(items).allSatisfy(s -> {
            assertThat(s.getTitle()).contains("同步测试行程").contains("Day");
            assertThat(s.getSourceKey()).startsWith("plan-sync:" + planId + ":day:");
        });

        // REST 查询台账（归属校验）
        mockMvc.perform(get("/api/agent/executions/" + execution.getExecutionId())
                        .header("Authorization", account.bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("SUCCEEDED"))
                .andExpect(jsonPath("$.data.modules.length()").value(3));
    }

    @Test
    @DisplayName("同一方案重复同步只有一份逻辑执行：日程不重复创建（幂等键）")
    void repeatedSyncIsIdempotent() throws Exception {
        Account account = registerNew();
        String planId = putPendingPlan(account.id());
        AgentDtos.AgentResult result = planConfirmation.confirm(planId, account.id());
        Long tripId = result.getTripId();

        // 直接重入同步（模拟重试/并发第二确认的兜底路径）
        AgentDtos.ExecutionSummary again = executionService.syncConfirmedPlan(planId, account.id(), tripId);
        assertThat(again.getExecutionId()).isEqualTo(result.getExecution().getExecutionId());
        assertThat(executionRepository.findByPlanId(planId)).isPresent();

        long scheduleCount = scheduleItemRepository.findBySourceKey(
                "plan-sync:" + planId + ":day:1").stream().count();
        assertThat(scheduleCount).isEqualTo(1);
        List<ScheduleItem> items = scheduleItemRepository
                .findByUserIdOrderByDateAscTimeAscIdAsc(account.id()).stream()
                .filter(s -> s.getSourceKey() != null && s.getSourceKey().startsWith("plan-sync:" + planId))
                .toList();
        assertThat(items).hasSize(3);
    }

    // ==================== FR-08 内容指纹 ====================

    @Test
    @DisplayName("携带正确内容指纹的确认通过，确认弹窗能看到指纹")
    void confirmWithCorrectContentHashSucceeds() throws Exception {
        Account account = registerNew();
        String planId = putPendingPlan(account.id());

        PendingPlanStore.PendingPlan pending = pendingPlans.peek(planId, account.id());
        assertThat(pending.contentHash()).isNotBlank();
        // 取回的确认请求带内容指纹（前端回传的依据）
        assertThat(pending.preview().getConfirmation().getContentHash()).isEqualTo(pending.contentHash());

        AgentDtos.AgentResult result = planConfirmation.confirm(planId, account.id(), pending.contentHash());
        assertThat(result.getStatus()).isEqualTo(AgentDtos.STATUS_COMPLETED);
    }

    @Test
    @DisplayName("内容指纹不匹配的确认被拒绝：方案保留，可用正确指纹重新确认")
    void confirmWithWrongContentHashIsRejected() throws Exception {
        Account account = registerNew();
        String planId = putPendingPlan(account.id());
        PendingPlanStore.PendingPlan pending = pendingPlans.peek(planId, account.id());

        AgentDtos.AgentResult rejected = planConfirmation.confirm(planId, account.id(), "deadbeef");
        assertThat(rejected.getStatus()).isEqualTo(AgentDtos.STATUS_FAILED);
        assertThat(rejected.getMessage()).contains("不一致");

        // 方案未被消费：正确指纹仍可确认成功
        assertThat(pendingPlans.peek(planId, account.id())).isNotNull();
        AgentDtos.AgentResult result = planConfirmation.confirm(planId, account.id(), pending.contentHash());
        assertThat(result.getStatus()).isEqualTo(AgentDtos.STATUS_COMPLETED);
    }

    @Test
    @DisplayName("不带指纹的确认走旧语义（向后兼容）：老前端行为不变")
    void confirmWithoutContentHashKeepsLegacyBehavior() throws Exception {
        Account account = registerNew();
        String planId = putPendingPlan(account.id());

        AgentDtos.AgentResult result = planConfirmation.confirm(planId, account.id());
        assertThat(result.getStatus()).isEqualTo(AgentDtos.STATUS_COMPLETED);
    }

    // ==================== 部分失败与重试 ====================

    @Test
    @DisplayName("单模块失败 → PARTIAL_FAILED；修复后重试只补失败模块且不重复")
    void partialFailureCanBeRetried() throws Exception {
        Account account = registerNew();
        // 直接构造：行程缺出发日期 → 日程模块失败，预算/足迹成功
        var trip = tripRepository.save(com.travel.agent.entity.Trip.builder()
                .userId(account.id())
                .name("缺日期行程")
                .destination("上海")
                .startDate(null)
                .endDate(null)
                .budget(BigDecimal.valueOf(2000))
                .build());
        String planId = "plan-partial-" + account.id() + "-" + System.nanoTime();

        AgentDtos.ExecutionSummary first = executionService.syncConfirmedPlan(planId, account.id(), trip.getId());
        assertThat(first.getStatus()).isEqualTo(PlanExecution.STATUS_PARTIAL_FAILED);
        assertThat(first.getModules()).extracting(AgentDtos.ExecutionSummary.ExecutionModule::getModule)
                .containsExactly("calendar", "budget", "footprint");
        AgentDtos.ExecutionSummary.ExecutionModule calendar = first.getModules().get(0);
        assertThat(calendar.getStatus()).isEqualTo("FAILED");
        assertThat(calendar.getError()).contains("出发日期");

        // 修复行程日期后重试：只有 calendar 模块重跑
        trip.setStartDate(LocalDate.now().plusDays(3));
        trip.setEndDate(LocalDate.now().plusDays(5));
        tripRepository.save(trip);

        AgentDtos.ExecutionSummary retried = executionService.retry(first.getExecutionId(), account.id());
        assertThat(retried.getStatus()).isEqualTo(PlanExecution.STATUS_SUCCEEDED);
        AgentDtos.ExecutionSummary.ExecutionModule retriedCalendar = retried.getModules().get(0);
        assertThat(retriedCalendar.getStatus()).isEqualTo("SUCCEEDED");
        assertThat(retriedCalendar.getRetryCount()).isEqualTo(1);

        // 重试补齐的日程恰好 3 条（修复后的 3 天行程）
        List<ScheduleItem> items = scheduleItemRepository
                .findByUserIdOrderByDateAscTimeAscIdAsc(account.id()).stream()
                .filter(s -> s.getSourceKey() != null && s.getSourceKey().startsWith("plan-sync:" + planId))
                .toList();
        assertThat(items).hasSize(3);
    }

    @Test
    @DisplayName("他人不能查看/重试执行台账；不存在的 executionId 返回 404")
    void executionLedgerIsIsolatedPerUser() throws Exception {
        Account account = registerNew();
        String planId = putPendingPlan(account.id());
        AgentDtos.AgentResult result = planConfirmation.confirm(planId, account.id());
        String executionId = result.getExecution().getExecutionId();

        Account stranger = registerNew();
        mockMvc.perform(get("/api/agent/executions/" + executionId)
                        .header("Authorization", stranger.bearer()))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/agent/executions/not-exist")
                        .header("Authorization", account.bearer()))
                .andExpect(status().isNotFound());
    }

    // ==================== 清理 ====================

    @Test
    @DisplayName("撤销（删除）行程时：同步日程与执行台账一并清理")
    void deletingTripCleansSyncedArtifacts() throws Exception {
        Account account = registerNew();
        String planId = putPendingPlan(account.id());
        AgentDtos.AgentResult result = planConfirmation.confirm(planId, account.id());
        Long tripId = result.getTripId();
        String executionId = result.getExecution().getExecutionId();

        assertThat(scheduleItemRepository.findBySourceKey("plan-sync:" + planId + ":day:1")).isPresent();

        planConfirmation.undo(tripId, account.id());

        assertThat(scheduleItemRepository.findBySourceKey("plan-sync:" + planId + ":day:1")).isEmpty();
        assertThat(executionRepository.findByPlanId(planId)).isEmpty();
        assertThat(tripRepository.findById(tripId)).isEmpty();
        // 执行台账也一并删除（行程已不存在，避免孤儿记录）
        assertThat(executionRepository.findByExecutionIdAndUserId(executionId, account.id())).isEmpty();
    }
}
