package com.travel.agent.repository;

import com.travel.agent.entity.PendingPlanRecord;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;

public interface PendingPlanRepository extends JpaRepository<PendingPlanRecord, String> {

    /** 清理过期方案，返回删除条数 */
    long deleteByCreatedAtBefore(LocalDateTime threshold);
}
