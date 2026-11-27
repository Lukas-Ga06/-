package com.travel.agent.service;

import com.travel.agent.dto.AgentDtos;
import com.travel.agent.entity.PlanOutbox;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 事务性 Outbox 的投递器（P0）。
 *
 * <p>一轮投递 = 「先做租约恢复，再按到期时间领取若干行，逐行条件领取并执行单模块」：
 * <ol>
 *   <li>恢复：把领取超过租约仍停在 DISPATCHING 的行改回可投递（进程崩溃路径）；</li>
 *   <li>领取：{@code UPDATE ... WHERE id=? AND status='PENDING'} 影响 1 行才执行，
 *       多实例同时跑也不会重复投递；</li>
 *   <li>执行：调用 {@link PlanExecutionService#executeDispatched(PlanOutbox)}（迟到补建台账）；</li>
 *   <li>收口：模块成功 → DONE；失败 → attempts+1 + 指数退避 + last_error，耗尽置 DEAD。</li>
 * </ol>
 *
 * <p>异常纪律：单行失败只影响该行；整轮异常也只在 {@link #scheduledDispatch()} 里吞掉，
 * 绝不让调度线程死掉（死掉的定时任务不会报错，只会「静默不再投递」）。
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class PlanOutboxDispatcher {

    private final PlanOutboxService outboxService;
    private final PlanExecutionService executionService;

    /** 每轮最多投递多少条：避免一轮拖太久、也避免把异常反复放大 */
    @Value("${travel.outbox.batch-size:20}")
    private int batchSize;

    /**
     * 定时投递：fixedDelay（上一轮结束后再等 5s），避免慢轮次堆积。
     * 启动后第一轮立即执行，顺带完成 DISPATCHING 行的崩溃恢复。
     */
    @Scheduled(fixedDelayString = "${travel.outbox.dispatch-interval-ms:5000}")
    public void scheduledDispatch() {
        try {
            dispatchDue();
        } catch (RuntimeException e) {
            // 调度线程兜底：本轮失败只记账，下一轮重试同一批到期行（投递是幂等的）
            log.warn("Outbox 投递轮次失败，等待下一次触发：{}", e.toString());
        }
    }

    /**
     * 可手动调用的一轮投递（测试 / 运维补投）。
     *
     * @return 本轮成功投递（置 DONE）的条数
     */
    public int dispatchDue() {
        recoverStaleClaims();
        List<PlanOutbox> due = outboxService.findDue(batchSize);
        int dispatched = 0;
        for (PlanOutbox row : due) {
            // 行可能已被并发实例领走：条件领取返回 false 就直接跳过
            if (!outboxService.claim(row.getId())) {
                continue;
            }
            try {
                AgentDtos.ExecutionSummary.ExecutionModule module = executionService.executeDispatched(row);
                if (isFailed(module)) {
                    // 模块失败已写进台账；这里只推进 Outbox 的退避状态
                    outboxService.markFailure(row.getId(), module.getError());
                } else {
                    outboxService.markDone(row.getId());
                    dispatched++;
                }
            } catch (RuntimeException e) {
                // 单行异常不外抛：记录失败并让退避机制接管，绝不影响同一轮的其它行
                log.warn("Outbox 投递异常: planId={} module={} err={}",
                        sanitize(row.getPlanId()), row.getModule(), e.toString());
                outboxService.markFailure(row.getId(), e.getMessage() == null ? e.toString() : e.getMessage());
            }
        }
        return dispatched;
    }

    /** 崩溃恢复：超过领取租约的 DISPATCHING 行重新排队（次数耗尽则置 DEAD） */
    private void recoverStaleClaims() {
        try {
            outboxService.recoverStaleClaims();
        } catch (RuntimeException e) {
            // 恢复失败不阻断本轮投递：仍然投递当前到期的 PENDING 行
            log.warn("Outbox 租约恢复失败：{}", e.toString());
        }
    }

    private static boolean isFailed(AgentDtos.ExecutionSummary.ExecutionModule module) {
        return module == null || "FAILED".equals(module.getStatus());
    }

    /** 用户可控值进日志前剥掉 CR/LF，防止伪造日志行 */
    private static String sanitize(String value) {
        return value == null ? null : value.replace("\r", "_").replace("\n", "_");
    }
}