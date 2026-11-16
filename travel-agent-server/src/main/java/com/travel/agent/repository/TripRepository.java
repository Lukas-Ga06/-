package com.travel.agent.repository;

import com.travel.agent.entity.Trip;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface TripRepository extends JpaRepository<Trip, Long> {
    List<Trip> findByUserIdOrderByCreatedAtDesc(Long userId);

    /** 行程列表分页：排序由 Pageable 的 Sort 决定（created_at desc, id desc） */
    Page<Trip> findByUserId(Long userId, Pageable pageable);

    Optional<Trip> findByIdAndUserId(Long id, Long userId);
}
