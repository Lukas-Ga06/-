package com.travel.agent.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * Agent 运行内的一个任务（V2.2 FR-04 的最小任务记录）。
 *
 * <p>一个 run 内的每个阶段（意图识别 / 研究 / 规划 / 预览 / 审核 / 重规划）对应一条 task：
 * receiver 是执行该阶段的 Agent 名（intent-agent / research-agent / planner-agent /
 * executor-agent / reflect-agent），状态流转 RUNNING → SUCCEEDED / FAILED，
 * 前置任务未完成时拒绝启动并记一条 CONFLICT。
 *
 * <p>第十五批（FR-02/FR-04）把「阶段记录」升级成「任务图节点」：新增
 * {@code dependsOn}（前置任务 id）、{@code attempt}（第几次尝试）与
 * {@code replacesTaskId}（被本次重派替换掉的任务）。依赖边由
 * {@code Coordinator} 在派发前强制校验——任务能被记录，也能被编排。
 */
@Entity
@Table(name = "agent_task", indexes = {
        @Index(name = "idx_agent_task_run", columnList = "run_id, id")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AgentTask {

    public static final String STATUS_RUNNING = "RUNNING";
    public static final String STATUS_SUCCEEDED = "SUCCEEDED";
    public static final String STATUS_FAILED = "FAILED";
    /** 前置任务未完成（或被拒绝对重派）：本次任务没有执行，只留冲突证据（第十五批） */
    public static final String STATUS_CONFLICT = "CONFLICT";

    /** 任务接收方（Agent 名），与设计文档 5.4 协议中的 receiver 对齐 */
    public static final String RECEIVER_INTENT = "intent-agent";
    public static final String RECEIVER_RESEARCH = "research-agent";
    public static final String RECEIVER_PLANNER = "planner-agent";
    public static final String RECEIVER_EXECUTOR = "executor-agent";
    public static final String RECEIVER_REVIEWER = "reflect-agent";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "task_id", nullable = false, length = 64, unique = true)
    private String taskId;

    @Column(name = "run_id", nullable = false, length = 64)
    private String runId;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(nullable = false, length = 50)
    private String receiver;

    /** 任务类型：INTENT / RESEARCH / PLAN / PREVIEW / REVIEW / TOOL / COMMIT */
    @Column(name = "task_type", nullable = false, length = 50)
    private String taskType;

    @Column(nullable = false, length = 16)
    private String status;

    /** 任务结果摘要（截断存储，例如「规划出 12 项操作」） */
    @Column(length = 1000)
    private String summary;

    @Column(length = 500)
    private String error;

    /**
     * 前置任务 id 列表（逗号分隔存储；DTO 对外是字符串数组）。
     *
     * <p>第十五批：只有全部前置任务 SUCCEEDED 时才允许启动本任务；
     * 依赖边是「任务图可查询」的依据，也是 Coordinator 拒绝启动的依据。
     */
    @Column(name = "depends_on", length = 1000)
    private String dependsOn;

    /** 第几次尝试：0 = 首次；1、2… = 重派（与「第 N 次重规划」口径一致） */
    @Column(nullable = false)
    private Integer attempt;

    /** 本次任务替换掉的旧任务 id（失败/冲突/被重规划取代的旧版本）；首次为 null */
    @Column(name = "replaces_task_id", length = 64)
    private String replacesTaskId;

    @Column(name = "started_at", nullable = false)
    private LocalDateTime startedAt;

    @Column(name = "ended_at")
    private LocalDateTime endedAt;

    @PrePersist
    void prePersist() {
        if (status == null) {
            status = STATUS_RUNNING;
        }
        if (attempt == null) {
            attempt = 0;
        }
        if (startedAt == null) {
            startedAt = LocalDateTime.now();
        }
    }

    /** 前置任务 id 列表（空列表 = 无前置） */
    public java.util.List<String> dependencies() {
        if (dependsOn == null || dependsOn.isBlank()) {
            return java.util.List.of();
        }
        return java.util.Arrays.stream(dependsOn.split(","))
                .map(String::trim)
                .filter(id -> !id.isEmpty())
                .toList();
    }

    /** 依赖边落库格式（与 {@link #dependencies()} 对称；空列表存 null） */
    public static String joinDependencies(java.util.List<String> taskIds) {
        if (taskIds == null || taskIds.isEmpty()) {
            return null;
        }
        return String.join(",", taskIds);
    }

    public Long durationMs() {
        if (startedAt == null || endedAt == null) {
            return null;
        }
        return java.time.Duration.between(startedAt, endedAt).toMillis();
    }
}
