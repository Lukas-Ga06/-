package com.travel.agent.service;

import com.travel.agent.agent.AgentSpend;
import com.travel.agent.entity.AgentRunSpend;
import com.travel.agent.repository.AgentRunSpendRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Optional;

/**
 * 每轮 Agent 花费的持久化（第二阶段：花费落库，V29）。
 *
 * <p>与 {@link AgentTraceService} 同一条纪律：<b>花费落库绝不反噬主流程</b>。
 * 写入发生在 run 收尾，失败只记 WARN（下一轮照常）；读取失败返回空，运行详情不因
 * 花费表不可用而 500——花费是补充信息，不是主数据。
 *
 * <p>幂等：同一 runId 重复收尾时覆盖为最新快照（一次运行只有一条行；runId 唯一约束兜底）。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AgentRunSpendService {

    /** 金额统一 6 位小数：与本表 DECIMAL(18,6) 对齐，避免 H2/MySQL 舍入差异 */
    private static final int COST_SCALE = 6;

    private final AgentRunSpendRepository repository;

    /** 记录一轮的花费快照；未启用花费预算的入口不会调用（runId 为空时同样跳过） */
    public void record(String runId, Long userId, AgentSpend.Snapshot snapshot) {
        if (runId == null || runId.isBlank() || snapshot == null) {
            return;
        }
        try {
            AgentRunSpend row = repository.findByRunId(runId).orElseGet(AgentRunSpend::new);
            row.setRunId(runId);
            row.setUserId(userId);
            row.setLlmCalls(snapshot.llmCalls());
            row.setToolCalls(snapshot.toolCalls());
            row.setRetries(snapshot.retries());
            row.setPromptTokens(snapshot.promptTokens());
            row.setCompletionTokens(snapshot.completionTokens());
            row.setCostUsd(BigDecimal.valueOf(Math.max(0d, snapshot.costUsd()))
                    .setScale(COST_SCALE, RoundingMode.HALF_UP));
            row.setExhausted(snapshot.exhausted() == null ? null : snapshot.exhausted().tag());
            repository.save(row);
        } catch (RuntimeException e) {
            log.warn("Agent 本轮花费落库失败（不影响主流程）: runId={} {}", runId, e.toString());
        }
    }

    /** 读取一轮的花费快照；读取失败按「没有记录」处理（详情接口是只读补充信息） */
    public Optional<AgentRunSpend> findByRunId(String runId) {
        if (runId == null || runId.isBlank()) {
            return Optional.empty();
        }
        try {
            return repository.findByRunId(runId);
        } catch (RuntimeException e) {
            log.warn("读取 Agent 花费快照失败（按无记录处理）: runId={} {}", runId, e.toString());
            return Optional.empty();
        }
    }
}