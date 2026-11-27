package com.travel.agent.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 一次 Agent 运行的花费快照（第二阶段：每轮花费落库，V29）。
 *
 * <p>数据来源是 {@code AgentSpend.Snapshot}：LLM 调用数、工具调用数、重试数、token 用量、
 * 按配置单价折算的估算费用，以及触顶的花费项。只记<b>真实用量</b>——供应商没返回 usage 的调用
 * 不估算 token；没配单价时费用为 0，不猜价目表。
 *
 * <p>写入发生在 run 收尾（{@code AgentEngine#recordSpend}），与日志/指标同一处；
 * 落库失败只告警，绝不影响本轮结果。保留期与 Agent 追踪一致（默认 14 天）。
 */
@Entity
@Table(name = "agent_run_spend", indexes = {
        @Index(name = "idx_agent_run_spend_created", columnList = "created_at"),
        @Index(name = "idx_agent_run_spend_user", columnList = "user_id, created_at")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AgentRunSpend {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 对外暴露的运行标识（agent_run.run_id），一次运行只有一条花费快照 */
    @Column(name = "run_id", nullable = false, length = 64, unique = true)
    private String runId;

    /** 冗余的用户 id：按用户查花费时不必回表 join */
    @Column(name = "user_id")
    private Long userId;

    @Column(name = "llm_calls", nullable = false)
    private int llmCalls;

    @Column(name = "tool_calls", nullable = false)
    private int toolCalls;

    @Column(name = "retries", nullable = false)
    private int retries;

    @Column(name = "prompt_tokens", nullable = false)
    private long promptTokens;

    @Column(name = "completion_tokens", nullable = false)
    private long completionTokens;

    /** 估算费用（美元）；未配置单价时恒为 0，不估算 */
    @Column(name = "cost_usd", nullable = false, precision = 18, scale = 6)
    private BigDecimal costUsd;

    /** 触顶的花费项 tag（llm_calls/tool_calls/retries/tokens/cost）；未触顶为 null */
    @Column(name = "exhausted", length = 20)
    private String exhausted;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    /** 总 token（查询展示用，不落列） */
    public long totalTokens() {
        return promptTokens + completionTokens;
    }

    @PrePersist
    void prePersist() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
        if (costUsd == null) {
            costUsd = BigDecimal.ZERO;
        }
    }
}