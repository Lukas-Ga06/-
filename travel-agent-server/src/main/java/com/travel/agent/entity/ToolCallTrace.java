package com.travel.agent.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * 一次工具调用的持久化追踪（V2.2 FR-10）。
 *
 * <p>SSE 的 tool_call / tool_result 事件只在连接存活期间可见；
 * 落库后才能回答「这次运行调了哪些工具、参数是什么、耗时多久、成功与否」。
 * 与设计文档 6.2 的 ToolResult 契约对应：success / errorCode / latency，
 * 参数与结果只存<b>摘要</b>（截断的 JSON），不保存完整原文。
 */
@Entity
@Table(name = "tool_call_trace", indexes = {
        @Index(name = "idx_tool_trace_run", columnList = "run_id, id"),
        @Index(name = "idx_tool_trace_user", columnList = "user_id, id")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ToolCallTrace {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 所属 Agent 运行（独立调用时可为空） */
    @Column(name = "run_id", length = 64)
    private String runId;

    /** 触发本次调用的任务（可为空：工具调用不一定都发生在任务阶段内） */
    @Column(name = "task_id", length = 64)
    private String taskId;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "tool_name", nullable = false, length = 100)
    private String toolName;

    /** 参数摘要（截断 JSON，不含任何密钥——工具参数本来就只有业务字段） */
    @Column(name = "args_digest", length = 1000)
    private String argsDigest;

    /** 结果摘要（截断 JSON） */
    @Column(name = "result_digest", length = 1000)
    private String resultDigest;

    @Column(nullable = false)
    private boolean success;

    /** 失败时的错误码/错误分类（截断） */
    @Column(name = "error_code", length = 200)
    private String errorCode;

    @Column(name = "latency_ms")
    private Long latencyMs;

    @Column(name = "started_at", nullable = false)
    private LocalDateTime startedAt;

    @PrePersist
    void prePersist() {
        if (startedAt == null) {
            startedAt = LocalDateTime.now();
        }
    }
}
