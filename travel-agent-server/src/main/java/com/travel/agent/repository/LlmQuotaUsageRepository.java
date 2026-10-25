package com.travel.agent.repository;

import com.travel.agent.entity.LlmQuotaUsage;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.Optional;

public interface LlmQuotaUsageRepository extends JpaRepository<LlmQuotaUsage, Long> {

    Optional<LlmQuotaUsage> findByUserNameAndUsageDate(String userName, LocalDate usageDate);

    /**
     * 原子自增（不存在则插入 1）。H2 MODE=MySQL 与 MySQL 均支持
     * INSERT ... ON DUPLICATE KEY UPDATE，配额判断在自增后回读，多实例共用同一行计数。
     */
    @Modifying
    @Query(value = "INSERT INTO llm_quota_usage (user_name, usage_date, used_count) "
            + "VALUES (:userName, :usageDate, 1) "
            + "ON DUPLICATE KEY UPDATE used_count = used_count + 1", nativeQuery = true)
    int increment(@Param("userName") String userName, @Param("usageDate") LocalDate usageDate);
}
