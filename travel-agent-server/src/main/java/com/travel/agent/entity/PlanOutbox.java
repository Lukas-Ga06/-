package com.travel.agent.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 事务性 Outbox：确认提交（行程落库）与「日程 / 预算快照 / 足迹」跨模块同步的待投递事件（P0）。
 *
 * <p>为什么要 Outbox：此前同步发生在提交之后、进程内尽力而为——进程在「行程已提交、
 * 同步未跑」之间崩溃，用户看到行程已保存但日程/预算/足迹静默缺失，且没有任何补投路径。
 * 现在确认提交在同一事务里写入三条 PENDING 事件（每个模块一条），由 {@code PlanOutboxDispatcher}
 * 至少一次投递、幂等消费：进程崩溃只会让事件留在表里，重启后补投。
 *
 * <p>不变量：
 * <ul>
 *   <li>{@code (plan_id, module)} 唯一：同一方案的同一模块只有一条待办，重复提交不会翻倍；</li>
 *   <li>投递器用「条件更新 PENDING → DISPATCHING，rowcount=1 才执行」领取，多实例不重复投递；</li>
 *   <li>失败不回退成 PENDING 立刻重试，而是写 {@code next_attempt_at} 指数退避；超过上限置 DEAD 保留证据；</li>
 *   <li>进程在 DISPATCHING 时崩溃：超过租约（默认 10 分钟）的行由投递器恢复成 PENDING 并累计 attempts。</li>
 * </ul>
 */
@Entity
@Table(name = "plan_outbox", uniqueConstraints = {
        @UniqueConstraint(name = "uk_plan_outbox_event", columnNames = "event_id"),
        @UniqueConstraint(name = "uk_plan_outbox_plan_module", columnNames = {"plan_id", "module"})
}, indexes = {
        @Index(name = "idx_plan_outbox_due", columnList = "status, next_attempt_at"),
        @Index(name = "idx_plan_outbox_trip", columnList = "trip_id")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PlanOutbox {

    /** 待投递：投递器只领 status=PENDING 且 next_attempt_at <= now 的行 */
    public static final String STATUS_PENDING = "PENDING";
    /** 已被某个投递器领走（执行中）；进程崩溃后由租约恢复 */
    public static final String STATUS_DISPATCHING = "DISPATCHING";
    /** 已确认完成（模块成功；或台账已记录成功后的重复投递） */
    public static final String STATUS_DONE = "DONE";
    /** 重试耗尽：保留行与 last_error 作为证据，等人工/运维介入，不再自动投递 */
    public static final String STATUS_DEAD = "DEAD";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 事件标识（UUID）：对外可追踪，与 (plan_id, module) 一起保证不重复入队 */
    @Column(name = "event_id", nullable = false, length = 64)
    private String eventId;

    /** 触发本次投递的方案（planId 即计划版本标识） */
    @Column(name = "plan_id", nullable = false, length = 64)
    private String planId;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "trip_id", nullable = false)
    private Long tripId;

    /** 同步模块：复用 {@link PlanExecution#MODULE_CALENDAR} / MODULE_BUDGET / MODULE_FOOTPRINT */
    @Column(name = "module", nullable = false, length = 32)
    private String module;

    /** 投递载荷（JSON）：当前只带定位信息，模块执行所需的计划本身来自台账/行程 */
    @Column(name = "payload_json", columnDefinition = "TEXT")
    private String payloadJson;

    @Column(name = "status", nullable = false, length = 16)
    private String status;

    /** 已失败次数（成功投递不增；租约恢复也算一次消耗） */
    @Column(name = "attempts", nullable = false)
    private int attempts;

    /** 下次可投递时间：失败后按指数退避写入，投递器只领已到期的行 */
    @Column(name = "next_attempt_at", nullable = false)
    private LocalDateTime nextAttemptAt;

    @Column(name = "last_error", length = 500)
    private String lastError;

    /** 领取时间：DISPATCHING 行的租约起点，用于崩溃恢复判断 */
    @Column(name = "claimed_at")
    private LocalDateTime claimedAt;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @PrePersist
    void prePersist() {
        LocalDateTime now = LocalDateTime.now();
        if (status == null) {
            status = STATUS_PENDING;
        }
        if (nextAttemptAt == null) {
            nextAttemptAt = now;
        }
        if (createdAt == null) {
            createdAt = now;
        }
    }
}