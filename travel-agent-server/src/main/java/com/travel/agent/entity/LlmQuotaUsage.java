package com.travel.agent.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDate;

/** LLM 每日按用户配额计数（用户名 + 日期唯一，计数原子递增） */
@Entity
@Table(name = "llm_quota_usage", indexes = {
        @Index(name = "idx_llm_quota_date", columnList = "usage_date")
}, uniqueConstraints = {
        @UniqueConstraint(name = "uk_llm_quota_user_date", columnNames = {"user_name", "usage_date"})
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class LlmQuotaUsage {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_name", length = 64, nullable = false)
    private String userName;

    @Column(name = "usage_date", nullable = false)
    private LocalDate usageDate;

    @Column(name = "used_count", nullable = false)
    private int usedCount;
}
