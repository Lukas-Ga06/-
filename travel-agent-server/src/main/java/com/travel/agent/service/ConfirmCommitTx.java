package com.travel.agent.service;

import com.travel.agent.agent.AgentPlan;
import com.travel.agent.agent.ExecutorAgent;
import com.travel.agent.agent.PendingPlanStore;
import com.travel.agent.dto.AgentDtos;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 确认提交的「单事务 + 租约栅栏」（P0）。
 *
 * <p>为什么必须拆成独立 bean：确认链路有三步必须在<b>同一个数据库事务</b>里完成，
 * 而 Spring 的 {@code @Transactional} 是代理实现的——写在 {@code PlanConfirmationService}
 * 的方法里会被自调用绕过，也拿不到「提交失败后仍可执行 release」的边界。
 *
 * <p>事务内顺序与不变量：
 * <ol>
 *   <li>{@code executorAgent.commit}：行程/条目/操作历史落库（本身 REQUIRED，加入本事务）；</li>
 *   <li>同事务写入本次方案的 3 条 {@code plan_outbox(PENDING)}：崩溃不会丢同步工作；</li>
 *   <li><b>提交栅栏</b>：{@code deleteClaimed(planId, token)} 必须影响 1 行。
 *       若方案行已被清扫改回 PENDING、或被别的请求重新 claim（令牌不同），这里拿到 0 行 →
 *       抛异常回滚整个事务，行程/台账/Outbox 一并消失，绝不重复落库。
 *       栅栏放在最后，是对「本次租约仍然有效」的最终裁决。</li>
 * </ol>
 *
 * <p>事务抛异常后由调用方（{@code PlanConfirmationService}）用同一令牌 release：
 * 令牌不匹配时 release 是空操作，不会误伤别人的租约。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ConfirmCommitTx {

    private final ExecutorAgent executorAgent;
    private final PlanOutboxService outboxService;
    private final PendingPlanStore pendingPlanStore;

    /**
     * 提交确认方案：行程落库 + Outbox 入队 + 租约栅栏，三者同一事务。
     *
     * @param plan       待提交方案（含 tripDraft 或 tripId）
     * @param userId     方案所有者
     * @param planId     待确认方案标识（栅栏与入队都用它）
     * @param claimToken 本次 claim 发放的租约令牌；栅栏按它判断本次提交是否仍然有效
     * @throws LeaseLostException 租约已失效（已回滚，无任何落库）
     */
    @Transactional
    public AgentDtos.AgentResult commit(AgentPlan plan, Long userId, String planId, String claimToken) {
        AgentDtos.AgentResult result = executorAgent.commit(plan, userId);
        int enqueued = outboxService.enqueueAll(planId, userId, result.getTripId());
        if (!pendingPlanStore.deleteClaimed(planId, claimToken)) {
            // 必须抛出（而不是返回 false）：只有异常才能让上面的 commit/outbox 一起回滚
            throw new LeaseLostException(planId);
        }
        log.debug("确认提交事务完成: planId={} tripId={} outbox={}",
                sanitize(planId), result.getTripId(), enqueued);
        return result;
    }

    /** 租约失效：提交事务已整体回滚，调用方应把方案放回可重试状态或提示用户刷新 */
    public static class LeaseLostException extends RuntimeException {
        public LeaseLostException(String planId) {
            super("确认租约已失效（方案已被清扫或重新抢占），本次提交已回滚: " + planId);
        }
    }

    /** 用户可控的 planId 进日志前剥掉 CR/LF，防止伪造日志行 */
    private static String sanitize(String value) {
        return value == null ? null : value.replace("\r", "_").replace("\n", "_");
    }
}