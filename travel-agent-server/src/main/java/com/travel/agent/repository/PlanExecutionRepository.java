package com.travel.agent.repository;

import com.travel.agent.entity.PlanExecution;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface PlanExecutionRepository extends JpaRepository<PlanExecution, Long> {

    Optional<PlanExecution> findByExecutionIdAndUserId(String executionId, Long userId);

    /** 同一方案只允许一份逻辑执行（幂等键查询） */
    Optional<PlanExecution> findByPlanId(String planId);

    List<PlanExecution> findByTripId(Long tripId);

    List<PlanExecution> deleteByTripId(Long tripId);
}
