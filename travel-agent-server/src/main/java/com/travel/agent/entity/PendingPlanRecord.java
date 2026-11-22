package com.travel.agent.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * 待用户确认的 Agent 方案。
 *
 * 存库而不是放进程内存：确认请求可能被负载均衡打到任意实例，
 * 进程内存储在多实例下会直接失败（方案不在这台机器上）；落库同时获得重启不丢。
 *
 * status 是确认链路的「所有权令牌」：confirm/cancel 先用一条条件 UPDATE 把
 * PENDING 改成 CONFIRMING，只有改成功（影响行数 = 1）的请求才允许提交，
 * 从而把「peek → 校验 → commit → remove」四步收敛成单点串行化。
 * 并发确认同一 plan 时，第二个请求拿到 0 行 → 返回业务失败，而不是重复落库。
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

    /** 等待用户确认：可被 claim */
    public static final String STATUS_PENDING = "PENDING";
    /** 已被某个请求抢到所有权（确认/取消进行中） */
    public static final String STATUS_CONFIRMING = "CONFIRMING";

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

    /** 生成方案时的行程指纹：确认时比对，防止按过期 Diff 提交 */
    @Column(name = "plan_revision", length = 64)
    private String planRevision;

    /**
     * 方案内容指纹（plan_json 的 SHA-256，V2.2 FR-08）。
     *
     * <p>与 {@code planRevision} 的分工：planRevision 是「当时行程状态」的指纹
     * （行程被别的入口改过 → 拒绝）；contentHash 是「方案内容本身」的指纹
     * （确认请求看到的内容与库里现存内容必须一致 → 拒绝后台悄悄换内容）。
     * 存量行为 NULL：视为未绑定指纹，只走既有校验（向后兼容）。
     */
    @Column(name = "content_hash", length = 64)
    private String contentHash;

    /**
     * 确认状态（PENDING / CONFIRMING）。
     *
     * 用 columnDefinition 带 DEFAULT：存量表由 ddl-auto 补列时，MySQL/H2 的
     * 「ADD COLUMN ... NOT NULL」必须有默认值才能给已存在的行回填，否则加列直接失败。
     * 默认值同时让「补列后旧行」自动变成 PENDING，可以被条件更新抢到。
     */
    @Column(name = "status", nullable = false, length = 16,
            columnDefinition = "varchar(16) default 'PENDING'")
    private String status;

    /** 乐观锁：条件更新之外的第二道防线（同一行被并发 save 时直接失败） */
    @Version
    @Column(name = "version", columnDefinition = "bigint default 0")
    private Long version;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @PrePersist
    void prePersist() {
        if (status == null) {
            status = STATUS_PENDING;
        }
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
    }
}