package com.travel.agent.repository;

import com.travel.agent.entity.HealthRecord;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface HealthRecordRepository extends JpaRepository<HealthRecord, Long> {
    List<HealthRecord> findByUserIdOrderByDateDesc(Long userId);

    /** 最近 N 天记录分页（窗口过滤 + 排序都下推数据库） */
    Page<HealthRecord> findByUserIdAndDateGreaterThanEqual(Long userId, LocalDate since, Pageable pageable);

    Optional<HealthRecord> findByUserIdAndDate(Long userId, LocalDate date);

    /** 最近一条记录（首页摘要用） */
    Optional<HealthRecord> findFirstByUserIdOrderByDateDescIdDesc(Long userId);

    /** 窗口内记录天数 */
    long countByUserIdAndDateGreaterThanEqual(Long userId, LocalDate since);

    /** 窗口内步数均值（AVG 下推；无数据返回 null） */
    @Query("select avg(h.steps) from HealthRecord h where h.userId = :userId and h.date >= :since")
    Double averageStepsSince(@Param("userId") Long userId, @Param("since") LocalDate since);

    /** 窗口内睡眠均值（AVG 下推；无数据返回 null） */
    @Query("select avg(h.sleepHours) from HealthRecord h where h.userId = :userId and h.date >= :since")
    Double averageSleepHoursSince(@Param("userId") Long userId, @Param("since") LocalDate since);
}
