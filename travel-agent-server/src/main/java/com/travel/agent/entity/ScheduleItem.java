package com.travel.agent.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

/** 日程条目：办公自动化模块 */
@Entity
@Table(name = "schedule_item", indexes = {
        @Index(name = "idx_schedule_user", columnList = "user_id")
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

    /** 用户已处理（忽略）该日程提醒的时间；为空表示仍会出现在提醒板 */
    @Column(name = "reminder_dismissed_at")
    private LocalDateTime reminderDismissedAt;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @PrePersist
    public void prePersist() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
    }
}
