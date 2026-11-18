package com.travel.agent.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * 一次「用户确认方案」的跨模块同步台账（V2.2 FR-09）。
 *
 * <p>设计文档 7.4 的简化落地（同库单事务 + 幂等键，而非跨库 Outbox）：
 * <ul>
 *   <li><b>一份逻辑执行</b>：plan_id 唯一约束——同一方案无论确认/重试多少次，只有一行执行记录；
 *   重复确认在 PendingPlanStore.claim 层已被拦截，这里的唯一约束是第二道防线；</li>
 *   <li><b>模块级状态</b>：modulesJson 记录日程 / 预算 / 足迹逐个模块的
 *   SUCCEEDED / FAILED 与详情（部分失败可单独重试，成功项不重复创建）；</li>
 *   <li><b>口径红线</b>：预算模块只记录计划快照，绝不自动生成 Expense（实际支出必须手动记账）；
 *   足迹模块不把「计划去」写成「已到访」——足迹仍由行程与日期派生。</li>
 * </ul>
 */
@Entity
@Table(name = "plan_execution", indexes = {
        @Index(name = "idx_plan_execution_user", columnList = "user_id, id"),
        @Index(name = "idx_plan_execution_trip", columnList = "trip_id"),
        @Index(name = "uk_plan_execution_plan_id", columnList = "plan_id", unique = true)
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PlanExecution {

    public static final String STATUS_SUCCEEDED = "SUCCEEDED";
    public static final String STATUS_PARTIAL_FAILED = "PARTIAL_FAILED";
    public static final String STATUS_FAILED = "FAILED";

    /** 同步模块名（modulesJson 里的 module 字段取值） */
    public static final String MODULE_CALENDAR = "calendar";
    public static final String MODULE_BUDGET = "budget";
    public static final String MODULE_FOOTPRINT = "footprint";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 对外执行标识（UUID）：重试与查询端点都用它 */
    @Column(name = "execution_id", nullable = false, length = 64, unique = true)
    private String executionId;

    /** 触发本次执行的方案（planId 即计划版本标识） */
    @Column(name = "plan_id", nullable = false, length = 64, unique = true)
    private String planId;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "trip_id")
    private Long tripId;

    @Column(nullable = false, length = 20)
    private String status;

    /** 逐模块执行结果（JSON 数组：module/status/detail/idempotencyKey/retryCount/error） */
    @Column(name = "modules_json", columnDefinition = "TEXT")
    private String modulesJson;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @PrePersist
    void prePersist() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
    }
}
