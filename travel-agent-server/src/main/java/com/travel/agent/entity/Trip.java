package com.travel.agent.entity;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;

@Entity
@Table(name = "trip", indexes = {
        @Index(name = "idx_trip_user", columnList = "user_id")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Trip {

    public static final String STATUS_PLANNING = "PLANNING";
    public static final String STATUS_CONFIRMED = "CONFIRMED";
    /** 用户取消的行程：仍保留记录，但不再计入足迹、不作为待出发行程 */
    public static final String STATUS_CANCELLED = "CANCELLED";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(length = 200)
    private String name;

    @Column(length = 200)
    private String destination;

    @Column(name = "start_date")
    private LocalDate startDate;

    @Column(name = "end_date")
    private LocalDate endDate;

    @Column(precision = 10, scale = 2)
    private BigDecimal budget;

    @Column(length = 30)
    private String status;

    /** 用户已处理（忽略）出行提醒的时间；为空表示仍会出现在提醒板 */
    @Column(name = "departure_reminder_dismissed_at")
    private LocalDateTime departureReminderDismissedAt;

    /**
     * 乐观锁版本：两个请求同时改同一行程时，后提交的一方会拿到
     * ObjectOptimisticLockingFailureException → 统一 409「请刷新后重试」，
     * 而不是静默覆盖（例如一个标签页改日期、另一个标签页同时提交 Agent 方案）。
     * columnDefinition 带 default 0：存量表由 ddl-auto 补列时能给已有行回填。
     */
    @Version
    @Column(name = "version", columnDefinition = "bigint default 0")
    private Long version;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @PrePersist
    public void prePersist() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
        if (status == null) {
            status = STATUS_PLANNING;
        }
    }

    /** 行程天数（含首尾） */
    public int totalDays() {
        if (startDate == null || endDate == null) {
            return 1;
        }
        return (int) Math.max(ChronoUnit.DAYS.between(startDate, endDate) + 1, 1);
    }

    /**
     * 业务阶段（由日期派生，不落库）：
     * CANCELLED 已取消 / PAST 已结束 / ONGOING 进行中 / UPCOMING 未开始。
     */
    public String phase(LocalDate today) {
        if (STATUS_CANCELLED.equals(status)) {
            return "CANCELLED";
        }
        if (startDate == null) {
            return "UPCOMING";
        }
        LocalDate day = today == null ? LocalDate.now() : today;
        if (endDate != null && day.isAfter(endDate)) {
            return "PAST";
        }
        if (day.isBefore(startDate)) {
            return "UPCOMING";
        }
        return "ONGOING";
    }
}
