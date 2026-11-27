package com.travel.agent.repository;

import com.travel.agent.entity.PendingPlanRecord;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;

public interface PendingPlanRepository extends JpaRepository<PendingPlanRecord, String> {

    /** 清理过期方案，返回删除条数 */
    long deleteByCreatedAtBefore(LocalDateTime threshold);

    /**
     * 抢占确认/取消所有权：把 PENDING 条件更新成 CONFIRMING，并写入本次租约令牌与时间。
     *
     * 一条 UPDATE 即完成「读状态 + 判定 + 写状态 + 加行锁」，返回 1 表示抢到所有权，
     * 返回 0 表示方案不存在 / 不属于该用户 / 已被别的请求抢走（或已确认、已取消）。
     * 并发场景下数据库行锁保证只有一个请求能看到 rowcount=1。
     *
     * 注意：JPQL 批量更新绕过 @Version，也不经过持久化上下文，所以调用方必须
     * 在自己的事务里（PendingPlanStore#claim 已标注 @Transactional），且不要在
     * claim 与 commit 之间插入其它写操作——那会把「抢占 → 提交」的原子窗口撑开。
     */
    @Modifying
    @Query("update PendingPlanRecord p set p.status = 'CONFIRMING', p.claimToken = :token, "
            + "p.claimedAt = :now "
            + "where p.planId = :planId and p.userId = :userId and p.status = 'PENDING'")
    int claim(@Param("planId") String planId, @Param("userId") Long userId,
              @Param("token") String token, @Param("now") LocalDateTime now);

    /**
     * 释放所有权：提交失败/被拒绝时把 CONFIRMING 放回 PENDING，并清空令牌与领取时间。
     *
     * 令牌必须匹配：如果这一行已被清扫或被别的请求重新 claim（新令牌），
     * 旧持有者不能把别人的租约释放掉——返回 0 表示本次释放无效。
     */
    @Modifying
    @Query("update PendingPlanRecord p set p.status = 'PENDING', p.claimToken = null, p.claimedAt = null "
            + "where p.planId = :planId and p.status = 'CONFIRMING' and p.claimToken = :token")
    int release(@Param("planId") String planId, @Param("token") String token);

    /**
     * 清扫过期租约：claimed_at 早于阈值（或缺失）的 CONFIRMING 回到 PENDING 并清空令牌。
     *
     * 崩溃恢复路径：进程在 claim 之后崩溃不会留下永久卡死的行；返回本轮清扫条数。
     * claimed_at 为 NULL 视为最早（V25 之前的历史 CONFIRMING 行也要能恢复）。
     */
    @Modifying
    @Query("update PendingPlanRecord p set p.status = 'PENDING', p.claimToken = null, p.claimedAt = null "
            + "where p.status = 'CONFIRMING' and (p.claimedAt is null or p.claimedAt < :threshold)")
    int sweepStale(@Param("threshold") LocalDateTime threshold);

    /**
     * 提交栅栏：只有「仍由本租约持有（CONFIRMING + 令牌匹配）」的方案行才能被删除。
     *
     * 返回 1 = 栅栏通过（本请求仍是唯一提交者），0 = 租约已被清扫/被重新抢走，
     * 调用方必须抛异常回滚整个提交事务，否则会在失效租约上重复落库。
     */
    @Modifying
    @Query("delete from PendingPlanRecord p "
            + "where p.planId = :planId and p.status = 'CONFIRMING' and p.claimToken = :token")
    int deleteClaimed(@Param("planId") String planId, @Param("token") String token);
}