package com.travel.agent.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.travel.agent.dto.AgentDtos;
import com.travel.agent.entity.PlanExecution;
import com.travel.agent.entity.PlanOutbox;
import com.travel.agent.entity.ScheduleItem;
import com.travel.agent.entity.Trip;
import com.travel.agent.exception.BusinessException;
import com.travel.agent.repository.PlanExecutionRepository;
import com.travel.agent.repository.PlanOutboxRepository;
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
 * 确认后跨模块同步（V2.2 FR-09 + P0 事务性 Outbox）：日程 / 预算 / 足迹。
 *
 * <p>P0 之后的可靠性模型：
 * <ul>
 *   <li><b>入队与提交同事务</b>：确认提交在同一个事务里写 3 条 {@code plan_outbox(PENDING)}，
 *       并用 pending_plan 的租约栅栏保证只提交一次（见 {@code ConfirmCommitTx}）；</li>
 *   <li><b>至少一次投递 + 幂等消费</b>：{@code PlanOutboxDispatcher} 领取到期行并调用
 *       {@link #executeDispatched(PlanOutbox)}；进程崩溃只会让事件留在表里，重启后补投；</li>
 *   <li><b>迟到补建</b>：进程在「行程已提交、同步未跑」时崩溃，dispatcher 会在补投时创建
 *       {@code plan_execution} 台账并补齐日程（plan_id / source_key 双重幂等）；</li>
 *   <li><b>逐模块可重试</b>：模块失败写台账 FAILED，重试只重跑失败模块，成功项不重复创建。</li>
 * </ul>
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

    /** 模块级状态（写入 modules_json；台账聚合状态用 {@link PlanExecution#STATUS_*}） */
    private static final String MODULE_SUCCEEDED = "SUCCEEDED";
    private static final String MODULE_FAILED = "FAILED";

    private final PlanExecutionRepository executionRepository;
    private final PlanOutboxRepository outboxRepository;
    private final PlanOutboxService outboxService;
    private final ScheduleItemRepository scheduleItemRepository;
    private final TripRepository tripRepository;
    private final PlanScheduleSyncWriter scheduleSyncWriter;
    private final ObjectMapper objectMapper;

    // ==================== 确认后的首次同步 ====================

    /**
     * 同步一份已提交的确认方案。
     *
     * <p>P0 语义：先领取该方案「当前可立即执行」的 Outbox 行（PENDING 已到期，或 DISPATCHING
     * 但领取超租约），逐模块执行并写/更新台账；没有待办行时返回既有台账。
     *
     * <p>兼容入口：如果没有该方案的任何 Outbox 行（历史数据 / 直接调用本方法的旧代码路径），
     * 退回「一次执行三模块」的既有行为，保证老调用方语义不变。
     *
     * <p>有 Outbox 行但当前没有可投递行（全部 DONE / DEAD / 仍在退避窗口）且台账缺失时返回
     * {@code null}：此时不能绕过退避重跑，确认响应只是不附加执行摘要。
     *
     * @param planId  方案标识（即计划版本标识）
     * @param tripId  提交后的行程（新行程 = 新建，修改 = 既有行程）
     */
    @Transactional
    public AgentDtos.ExecutionSummary syncConfirmedPlan(String planId, Long userId, Long tripId) {
        LocalDateTime now = LocalDateTime.now();
        List<PlanOutbox> due = outboxRepository.findDispatchableForPlan(
                planId, now, outboxService.staleDispatchBefore(now));
        if (!due.isEmpty()) {
            return dispatchForPlan(planId, userId, tripId, due);
        }
        Optional<PlanExecution> existing = executionRepository.findByPlanId(planId);
        if (existing.isPresent()) {
            // 幂等重入：同 planId 只有一份逻辑执行，返回既有台账
            return toSummary(existing.get());
        }
        if (outboxRepository.existsByPlanId(planId)) {
            // 有投递记录但当前没有可投递行：可能是全部 DONE（台账应已存在）或 DEAD/退避中。
            // 不在这里绕过退避重跑；DEAD 行需要人工介入（重试接口会重新排队）。
            log.info("同步跳过：方案没有待投递的 Outbox 行且无执行台账: planId={}", sanitize(planId));
            return null;
        }
        // 兼容旧入口：未经过确认 Outbox 的直接同步（历史数据 / 内部调用）
        return executeAllModules(planId, userId, tripId);
    }

    /**
     * 确认请求内的同步：领取该方案的到期 Outbox 行并逐模块执行。
     * 与其他投递者（定时 dispatcher）的互斥靠 {@link PlanOutboxService#claimForPlan(PlanOutbox)}
     * 的条件更新：PENDING 行只有一方能领到；崩溃残留的 DISPATCHING 行要超过租约才允许接管。
     */
    private AgentDtos.ExecutionSummary dispatchForPlan(String planId, Long userId, Long tripId,
                                                       List<PlanOutbox> due) {
        PlanExecution execution = executionRepository.findByPlanId(planId).orElse(null);
        List<AgentDtos.ExecutionSummary.ExecutionModule> modules =
                execution == null ? new ArrayList<>() : readModules(execution);
        boolean executed = false;
        for (PlanOutbox row : due) {
            if (!outboxService.claimForPlan(row)) {
                // 已被定时投递器领走（或租约未到期）：不重复执行
                continue;
            }
            AgentDtos.ExecutionSummary.ExecutionModule module =
                    executeModule(row.getModule(), planId, row.getUserId(), row.getTripId());
            upsertModule(modules, module);
            executed = true;
            markOutbox(row.getId(), module);
        }
        if (!executed) {
            return execution == null ? null : toSummary(execution);
        }
        PlanExecution target = execution == null ? newExecution(planId, userId, tripId) : execution;
        return saveExecution(target, modules);
    }

    // ==================== 投递器（迟到补建） ====================

    /**
     * 投递器单模块执行：幂等、可迟到补建台账。
     *
     * <p>已在台账里记录成功的模块直接返回（重复投递不重放）；模块本身也有幂等键
     * （schedule_item.source_key 唯一 + plan_execution.plan_id 唯一），即使重放也不会重复创建。
     *
     * <p>返回的模块状态由调用方（dispatcher）据此更新 Outbox：SUCCEEDED → DONE，FAILED → 退避重试。
     */
    @Transactional
    public AgentDtos.ExecutionSummary.ExecutionModule executeDispatched(PlanOutbox outbox) {
        String planId = outbox.getPlanId();
        PlanExecution execution = executionRepository.findByPlanId(planId).orElse(null);
        List<AgentDtos.ExecutionSummary.ExecutionModule> modules =
                execution == null ? new ArrayList<>() : readModules(execution);
        AgentDtos.ExecutionSummary.ExecutionModule previous = findModule(modules, outbox.getModule());
        if (previous != null && MODULE_SUCCEEDED.equals(previous.getStatus())) {
            log.debug("Outbox 重复投递，台账已记录成功: planId={} module={}", sanitize(planId), outbox.getModule());
            return previous;
        }
        AgentDtos.ExecutionSummary.ExecutionModule module =
                executeModule(outbox.getModule(), planId, outbox.getUserId(), outbox.getTripId());
        upsertModule(modules, module);
        PlanExecution target = execution == null
                ? newExecution(planId, outbox.getUserId(), outbox.getTripId())
                : execution;
        saveExecution(target, modules);
        return module;
    }

    // ==================== 失败模块重试 ====================

    /**
     * 重试执行记录里的失败模块（成功模块不动，幂等键保证不重复创建）。
     * 全部成功或无可重试模块时，只刷新台账；同时把对应 Outbox 行收口
     * （成功 → DONE，仍失败 → 重新排队并把退避预算清零）。
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
            if (!MODULE_FAILED.equals(module.getStatus())) {
                retried.add(module);
                continue;
            }
            AgentDtos.ExecutionSummary.ExecutionModule attempt =
                    executeModule(module.getModule(), execution.getPlanId(),
                            execution.getUserId(), execution.getTripId());
            attempt.setRetryCount((module.getRetryCount() == null ? 0 : module.getRetryCount()) + 1);
            retried.add(attempt);
        }
        syncOutboxAfterRetry(execution.getPlanId(), retried);

        execution.setModulesJson(writeModules(retried));
        execution.setStatus(aggregate(retried));
        execution.setUpdatedAt(LocalDateTime.now());
        return toSummary(executionRepository.save(execution));
    }

    /** 人工重试后的 Outbox 收口：成功模块 DONE，仍失败模块重新排队（不重复入队） */
    private void syncOutboxAfterRetry(String planId, List<AgentDtos.ExecutionSummary.ExecutionModule> modules) {
        for (AgentDtos.ExecutionSummary.ExecutionModule module : modules) {
            PlanOutbox row = outboxRepository.findByPlanIdAndModule(planId, module.getModule()).orElse(null);
            if (row == null) {
                continue;
            }
            if (MODULE_SUCCEEDED.equals(module.getStatus())) {
                outboxService.markDone(row.getId());
            } else if (MODULE_FAILED.equals(module.getStatus())) {
                outboxService.reviveForRetry(planId, module.getModule());
            }
        }
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
     * 行程删除（含撤销新建行程）时清理同步产物：该行程所有方案的待投递事件、同步日程与执行台账。
     * 幂等：重复调用无副作用。在调用方事务内执行（与行程删除同一事务）。
     */
    @Transactional
    public void cleanupForTrip(Long tripId) {
        if (tripId == null) {
            return;
        }
        long removedOutbox = outboxRepository.deleteByTripId(tripId);
        List<PlanExecution> executions = executionRepository.findByTripId(tripId);
        long removedSchedules = 0;
        for (PlanExecution execution : executions) {
            removedSchedules += scheduleItemRepository
                    .deleteBySourceKeyStartingWith(sourcePrefix(execution.getPlanId()));
        }
        executionRepository.deleteByTripId(tripId);
        if (!executions.isEmpty() || removedOutbox > 0) {
            log.debug("清理行程同步产物: tripId={} outbox={} executions={} schedules={}",
                    tripId, removedOutbox, executions.size(), removedSchedules);
        }
    }

    // ==================== 模块实现（可单独调用） ====================

    /**
     * 执行单个同步模块并返回模块级结果（不落台账）。
     *
     * <p>模块内部捕获异常并返回 FAILED，调用方据状态决定台账与 Outbox 的写法——
     * 一个模块失败不影响其它模块，也不把整个事务标成 rollback-only。
     */
    public AgentDtos.ExecutionSummary.ExecutionModule executeModule(String module, String planId,
                                                                    Long userId, Long tripId) {
        return switch (module == null ? "" : module) {
            case PlanExecution.MODULE_CALENDAR -> calendarModule(planId, userId, tripId);
            case PlanExecution.MODULE_BUDGET -> budgetModule(userId, tripId);
            case PlanExecution.MODULE_FOOTPRINT -> footprintModule(userId, tripId);
            default -> unknownModule(module);
        };
    }

    /** 兼容旧入口：没有 Outbox 行时一次性执行三模块（历史上确认后的直接同步） */
    private AgentDtos.ExecutionSummary executeAllModules(String planId, Long userId, Long tripId) {
        PlanExecution execution = newExecution(planId, userId, tripId);
        List<AgentDtos.ExecutionSummary.ExecutionModule> modules = new ArrayList<>();
        for (String module : PlanOutboxService.MODULES) {
            modules.add(executeModule(module, planId, userId, tripId));
        }
        return saveExecution(execution, modules);
    }

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
            module.setStatus(MODULE_SUCCEEDED);
            module.setDetail(days + " 天行程已同步为日程时间块（本次新建 " + created + " 条）");
        } catch (RuntimeException e) {
            module.setStatus(MODULE_FAILED);
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
            module.setStatus(MODULE_SUCCEEDED);
            module.setDetail("计划预算快照已记录：预算上限 ¥" + plain(limit) + "，计划成本 ¥" + plain(planned)
                    + "；实际支出需手动记账，估算不会自动变成已消费");
        } catch (RuntimeException e) {
            module.setStatus(MODULE_FAILED);
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
            module.setStatus(MODULE_SUCCEEDED);
            module.setDetail("行程「" + trip.getDestination() + "」已进入足迹地图（计划标记，到访由行程日期派生）");
        } catch (RuntimeException e) {
            module.setStatus(MODULE_FAILED);
            module.setError(brief(e));
        }
        return module;
    }

    private AgentDtos.ExecutionSummary.ExecutionModule unknownModule(String module) {
        AgentDtos.ExecutionSummary.ExecutionModule result = baseModule(module, null);
        result.setStatus(MODULE_FAILED);
        result.setError("未知同步模块: " + module);
        return result;
    }

    // ==================== 台账 ====================

    private PlanExecution newExecution(String planId, Long userId, Long tripId) {
        return PlanExecution.builder()
                .executionId(UUID.randomUUID().toString())
                .planId(planId)
                .userId(userId)
                .tripId(tripId)
                .status(PlanExecution.STATUS_SUCCEEDED)
                .modulesJson("[]")
                .build();
    }

    private AgentDtos.ExecutionSummary saveExecution(PlanExecution execution,
                                                     List<AgentDtos.ExecutionSummary.ExecutionModule> modules) {
        execution.setModulesJson(writeModules(modules));
        execution.setStatus(aggregate(modules));
        execution.setUpdatedAt(LocalDateTime.now());
        return toSummary(executionRepository.save(execution));
    }

    /** 按模块名就地替换（保持首次执行顺序，重试只更新对应模块） */
    private static void upsertModule(List<AgentDtos.ExecutionSummary.ExecutionModule> modules,
                                     AgentDtos.ExecutionSummary.ExecutionModule update) {
        for (int i = 0; i < modules.size(); i++) {
            String existing = modules.get(i).getModule();
            if (existing != null && existing.equals(update.getModule())) {
                modules.set(i, update);
                return;
            }
        }
        modules.add(update);
    }

    private static AgentDtos.ExecutionSummary.ExecutionModule findModule(
            List<AgentDtos.ExecutionSummary.ExecutionModule> modules, String name) {
        if (name == null) {
            return null;
        }
        for (AgentDtos.ExecutionSummary.ExecutionModule module : modules) {
            if (name.equals(module.getModule())) {
                return module;
            }
        }
        return null;
    }

    /** 根据模块结果决定 Outbox 收口方式，异常不向外抛（投递器自己会兜底） */
    private void markOutbox(Long outboxId, AgentDtos.ExecutionSummary.ExecutionModule module) {
        if (MODULE_FAILED.equals(module.getStatus())) {
            outboxService.markFailure(outboxId, module.getError());
        } else {
            outboxService.markDone(outboxId);
        }
    }

    // ==================== 辅助 ====================

    private static AgentDtos.ExecutionSummary.ExecutionModule baseModule(String name, String planId) {
        return AgentDtos.ExecutionSummary.ExecutionModule.builder()
                .module(name)
                .status(MODULE_SUCCEEDED)
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
        boolean anyFailed = modules.stream().anyMatch(m -> MODULE_FAILED.equals(m.getStatus()));
        if (!anyFailed) {
            return PlanExecution.STATUS_SUCCEEDED;
        }
        boolean allFailed = modules.stream().allMatch(m -> MODULE_FAILED.equals(m.getStatus()));
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

    /** 用户可控的 planId 进日志前剥掉 CR/LF，防止伪造日志行 */
    private static String sanitize(String value) {
        return value == null ? null : value.replace("\r", "_").replace("\n", "_");
    }
}