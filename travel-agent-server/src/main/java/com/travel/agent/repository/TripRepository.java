package com.travel.agent.repository;

import com.travel.agent.entity.Trip;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

public interface TripRepository extends JpaRepository<Trip, Long> {
    List<Trip> findByUserIdOrderByCreatedAtDesc(Long userId);

    /** 行程列表分页：排序由 Pageable 的 Sort 决定（created_at desc, id desc） */
    Page<Trip> findByUserId(Long userId, Pageable pageable);

    Optional<Trip> findByIdAndUserId(Long id, Long userId);

    /** 计划成本口径（FR-09 预算快照）：行程条目 cost 合计，与 GET /api/trips/{id}/budget 的 planned 一致 */
    @Query("select coalesce(sum(i.cost), 0) from ItineraryItem i where i.tripId = :tripId")
    BigDecimal sumItineraryCost(@Param("tripId") Long tripId);
}
