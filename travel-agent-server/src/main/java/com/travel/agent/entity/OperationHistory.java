package com.travel.agent.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * 操作历史：AI 修改行程时先记录 before/after 快照，这是实现「撤销」的核心。
 */
@Entity
@Table(name = "operation_history", indexes = {
        @Index(name = "idx_history_trip_status", columnList = "trip_id,status")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class OperationHistory {

    public static final String STATUS_ACTIVE = "ACTIVE";
    public static final String STATUS_UNDONE = "UNDONE";

    public static final String OP_AGENT_COMMIT = "AGENT_COMMIT";
    /** Agent 新建行程：撤销时应整体删除行程，而不是只清空条目 */
    public static final String OP_TRIP_CREATE = "TRIP_CREATE";
    /** 用户在界面上手工增删改行程项：同样进入撤销链 */
    public static final String OP_MANUAL_EDIT = "MANUAL_EDIT";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "trip_id", nullable = false)
    private Long tripId;

    @Column(name = "user_id")
    private Long userId;

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
    }
}
