package com.travel.agent.service;

import com.travel.agent.agent.PendingPlanStore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * 过期确认租约的清扫（P0）。
 *
 * <p>问题：confirm 用 pending_plan.status 的条件更新抢所有权（PENDING → CONFIRMING）。
 * 进程若在「抢到所有权」与「提交完成」之间崩溃，行会永久停在 CONFIRMING——既不能被再次
 * claim，也没有调用者会 release，用户只能等 TTL 过期重来。
 *
 * <p>本任务把 {@code claimed_at} 早于「当前时间 - 租约时长」（默认 5 分钟）的 CONFIRMING
 * 改回 PENDING 并清空令牌；提交侧另有栅栏（deleteClaimed 必须影响 1 行）保证
 * 「被清扫后旧持有者再提交」会整体回滚，不会重复落库。
 *
 * <p>启动时立即执行一次：进程刚崩溃重启时不必再等一个调度周期。
 * 调度（触发）与清扫（业务）分开：测试只调 {@link #sweep()}，不依赖时钟。
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class PendingPlanRecoveryJob {

    private final PendingPlanStore pendingPlans;

    /** 确认租约时长（分钟）：超过该时长仍停在 CONFIRMING 视为处理者已崩溃 */
    @Value("${travel.pending-plan.lease-minutes:5}")
    private long leaseMinutes;

    /** 启动后立刻清扫一次（应用刚重启时最需要） */
    @EventListener(ApplicationReadyEvent.class)
    public void sweepOnStartup() {
        sweep();
    }

    /** 默认每 30 秒清扫一次；fixedDelay 表示上一轮结束后再计时，慢轮次不会堆积 */
    @Scheduled(fixedDelayString = "${travel.pending-plan.sweep-interval-ms:30000}")
    public void sweepScheduled() {
        sweep();
    }

    /**
     * 执行一轮清扫。
     *
     * @return 本轮从 CONFIRMING 恢复到 PENDING 的条数
     */
    public int sweep() {
        try {
            int swept = pendingPlans.sweepStale(LocalDateTime.now().minusMinutes(leaseMinutes));
            if (swept > 0) {
                // 只写条数：方案内容与用户标识不进日志
                log.info("确认租约清扫：{} 条过期 CONFIRMING 已回到 PENDING", swept);
            }
            return swept;
        } catch (RuntimeException e) {
            // 定时任务线程里异常只记账；清扫是幂等的，下一轮重试同一批数据
            log.warn("确认租约清扫失败，等待下一次触发重试：{}", e.toString());
            return 0;
        }
    }
}