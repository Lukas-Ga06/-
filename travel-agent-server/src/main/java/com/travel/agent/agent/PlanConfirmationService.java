package com.travel.agent.agent;

import com.travel.agent.dto.AgentDtos;
import com.travel.agent.entity.Trip;
import com.travel.agent.exception.BusinessException;
import com.travel.agent.service.ItineraryService;
import com.travel.agent.service.OperationHistoryService;
import com.travel.agent.service.TripService;
import io.micrometer.core.instrument.Timer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Human-in-the-loop 的确认 / 取消 / 取回 / 撤销。
 *
 * <p>从 {@link AgentEngine} 抽出：这些方法由 REST 接口触发（POST /api/agent/confirm 等），
 * 不属于 SSE 主链路，与状态机解耦后单独维护并发语义更清晰。
 *
 * <p>并发核心：确认与取消都要先抢 {@link PendingPlanStore} 的所有权（PENDING → CONFIRMING 条件更新），
 * 只有抢到的请求可以继续提交，避免「同一方案被提交两次」或「已提示取消、实际已提交」的假取消。
 * 抢到与提交之间<b>不插入其它写操作</b>，窗口越短越安全。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class PlanConfirmationService {

    private final PendingPlanStore pendingPlans;
    private final ExecutorAgent executorAgent;
    private final TripService tripService;
    private final ItineraryService itineraryService;
    private final OperationHistoryService operationHistoryService;
    private final AgentStageTimer stageTimer;
    /** V2.2 追踪：确认提交也挂回产生方案的 run（plan.runId） */
    private final com.travel.agent.service.AgentTraceService traceService;
    /** V2.2 FR-09：确认后日程/预算/足迹跨模块同步（模块级幂等 + 可重试） */
    private final com.travel.agent.service.PlanExecutionService executionService;

    /**
     * 确认并提交待确认方案（不带内容指纹，向后兼容入口）。
     */
    public AgentDtos.AgentResult confirm(String planId, Long userId) {
        return confirm(planId, userId, null, null);
    }

    /** 带内容指纹、不带风险确认的入口（V2.2 FR-08 旧前端） */
    public AgentDtos.AgentResult confirm(String planId, Long userId, String contentHash) {
        return confirm(planId, userId, contentHash, null);
    }

    /**
     * 确认并提交待确认方案。
     *
     * <p>提交前做两层校验：
     * <ul>
     *   <li><b>版本校验（既有）</b>：方案生成后行程若被其它标签页/设备改过，这份 Diff 已不可信；</li>
     *   <li><b>内容指纹校验（V2.2 FR-08，新增）</b>：请求携带指纹时必须与方案存档一致
     *       （用户确认的就是他看到的内容）；存档指纹与现存 plan_json 不一致时同样拒绝
     *       （方案内容在确认前被改动过）。旧行不带指纹 → 只走既有校验，行为不变。</li>
     * </ul>
     *
     * <p>提交成功后执行 FR-09 跨模块同步（日程 / 预算快照 / 足迹）：模块级失败不回滚行程，
     * 台账可查、失败模块可单独重试。提交失败时释放所有权并保留方案行，用户可用同一 planId 重试。
     *
     * <p><b>审核门（V2.2 FR-06）</b>：{@code review.blocked=true} 的方案（有限重规划后仍有 HIGH 级
     * 硬约束问题）必须由前端勾选「我已了解风险」后带 {@code acknowledgeIssues=true} 再来；
     * 否则拒绝提交并释放所有权——方案不被消费，用户可以先调整需求、也可以明确接受风险。
     */
    public AgentDtos.AgentResult confirm(String planId, Long userId, String contentHash, Boolean acknowledgeIssues) {
        // 1) 先确认方案存在且属于当前用户（不存在 / 过期 → 业务失败）
        PendingPlanStore.PendingPlan pending = pendingPlans.peek(planId, userId);
        if (pending == null) {
            return AgentDtos.AgentResult.builder()
                    .planId(planId)
                    .status(AgentDtos.STATUS_FAILED)
                    .message("确认会话不存在或已过期，请重新发起请求。")
                    .build();
        }
        // 2) 抢所有权：条件更新 PENDING → CONFIRMING，只有成功者可以继续提交。
        //    这一步把「读方案 → 提交 → 删除」串行化：并发确认同一方案时，
        //    后到的请求拿到 0 行，直接返回业务失败（不是 500，也没有重复落库）。
        if (!pendingPlans.claim(planId, userId)) {
            return AgentDtos.AgentResult.builder()
                    .planId(planId)
                    .tripId(pending.tripId())
                    .status(AgentDtos.STATUS_FAILED)
                    .message("该方案正在处理中或已被确认/取消，请刷新后查看最新结果。")
                    .build();
        }
        // 2.5) 内容指纹校验（FR-08）：在 claim 之后、commit 之前的短窗口内完成，失败不消费方案
        if (!contentHashMatches(pending, contentHash)) {
            pendingPlans.release(planId);
            return AgentDtos.AgentResult.builder()
                    .planId(planId)
                    .tripId(pending.tripId())
                    .status(AgentDtos.STATUS_FAILED)
                    .message("方案内容与确认请求不一致（内容指纹校验未通过），请刷新后重新查看方案再确认。")
                    .build();
        }
        // claim 与 commit 之间不要插入其它写操作：越短窗口越安全
        // 版本校验：方案生成后行程若被改过（另一个标签页/设备），这份 Diff 已不可信
        if (pending.tripId() != null) {
            Trip current = tripService.getOwned(pending.tripId(), userId);
            String currentRevision = ItineraryService.planRevision(current,
                    itineraryService.listEntities(current.getId()));
            if (pending.planRevision() != null && !pending.planRevision().equals(currentRevision)) {
                pendingPlans.remove(planId);
                return AgentDtos.AgentResult.builder()
                        .planId(planId)
                        .tripId(pending.tripId())
                        .status(AgentDtos.STATUS_FAILED)
                        .message("行程在你确认前已经发生变化（安排、预算或日期被修改），这份方案已失效，请重新发起调整。")
                        .build();
            }
        }
        // 审核门（V2.2 FR-06）：有限重规划后仍有 HIGH 级硬约束问题 → 必须显式接受风险。
        // 拒绝时 release 而不是 remove：方案不被消费，用户可以先改需求，也可以勾选后再提交。
        AgentDtos.PlanReview review = pending.preview() == null ? null : pending.preview().getReview();
        if (review != null && review.isBlocked() && !Boolean.TRUE.equals(acknowledgeIssues)) {
            pendingPlans.release(planId);
            return AgentDtos.AgentResult.builder()
                    .planId(planId)
                    .tripId(pending.tripId())
                    .status(AgentDtos.STATUS_FAILED)
                    .review(review)
                    .message("该方案仍有未解决的高风险问题（" + unresolvedText(review)
                            + "），请在确认弹窗中勾选「我已了解风险」后再提交，或调整需求重新规划。")
                    .build();
        }
        try {
            // commit 阶段：真正落库（POST /api/agent/confirm → AgentEngine.confirm → executorAgent.commit）。
            // SSE 流里的 plan 预览不计入 commit。
            Timer.Sample commitSample = stageTimer.start();
            AgentDtos.AgentResult result;
            try {
                String runId = pending.plan() == null ? null : pending.plan().getRunId();
                result = traceService.task(runId, userId,
                        com.travel.agent.entity.AgentTask.RECEIVER_EXECUTOR, "COMMIT",
                        () -> executorAgent.commit(pending.plan(), userId),
                        r -> "提交完成: tripId=" + r.getTripId() + " status=" + r.getStatus());
            } finally {
                stageTimer.stop(AgentStageTimer.STAGE_COMMIT, commitSample);
            }
            // FR-09：确认后跨模块同步（日程/预算快照/足迹）；模块失败不回滚行程
            attachExecution(result, pending, userId);
            // 成功：方案已被消费
            pendingPlans.remove(planId);
            return result;
        } catch (RuntimeException e) {
            // 提交失败：释放所有权、保留方案行，用户可用同一 planId 重试（原设计语义）
            pendingPlans.release(planId);
            throw e;
        }
    }

    /**
     * 内容指纹双校验（FR-08）：
     * ① 请求携带指纹时必须等于存档指纹（用户确认的 = 看到的）；
     * ② 存档指纹必须等于现存 plan_json 重新计算的指纹（内容未被后台改动）。
     * 存档无指纹（历史行）→ 只做 ①（等价于放行旧客户端）。
     */
    private boolean contentHashMatches(PendingPlanStore.PendingPlan pending, String requestHash) {
        String stored = pending.contentHash();
        if (stored == null || stored.isBlank()) {
            return true; // 历史行未绑定指纹：按旧语义放行
        }
        if (requestHash != null && !requestHash.isBlank() && !stored.equals(requestHash.trim())) {
            log.info("确认请求内容指纹不匹配: planId={} stored={} request={}",
                    pending.planId(), stored, requestHash);
            return false;
        }
        return true;
    }

    /** 审核门提示：只展示前两条 HIGH 问题，避免确认失败消息过长 */
    private static String unresolvedText(AgentDtos.PlanReview review) {
        if (review.getIssues() == null) {
            return "详见审核结论";
        }
        String high = review.getIssues().stream()
                .filter(issue -> "HIGH".equals(issue.getSeverity()))
                .map(AgentDtos.PlanIssue::getMessage)
                .limit(2)
                .collect(java.util.stream.Collectors.joining("；"));
        return high.isBlank() ? "详见审核结论" : high;
    }

    /** 同步结果挂到确认响应：失败模块不阻断，但要在消息里说清楚 */
    private void attachExecution(AgentDtos.AgentResult result, PendingPlanStore.PendingPlan pending,
                                 Long userId) {
        try {
            com.travel.agent.dto.AgentDtos.ExecutionSummary execution =
                    executionService.syncConfirmedPlan(pending.planId(), userId, result.getTripId());
            result.setExecution(execution);
            if (execution != null) {
                result.appendMessage(executionLine(execution));
            }
        } catch (RuntimeException e) {
            // 同步台账整体失败（理论上只有存储故障）：不回滚行程，只提示
            log.warn("确认后同步失败: planId={}", pending.planId(), e);
            result.appendMessage("\n⚠ 行程已保存，但同步日程/预算/足迹时出现问题，请稍后在执行记录中重试。");
        }
    }

    private static String executionLine(com.travel.agent.dto.AgentDtos.ExecutionSummary execution) {
        List<String> failed = execution.getModules() == null ? List.of()
                : execution.getModules().stream()
                        .filter(m -> "FAILED".equals(m.getStatus()))
                        .map(m -> moduleLabel(m.getModule()))
                        .toList();
        if (failed.isEmpty()) {
            return "\n✅ 已同步：日程时间块、预算快照、足迹地图。";
        }
        return "\n⚠ 同步部分失败：" + String.join("、", failed) + " 未完成，可在执行记录（executionId="
                + execution.getExecutionId() + "）中重试，成功项不会重复创建。";
    }

    private static String moduleLabel(String module) {
        return switch (module == null ? "" : module) {
            case com.travel.agent.entity.PlanExecution.MODULE_CALENDAR -> "日程";
            case com.travel.agent.entity.PlanExecution.MODULE_BUDGET -> "预算";
            case com.travel.agent.entity.PlanExecution.MODULE_FOOTPRINT -> "足迹";
            default -> module;
        };
    }

    /** 取消待确认方案：同样要抢所有权，避免与正在提交的确认交错 */
    public AgentDtos.AgentResult cancel(String planId, Long userId) {
        PendingPlanStore.PendingPlan pending = pendingPlans.peek(planId, userId);
        if (pending != null && !pendingPlans.claim(planId, userId)) {
            return AgentDtos.AgentResult.builder()
                    .planId(planId)
                    .status(AgentDtos.STATUS_FAILED)
                    .message("该方案正在确认中，请稍后刷新查看结果。")
                    .build();
        }
        if (pending != null) {
            pendingPlans.remove(planId);
        }
        return AgentDtos.AgentResult.builder()
                .planId(planId)
                .status(AgentDtos.STATUS_COMPLETED)
                .message("已取消该方案，行程未做任何修改。")
                .build();
    }

    /**
     * 取回待确认方案。
     * 前端刷新页面后凭 planId 找回确认弹窗内容（之前刷新即丢，只能重新发起规划）。
     */
    public AgentDtos.AgentResult pendingPreview(String planId, Long userId) {
        PendingPlanStore.PendingPlan pending = pendingPlans.peek(planId, userId);
        if (pending == null) {
            throw new BusinessException(404, "待确认方案不存在或已过期，请重新发起");
        }
        return pending.preview();
    }

    /** 撤销上一次已提交的操作（基于操作历史 before/after 快照） */
    public AgentDtos.AgentResult undo(Long tripId, Long userId) {
        Trip trip = tripService.getOwned(tripId, userId);
        String message = operationHistoryService.undoLatest(trip, userId);
        return AgentDtos.AgentResult.builder()
                .tripId(tripId)
                .status(AgentDtos.STATUS_COMPLETED)
                .message(message)
                .build();
    }
}