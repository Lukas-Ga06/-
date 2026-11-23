package com.travel.agent.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.travel.agent.dto.AgentDtos;
import com.travel.agent.entity.PlanExecution;
import com.travel.agent.entity.ScheduleItem;
import com.travel.agent.entity.Trip;
import com.travel.agent.exception.BusinessException;
import com.travel.agent.repository.PlanExecutionRepository;
import com.travel.agent.repository.ScheduleItemRepository;
import com.travel.agent.repository.TripRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 确认后跨模块同步（V2.2 FR-09）：日程 / 预算 / 足迹。
 *
 * <p>设计文档 7.4 的「确认事务 + Outbox + 幂等消费者」在本项目的同库简化：
 * 行程提交（{@code ExecutorAgent.commit}）已有自己的原子事务；本服务在提交成功后
 * 执行同步，<b>逐模块</b>捕获异常——一个模块失败不影响其它模块，失败模块可单独重试，
 * 成功模块靠幂等键（plan_id 唯一 + source_key 唯一）保证重试不重复创建。
 *
 * <p>口径红线（设计文档表 12）：
 * <ul>
 *   <li><b>预算</b>：同步的是计划预算快照（行程预算上限 + 计划成本合计），
 *       绝不自动生成 Expense——估算不能变成「已消费」；</li>
 *   <li><b>足迹</b>：目的地级足迹由行程本身派生（含未出发的「计划去」），
 *       不把未来行程写成「已到访」；</li>
 *   <li><b>日程</b>：按「每日一个时间块」写入 schedule_item，带
 *       {@code source_key=plan-sync:{planId}:day:{n}} 幂等键；行程删除时级联清理。</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class PlanExecutionService {

    private final PlanExecutionRepository executionRepository;
    private final ScheduleItemRepository scheduleItemRepository;
    private final TripRepository tripRepository;
    private final PlanScheduleSyncWriter scheduleSyncWriter;
    private final ObjectMapper objectMapper;

    // ==================== 确认后的首次同步 ====================

    /**
     * 同步一份已提交的确认方案。幂等：同一 planId 已有执行记录时直接返回既有结果
     * （不重复创建任何模块数据）。
     *
     * @param planId  方案标识（即计划版本标识）
     * @param tripId  提交后的行程（新行程 = 新建，修改 = 既有行程）
     */
    @Transactional
    public AgentDtos.ExecutionSummary syncConfirmedPlan(String planId, Long userId, Long tripId) {
        Optional<PlanExecution> existing = executionRepository.findByPlanId(planId);
        if (existing.isPresent()) {
            // 幂等重入：同 planId 只有一份逻辑执行，返回既有台账
            return toSummary(existing.get());
        }
        PlanExecution execution = executionRepository.save(PlanExecution.builder()
                .executionId(UUID.randomUUID().toString())
                .planId(planId)
                .userId(userId)
                .tripId(tripId)
                .status(PlanExecution.STATUS_SUCCEEDED)
                .modulesJson("[]")
                .build());

        List<AgentDtos.ExecutionSummary.ExecutionModule> modules = new ArrayList<>();
        modules.add(calendarModule(planId, userId, tripId));
        modules.add(budgetModule(userId, tripId));
        modules.add(footprintModule(userId, tripId));

        execution.setModulesJson(writeModules(modules));
        execution.setStatus(aggregate(modules));
        execution.setUpdatedAt(LocalDateTime.now());
        return toSummary(executionRepository.save(execution));
    }

    // ==================== 失败模块重试 ====================

    /**
     * 重试执行记录里的失败模块（成功模块不动，幂等键保证不重复创建）。
     * 全部成功或无可重试模块时，只刷新台账。
     */
    @Transactional
    public AgentDtos.ExecutionSummary retry(String executionId, Long userId) {
        PlanExecution execution = executionRepository.findByExecutionIdAndUserId(executionId, userId)
                .orElseThrow(() -> new BusinessException(404, "执行记录不存在"));
        List<AgentDtos.ExecutionSummary.ExecutionModule> modules = readModules(execution);
        if (modules.isEmpty()) {
            throw new BusinessException(400, "该执行记录没有可重试的模块");
        }

        List<AgentDtos.ExecutionSummary.ExecutionModule> retried = new ArrayList<>();
        for (AgentDtos.ExecutionSummary.ExecutionModule module : modules) {
            if (!"FAILED".equals(module.getStatus())) {
                retried.add(module);
                continue;
            }
            AgentDtos.ExecutionSummary.ExecutionModule attempt =
                    runModule(execution.getPlanId(), execution.getUserId(), execution.getTripId(), module);
            attempt.setRetryCount((module.getRetryCount() == null ? 0 : module.getRetryCount()) + 1);
            retried.add(attempt);
        }

        execution.setModulesJson(writeModules(retried));
        execution.setStatus(aggregate(retried));
        execution.setUpdatedAt(LocalDateTime.now());
        return toSummary(executionRepository.save(execution));
    }

    /** 查询执行台账（归属校验） */
    @Transactional(readOnly = true)
    public AgentDtos.ExecutionSummary find(String executionId, Long userId) {
        PlanExecution execution = executionRepository.findByExecutionIdAndUserId(executionId, userId)
                .orElseThrow(() -> new BusinessException(404, "执行记录不存在"));
        return toSummary(execution);
    }

    // ==================== 行程删除时的级联清理 ====================

    /**
     * 行程删除（含撤销新建行程）时清理同步产物：该行程所有方案的同步日程与执行台账。
     * 幂等：重复调用无副作用。在调用方事务内执行（与行程删除同一事务）。
     */
    @Transactional
    public void cleanupForTrip(Long tripId) {
        if (tripId == null) {
            return;
        }
        List<PlanExecution> executions = executionRepository.findByTripId(tripId);
        if (executions.isEmpty()) {
            return;
        }
        long removedSchedules = 0;
        for (PlanExecution execution : executions) {
            removedSchedules += scheduleItemRepository
                    .deleteBySourceKeyStartingWith(sourcePrefix(execution.getPlanId()));
        }
        executionRepository.deleteByTripId(tripId);
        log.debug("清理行程同步产物: tripId={} executions={} schedules={}", tripId, executions.size(), removedSchedules);
    }

    // ==================== 模块实现 ====================

    private AgentDtos.ExecutionSummary.ExecutionModule calendarModule(String planId, Long userId, Long tripId) {
        AgentDtos.ExecutionSummary.ExecutionModule module = baseModule(PlanExecution.MODULE_CALENDAR, planId);
        try {
            Trip trip = ownedTrip(tripId, userId);
            if (trip.getStartDate() == null) {
                throw new IllegalStateException("行程缺少出发日期，无法生成日程时间块");
            }
            int days = trip.totalDays();
            int created = 0;
            for (int day = 1; day <= days; day++) {
                String sourceKey = sourceKey(planId, day);
                if (scheduleItemRepository.findBySourceKey(sourceKey).isPresent()) {
                    continue; // 幂等：已同步过（快路径，不走唯一键异常）
                }
                // 并发兜底走独立事务：撞唯一键只回滚这一天，不会把整个同步事务标成 rollback-only
                boolean inserted = scheduleSyncWriter.insertIfAbsent(ScheduleItem.builder()
                        .userId(userId)
                        .title("🧳 " + trip.getName() + " · Day " + day)
                        .date(trip.getStartDate().plusDays(day - 1L))
                        .time(LocalTime.of(9, 0))
                        .note("同步自行程计划（目的地 " + trip.getDestination() + "）")
                        .sourceKey(sourceKey)
                        .tripId(tripId)
                        .travelDay(day)
                        .source("PLAN_SYNC")
                        .build());
                if (inserted) {
                    created++;
                }
            }
            module.setStatus("SUCCEEDED");
            module.setDetail(days + " 天行程已同步为日程时间块（本次新建 " + created + " 条）");
        } catch (RuntimeException e) {
            module.setStatus("FAILED");
            module.setError(brief(e));
        }
        return module;
    }

    private AgentDtos.ExecutionSummary.ExecutionModule budgetModule(Long userId, Long tripId) {
        AgentDtos.ExecutionSummary.ExecutionModule module = baseModule(PlanExecution.MODULE_BUDGET, null);
        try {
            Trip trip = ownedTrip(tripId, userId);
            BigDecimal limit = trip.getBudget();
            // 计划成本口径：行程条目 cost 合计（与 GET /api/trips/{id}/budget 的 planned 一致）
            BigDecimal planned = tripRepository.sumItineraryCost(tripId);
            module.setStatus("SUCCEEDED");
            module.setDetail("计划预算快照已记录：预算上限 ¥" + plain(limit) + "，计划成本 ¥" + plain(planned)
                    + "；实际支出需手动记账，估算不会自动变成已消费");
        } catch (RuntimeException e) {
            module.setStatus("FAILED");
            module.setError(brief(e));
        }
        return module;
    }

    private AgentDtos.ExecutionSummary.ExecutionModule footprintModule(Long userId, Long tripId) {
        AgentDtos.ExecutionSummary.ExecutionModule module = baseModule(PlanExecution.MODULE_FOOTPRINT, null);
        try {
            Trip trip = ownedTrip(tripId, userId);
            if (Trip.STATUS_CANCELLED.equals(trip.getStatus())) {
                throw new IllegalStateException("行程已取消，不进入足迹");
            }
            module.setStatus("SUCCEEDED");
            module.setDetail("行程「" + trip.getDestination() + "」已进入足迹地图（计划标记，到访由行程日期派生）");
        } catch (RuntimeException e) {
            module.setStatus("FAILED");
            module.setError(brief(e));
        }
        return module;
    }

    private AgentDtos.ExecutionSummary.ExecutionModule runModule(String planId, Long userId, Long tripId,
                                                                 AgentDtos.ExecutionSummary.ExecutionModule failed) {
        return switch (failed.getModule() == null ? "" : failed.getModule()) {
            case PlanExecution.MODULE_CALENDAR -> calendarModule(planId, userId, tripId);
            case PlanExecution.MODULE_BUDGET -> budgetModule(userId, tripId);
            case PlanExecution.MODULE_FOOTPRINT -> footprintModule(userId, tripId);
            default -> failed; // 未知模块原样返回（保持台账可读）
        };
    }

    // ==================== 辅助 ====================

    private static AgentDtos.ExecutionSummary.ExecutionModule baseModule(String name, String planId) {
        return AgentDtos.ExecutionSummary.ExecutionModule.builder()
                .module(name)
                .status("SUCCEEDED")
                .idempotencyKey(planId == null ? null : "plan:" + planId + ":module:" + name)
                .build();
    }

    private static String sourcePrefix(String planId) {
        return "plan-sync:" + planId + ":";
    }

    private static String sourceKey(String planId, int day) {
        return sourcePrefix(planId) + "day:" + day;
    }

    private Trip ownedTrip(Long tripId, Long userId) {
        if (tripId == null) {
            throw new IllegalStateException("缺少行程标识");
        }
        return tripRepository.findById(tripId)
                .filter(t -> t.getUserId().equals(userId))
                .orElseThrow(() -> new IllegalStateException("行程不存在"));
    }

    private static String aggregate(List<AgentDtos.ExecutionSummary.ExecutionModule> modules) {
        boolean anyFailed = modules.stream().anyMatch(m -> "FAILED".equals(m.getStatus()));
        if (!anyFailed) {
            return PlanExecution.STATUS_SUCCEEDED;
        }
        boolean allFailed = modules.stream().allMatch(m -> "FAILED".equals(m.getStatus()));
        return allFailed ? PlanExecution.STATUS_FAILED : PlanExecution.STATUS_PARTIAL_FAILED;
    }

    private AgentDtos.ExecutionSummary toSummary(PlanExecution execution) {
        return AgentDtos.ExecutionSummary.builder()
                .executionId(execution.getExecutionId())
                .planId(execution.getPlanId())
                .tripId(execution.getTripId())
                .status(execution.getStatus())
                .modules(readModules(execution))
                .build();
    }

    private List<AgentDtos.ExecutionSummary.ExecutionModule> readModules(PlanExecution execution) {
        if (execution.getModulesJson() == null || execution.getModulesJson().isBlank()) {
            return new ArrayList<>();
        }
        try {
            return objectMapper.readValue(execution.getModulesJson(),
                    new TypeReference<List<AgentDtos.ExecutionSummary.ExecutionModule>>() {
                    });
        } catch (Exception e) {
            log.warn("执行台账 modulesJson 解析失败: executionId={}", execution.getExecutionId(), e);
            return new ArrayList<>();
        }
    }

    private String writeModules(List<AgentDtos.ExecutionSummary.ExecutionModule> modules) {
        try {
            return objectMapper.writeValueAsString(modules);
        } catch (Exception e) {
            log.warn("执行台账 modulesJson 序列化失败", e);
            return "[]";
        }
    }

    private static String brief(RuntimeException e) {
        String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        return message.length() > 300 ? message.substring(0, 299) + "…" : message;
    }

    private static String plain(BigDecimal value) {
        return value == null ? "-" : value.stripTrailingZeros().toPlainString();
    }
}
