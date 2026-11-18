package com.travel.agent.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * Agent 运行内的一个任务（V2.2 FR-04 的最小任务记录）。
 *
 * <p>当前阶段化编排里，一个 run 内的每个阶段（意图识别 / 研究 / 规划 / 预览 / 审核）
 * 对应一条 task：receiver 是执行该阶段的 Agent 名（intent-agent / research-agent /
 * planner-agent / executor-agent / reflect-agent），状态流转 RUNNING → SUCCEEDED / FAILED。
 * 这是任务协议的第一块地基：后续把阶段升级为可委派、可依赖的任务时，
 * 表结构与查询链路（按 run 聚合任务链）不需要推翻。
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

    @Column(name = "started_at", nullable = false)
    private LocalDateTime startedAt;

    @Column(name = "ended_at")
    private LocalDateTime endedAt;

    @PrePersist
    void prePersist() {
        if (status == null) {
            status = STATUS_RUNNING;
        }
        if (startedAt == null) {
            startedAt = LocalDateTime.now();
        }
    }

    public Long durationMs() {
        if (startedAt == null || endedAt == null) {
            return null;
        }
        return java.time.Duration.between(startedAt, endedAt).toMillis();
    }
}
