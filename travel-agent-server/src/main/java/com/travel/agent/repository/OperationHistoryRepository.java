package com.travel.agent.repository;

import com.travel.agent.entity.OperationHistory;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface OperationHistoryRepository extends JpaRepository<OperationHistory, Long> {
    Optional<OperationHistory> findFirstByTripIdAndStatusOrderByIdDesc(Long tripId, String status);

    /** P1：回顾页的修改历史（只读，按时间倒序） */
    List<OperationHistory> findByTripIdOrderByIdDesc(Long tripId);

    void deleteByTripId(Long tripId);
}
