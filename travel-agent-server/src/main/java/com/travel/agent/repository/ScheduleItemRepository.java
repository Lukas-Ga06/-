package com.travel.agent.repository;

import com.travel.agent.entity.ScheduleItem;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ScheduleItemRepository extends JpaRepository<ScheduleItem, Long> {
    List<ScheduleItem> findByUserIdOrderByDateAscTimeAscIdAsc(Long userId);

    /** Agent 确认同步的幂等键查询：同一 sourceKey 只创建一条（FR-09） */
    Optional<ScheduleItem> findBySourceKey(String sourceKey);

    /** 行程删除时清理该方案同步出来的日程（按前缀 plan-sync:{planId}:） */
    long deleteBySourceKeyStartingWith(String prefix);
}
