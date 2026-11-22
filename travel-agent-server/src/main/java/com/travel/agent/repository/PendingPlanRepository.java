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
     * 抢占确认/取消所有权：把 PENDING 条件更新成 CONFIRMING。
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
    @Query("update PendingPlanRecord p set p.status = 'CONFIRMING' "
            + "where p.planId = :planId and p.userId = :userId and p.status = 'PENDING'")
    int claim(@Param("planId") String planId, @Param("userId") Long userId);

    /**
     * 释放所有权：提交失败时把 CONFIRMING 放回 PENDING，用户可用同一 planId 重试。
     * 返回 1 表示确实由 CONFIRMING 回退（说明本次释放有效）。
     */
    @Modifying
    @Query("update PendingPlanRecord p set p.status = 'PENDING' "
            + "where p.planId = :planId and p.status = 'CONFIRMING'")
    int release(@Param("planId") String planId);
}