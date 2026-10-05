package com.travel.agent.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * 待用户确认的 Agent 方案。
 *
 * 存库而不是放进程内存：确认请求可能被负载均衡打到任意实例，
 * 进程内存储在多实例下会直接失败（方案不在这台机器上）；落库同时获得重启不丢。
 */
@Entity
@Table(name = "pending_plan", indexes = {
        @Index(name = "idx_pending_plan_user", columnList = "user_id"),
        @Index(name = "idx_pending_plan_created", columnList = "created_at")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PendingPlanRecord {

    /** planId 由 PlannerAgent 生成，直接作为主键 */
    @Id
    @Column(name = "plan_id", length = 64)
    private String planId;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "trip_id")
    private Long tripId;

    @Column(name = "plan_json", nullable = false, columnDefinition = "TEXT")
    private String planJson;

    @Column(name = "preview_json", nullable = false, columnDefinition = "TEXT")
    private String previewJson;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;
}
