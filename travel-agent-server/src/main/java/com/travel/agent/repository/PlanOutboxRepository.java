package com.travel.agent.repository;

import com.travel.agent.entity.PlanOutbox;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface PlanOutboxRepository extends JpaRepository<PlanOutbox, Long> {

    Optional<PlanOutbox> findByPlanIdAndModule(String planId, String module);

    /** 入队前先查重，避免唯一键异常把调用方事务标记成 rollback-only */
    boolean existsByPlanIdAndModule(String planId, String module);

    boolean existsByPlanId(String planId);

    List<PlanOutbox> findByPlanIdOrderByIdAsc(String planId);

    List<PlanOutbox> findByPlanIdAndStatusInOrderByIdAsc(String planId, Collection<String> statuses);

    /** 行程删除时清理该行程的待投递事件（与执行台账/同步日程同一事务） */
    long deleteByTripId(Long tripId);

    /**
     * 到期待投递行：只领 PENDING 且 next_attempt_at <= now 的行，按到期时间与 id 排序。
     * 排序目的是让退避中的行不会插队，同时给分页一个稳定顺序。
     */
    @Query("select o from PlanOutbox o where o.status = 'PENDING' and o.nextAttemptAt <= :now "
            + "order by o.nextAttemptAt asc, o.id asc")
    List<PlanOutbox> findDue(@Param("now") LocalDateTime now, Pageable pageable);

    /**
     * 某方案当前可以立刻执行的行：
     * <ul>
     *   <li>PENDING 且已到期——确认请求内的同步走这里；</li>
     *   <li>DISPATCHING 且领取时间超过租约——投递器崩溃后被接管。</li>
     * </ul>
     * PENDING 但仍在退避窗口内的行<b>不会</b>被同步路径绕过退避提前执行。
     */
    @Query("select o from PlanOutbox o where o.planId = :planId and ("
            + "(o.status = 'PENDING' and o.nextAttemptAt <= :now) or "
            + "(o.status = 'DISPATCHING' and (o.claimedAt is null or o.claimedAt < :staleBefore))) "
            + "order by o.id asc")
    List<PlanOutbox> findDispatchableForPlan(@Param("planId") String planId,
                                             @Param("now") LocalDateTime now,
                                             @Param("staleBefore") LocalDateTime staleBefore);

    /**
     * 条件领取：只有仍处于 PENDING 的行会被改成 DISPATCHING（rowcount=1 才算领到）。
     * 这是多实例不重复投递的唯一依据，不能改成「先查再改」。
     */
    @Modifying
    @Query("update PlanOutbox o set o.status = 'DISPATCHING', o.claimedAt = :now, o.updatedAt = :now "
            + "where o.id = :id and o.status = 'PENDING'")
    int claim(@Param("id") Long id, @Param("now") LocalDateTime now);

    /**
     * 接管崩溃残留：领取者已消失、且领取时间超过租约的 DISPATCHING 行可以被重新领取（attempts+1）。
     * 与 {@link #recoverStaleClaims} 的区别：这里不先改回 PENDING，而是直接由本次调用者继续执行，
     * 供确认请求内的同步路径接管「上一任投递器崩溃」的行。
     */
    @Modifying
    @Query("update PlanOutbox o set o.status = 'DISPATCHING', o.claimedAt = :now, "
            + "o.attempts = o.attempts + 1, o.updatedAt = :now "
            + "where o.id = :id and o.status = 'DISPATCHING' "
            + "and (o.claimedAt is null or o.claimedAt < :staleBefore) and o.attempts + 1 < :maxAttempts")
    int takeoverStale(@Param("id") Long id,
                      @Param("staleBefore") LocalDateTime staleBefore,
                      @Param("now") LocalDateTime now,
                      @Param("maxAttempts") int maxAttempts);

    /**
     * 崩溃恢复：领取超过租约仍停在 DISPATCHING 的行改回 PENDING，attempts+1，立刻到期。
     * 只处理 attempt+1 仍未耗尽的行；耗尽的那部分由 {@link #deadLetterStaleClaims} 置 DEAD。
     */
    @Modifying
    @Query("update PlanOutbox o set o.status = 'PENDING', o.claimedAt = null, o.attempts = o.attempts + 1, "
            + "o.nextAttemptAt = :now, o.updatedAt = :now "
            + "where o.status = 'DISPATCHING' and (o.claimedAt is null or o.claimedAt < :staleBefore) "
            + "and o.attempts + 1 < :maxAttempts")
    int recoverStaleClaims(@Param("staleBefore") LocalDateTime staleBefore,
                           @Param("now") LocalDateTime now,
                           @Param("maxAttempts") int maxAttempts);

    /**
     * 崩溃恢复的失败收口：领取超时且恢复后次数已达上限的行直接置 DEAD，
     * 避免「每轮恢复 → 再崩溃」把同一行无限复活。
     */
    @Modifying
    @Query("update PlanOutbox o set o.status = 'DEAD', o.claimedAt = null, o.attempts = o.attempts + 1, "
            + "o.lastError = :lastError, o.updatedAt = :now "
            + "where o.status = 'DISPATCHING' and (o.claimedAt is null or o.claimedAt < :staleBefore) "
            + "and o.attempts + 1 >= :maxAttempts")
    int deadLetterStaleClaims(@Param("staleBefore") LocalDateTime staleBefore,
                              @Param("now") LocalDateTime now,
                              @Param("lastError") String lastError,
                              @Param("maxAttempts") int maxAttempts);
}