package com.travel.agent.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * 一次 Agent 运行的生命周期记录（V2.2 FR-02 / FR-10）。
 *
 * <p>每次用户对话（同步 chat / SSE stream）都会创建一条 run：记录输入摘要、
 * 识别出的意图、最终状态与耗时。runId 同时写进 {@code AgentContext}，
 * {@code ToolInvoker} 据此把每次工具调用挂到对应 run 上，形成可查询的执行追踪。
 *
 * <p>写入策略：{@code AgentTraceService} 的方法用 REQUIRES_NEW 独立事务，
 * 主流程半途失败时已产生的 run/task/trace 仍然落库——「失败也要留痕」
 * 是追踪表的本职（否则超时/异常轮次在追踪里完全消失）。
 */
@Entity
@Table(name = "agent_run", indexes = {
        @Index(name = "idx_agent_run_user", columnList = "user_id, id"),
        @Index(name = "uk_agent_run_run_id", columnList = "run_id", unique = true)
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AgentRun {

    public static final String STATUS_RUNNING = "RUNNING";
    public static final String STATUS_SUCCEEDED = "SUCCEEDED";
    public static final String STATUS_FAILED = "FAILED";
    public static final String STATUS_CANCELLED = "CANCELLED";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 对外暴露的运行标识（UUID），SSE 与 REST API 都用它引用这次运行 */
    @Column(name = "run_id", nullable = false, length = 64, unique = true)
    private String runId;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "trip_id")
    private Long tripId;

    /** 用户输入摘要（截断存储，避免长攻略文本整篇入库） */
    @Column(length = 500)
    private String message;

    /** 识别出的意图（PLAN_TRIP / MODIFY_ITINERARY / CHITCHAT…） */
    @Column(length = 50)
    private String intent;

    @Column(nullable = false, length = 16)
    private String status;

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

    /** 运行耗时（毫秒）；未结束返回 null */
    public Long durationMs() {
        if (startedAt == null || endedAt == null) {
            return null;
        }
        return java.time.Duration.between(startedAt, endedAt).toMillis();
    }
}
