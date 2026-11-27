package com.travel.agent.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * 操作历史：AI 修改行程时先记录 before/after 快照，这是实现「撤销」的核心。
 */
@Entity
@Table(name = "operation_history", indexes = {
        @Index(name = "idx_history_trip_status", columnList = "trip_id,status"),
        // V31：日程域历史没有行程，撤销按 (user_id, domain, status) 取最近一条
        @Index(name = "idx_history_user_domain", columnList = "user_id,domain,status")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class OperationHistory {

    public static final String STATUS_ACTIVE = "ACTIVE";
    public static final String STATUS_UNDONE = "UNDONE";

    /** 撤销域：行程系历史（trip_id 非空） */
    public static final String DOMAIN_ITINERARY = "ITINERARY";
    /** 撤销域：日程系历史（trip_id 为空，按 user 撤销） */
    public static final String DOMAIN_SCHEDULE = "SCHEDULE";

    public static final String OP_AGENT_COMMIT = "AGENT_COMMIT";
    /** Agent 新建行程：撤销时应整体删除行程，而不是只清空条目 */
    public static final String OP_TRIP_CREATE = "TRIP_CREATE";
    /** 用户在界面上手工增删改行程项：同样进入撤销链 */
    public static final String OP_MANUAL_EDIT = "MANUAL_EDIT";
    /** Agent 确认后批量清空日程：before 是 ScheduleSnapshot 数组，撤销时重新插入 */
    public static final String OP_SCHEDULE_CLEAR = "SCHEDULE_CLEAR";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** V31 起可空：SCHEDULE 域的历史不挂在任何行程上 */
    @Column(name = "trip_id")
    private Long tripId;

    @Column(name = "user_id")
    private Long userId;

    /**
     * 撤销域（V31）：{@code ITINERARY} / {@code SCHEDULE}。
     * 老行由迁移默认成 ITINERARY；新行由 {@code @PrePersist} 兜底，读写两侧都不用猜。
     */
    @Column(length = 20)
    private String domain;

    @Column(name = "operation_type", length = 50)
    private String operationType;

    @Column(name = "before_state", columnDefinition = "TEXT")
    private String beforeState;

    @Column(name = "after_state", columnDefinition = "TEXT")
    private String afterState;

    @Column(name = "agent_reason", columnDefinition = "TEXT")
    private String agentReason;

    @Column(length = 20)
    private String status;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @PrePersist
    public void prePersist() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
        if (status == null) {
            status = STATUS_ACTIVE;
        }
        // 撤销域默认行程：既有写入方（recordCommit / recordTripCreate / withHistory）不需要逐个传
        if (domain == null) {
            domain = DOMAIN_ITINERARY;
        }
    }
}
