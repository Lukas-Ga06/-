package com.travel.agent.repository;

import com.travel.agent.entity.OperationHistory;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface OperationHistoryRepository extends JpaRepository<OperationHistory, Long> {
    Optional<OperationHistory> findFirstByTripIdAndStatusOrderByIdDesc(Long tripId, String status);

    void deleteByTripId(Long tripId);
}
