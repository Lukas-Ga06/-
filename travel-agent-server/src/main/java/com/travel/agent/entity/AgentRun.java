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
 *
 * <p>P0 起额外维护运行状态机快照：{@link #stage}（当前阶段）、{@link #heartbeatAt}
 * （最近一次阶段推进时间）、{@link #requestId}（客户端幂等标识，SSE 重连据此找回原 run）。
 * 进程崩溃后，恢复任务按心跳超时把 RUNNING 判为 {@link #STATUS_ABANDONED}，
 * 不再让「实际上已经死掉」的运行永远显示为运行中。
 */
@Entity
@Table(name = "agent_run", indexes = {
        @Index(name = "idx_agent_run_user", columnList = "user_id, id"),
        @Index(name = "uk_agent_run_run_id", columnList = "run_id", unique = true),
        @Index(name = "idx_agent_run_status_heartbeat", columnList = "status, heartbeat_at"),
        @Index(name = "idx_agent_run_user_request", columnList = "user_id, request_id")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AgentRun {

    public static final String STATUS_RUNNING = "RUNNING";
    public static final String STATUS_SUCCEEDED = "SUCCEEDED";
    /**
     * 部分完成（P0-07）：本轮正常收尾，但有阶段失败/降级、过程警告或预算降级，
     * 结论可能不完整。绝不能与 {@link #STATUS_SUCCEEDED} 显示成同一个绿色标签。
     */
    public static final String STATUS_PARTIAL = "PARTIAL_SUCCESS";
    public static final String STATUS_FAILED = "FAILED";
    public static final String STATUS_CANCELLED = "CANCELLED";
    /**
     * 被拒绝/放弃的运行（P0）：心跳超时或进程重启，服务恢复时无法确认结果。
     * 与 FAILED 区分：FAILED 是执行体自己报的失败，ABANDONED 是「没人知道发生了什么」。
     */
    public static final String STATUS_ABANDONED = "ABANDONED";

    // ==================== 终态阶段（agent_run.stage） ====================
    // stage 列宽 32；这些是 run 收尾时写入的终态，运行中则是 AgentState 的名字。

    /** 正常收尾（SUCCEEDED / PARTIAL_SUCCESS） */
    public static final String STAGE_DONE = "DONE";
    public static final String STAGE_FAILED = "FAILED";
    public static final String STAGE_CANCELLED = "CANCELLED";
    public static final String STAGE_ABANDONED = "ABANDONED";

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

    /** 运行总状态（列宽 16：PARTIAL_SUCCESS 占 15，改名不能再长，否则写入会被截断/报错） */
    @Column(nullable = false, length = 16)
    private String status;

    @Column(length = 500)
    private String error;

    /** 当前阶段快照（RECEIVE / UNDERSTAND / PLAN / ... / DONE / FAILED / CANCELLED / ABANDONED） */
    @Column(length = 32)
    private String stage;

    /** 最近一次阶段推进时间；进程崩溃后恢复任务据此判断运行是否已经死掉 */
    @Column(name = "heartbeat_at")
    private LocalDateTime heartbeatAt;

    /** 客户端幂等标识；同一 (user_id, request_id) 可回到原 run，SSE 重连不重复规划 */
    @Column(name = "request_id", length = 64)
    private String requestId;

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
        if (heartbeatAt == null) {
            heartbeatAt = startedAt;
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
