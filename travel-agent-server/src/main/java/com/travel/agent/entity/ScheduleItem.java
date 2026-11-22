package com.travel.agent.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

/** 日程条目：办公自动化模块 */
@Entity
@Table(name = "schedule_item",
        indexes = {
                @Index(name = "idx_schedule_user", columnList = "user_id")
        },
        uniqueConstraints = {
                // V22：同步日程的幂等键。生产库由迁移脚本创建同名唯一索引；
                // 测试库（ddl-auto=create-drop，Flyway 关闭）由这里创建，两边必须一致。
                // 手工日程 source_key 为 NULL，唯一索引允许多个 NULL，不受影响。
                @UniqueConstraint(name = "uk_schedule_source_key", columnNames = "source_key")
        })
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ScheduleItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(nullable = false, length = 200)
    private String title;

    @Column(name = "sched_date", nullable = false)
    private LocalDate date;

    @Column(name = "sched_time")
    private LocalTime time;

    @Column(length = 300)
    private String note;

    @Column(nullable = false)
    private boolean done;

    /** NONE / DAILY / WEEKLY / MONTHLY；date 为首次发生日期 */
    @Column(length = 10)
    private String recurrence;

    /** 重复结束日期（含），可为空表示长期重复 */
    @Column(name = "repeat_until")
    private LocalDate repeatUntil;

    /** 下次发生日期（列表展示用，不落库） */
    @Transient
    private LocalDate nextOccurrence;

    /** 用户已处理（忽略）该日程提醒的时间；为空表示仍会出现在提醒板 */
    @Column(name = "reminder_dismissed_at")
    private LocalDateTime reminderDismissedAt;

    /**
     * 来源幂等键（V2.2 FR-09）：Agent 确认同步生成的日程写
     * {@code plan-sync:{planId}:day:{n}}；唯一索引兜底并发重复。
     * 手工创建的日程为 NULL（唯一索引允许多个 NULL，不受影响）。
     */
    @Column(name = "source_key", length = 100)
    private String sourceKey;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    /** 关联行程（P1，可空）：出行相关的日程可按 tripId 聚合到行程详情 */
    @Column(name = "trip_id")
    private Long tripId;

    /** 关联行程项（可空）：例如「提醒办理签证」挂在出发日条目上 */
    @Column(name = "itinerary_item_id")
    private Long itineraryItemId;

    /** 来源：MANUAL / AGENT / PLAN_SYNC，用于区分手工与方案同步生成 */
    @Column(length = 30)
    private String source;

    /** 行程第几天（1-based），仅当 tripId 非空时有意义 */
    @Column(name = "travel_day")
    private Integer travelDay;

    @PrePersist
    public void prePersist() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
    }
}
