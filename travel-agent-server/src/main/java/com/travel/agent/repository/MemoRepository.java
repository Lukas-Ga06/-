package com.travel.agent.repository;

import com.travel.agent.entity.Memo;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface MemoRepository extends JpaRepository<Memo, Long> {
    /** 未完成在前，其余按创建时间倒序 */
    List<Memo> findByUserIdOrderByDoneAscCreatedAtDesc(Long userId);

    /** 备忘列表分页：排序由 Pageable 的 Sort 决定（done asc, created_at desc） */
    Page<Memo> findByUserId(Long userId, Pageable pageable);

    /** 待办数量（COUNT 下推，不再先取全量再 filter） */
    long countByUserIdAndDoneFalse(Long userId);

    /** P1：按行程聚合的备忘数量（回顾页） */
    long countByTripId(Long tripId);

    /** P1：行程删除时清理挂在该行程上的备忘 */
    long deleteByTripId(Long tripId);
}
