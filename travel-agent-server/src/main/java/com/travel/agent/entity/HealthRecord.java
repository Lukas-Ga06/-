package com.travel.agent.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDate;
import java.time.LocalDateTime;

/** 健康日报（小米手环等设备数据导入/手动记录） */
@Entity
@Table(name = "health_record", indexes = {
        @Index(name = "idx_health_user_date", columnList = "user_id,record_date")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class HealthRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "record_date", nullable = false)
    private LocalDate date;

    @Column(name = "steps")
    private Integer steps;

    @Column(name = "sleep_hours")
    private Double sleepHours;

    @Column(name = "resting_heart_rate")
    private Integer restingHeartRate;

    @Column(name = "weight")
    private Double weight;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @PrePersist
    public void prePersist() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
    }
}
