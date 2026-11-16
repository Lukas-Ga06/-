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

    /**
     * 确认并提交待确认方案。
     *
     * <p>提交前做版本校验：方案生成后行程若被其它标签页/设备改过，这份 Diff 已不可信。
     * 提交失败时释放所有权并保留方案行，用户可用同一 planId 重试。
     */
    public AgentDtos.AgentResult confirm(String planId, Long userId) {
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
        try {
            // commit 阶段：真正落库（POST /api/agent/confirm → AgentEngine.confirm → executorAgent.commit）。
            // SSE 流里的 plan 预览不计入 commit。
            Timer.Sample commitSample = stageTimer.start();
            AgentDtos.AgentResult result;
            try {
                result = executorAgent.commit(pending.plan(), userId);
            } finally {
                stageTimer.stop(AgentStageTimer.STAGE_COMMIT, commitSample);
            }
            // 成功：方案已被消费
            pendingPlans.remove(planId);
            return result;
        } catch (RuntimeException e) {
            // 提交失败：释放所有权、保留方案行，用户可用同一 planId 重试（原设计语义）
            pendingPlans.release(planId);
            throw e;
        }
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