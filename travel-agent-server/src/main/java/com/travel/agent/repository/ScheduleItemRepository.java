package com.travel.agent.repository;

import com.travel.agent.entity.ScheduleItem;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ScheduleItemRepository extends JpaRepository<ScheduleItem, Long> {
    List<ScheduleItem> findByUserIdOrderByDateAscTimeAscIdAsc(Long userId);

    /** 某用户某日期范围内（含首尾）的全部日程：批量清空的范围查询与版本指纹共用 */
    List<ScheduleItem> findByUserIdAndDateBetweenOrderByDateAscTimeAscIdAsc(
            Long userId, java.time.LocalDate from, java.time.LocalDate to);

    /** Agent 确认同步的幂等键查询：同一 sourceKey 只创建一条（FR-09） */
    Optional<ScheduleItem> findBySourceKey(String sourceKey);

    /** 行程删除时清理该方案同步出来的日程（按前缀 plan-sync:{planId}:） */
    long deleteBySourceKeyStartingWith(String prefix);

    /** P1：按行程聚合的日程数量（回顾页） */
    long countByTripId(Long tripId);

    /** P1：行程删除时清理挂在该行程上的日程 */
    long deleteByTripId(Long tripId);
}
