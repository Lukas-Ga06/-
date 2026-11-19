package com.travel.agent.config;

import com.travel.agent.agent.AgentBudget;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Agent 主链路端到端时间预算配置（P1，{@code travel.agent.budget.*}）。
 *
 * <p>为什么默认开启：慢依赖把一次对话拖到分钟级是线上真实发生的问题（验收实测 128.13s），
 * 而「预算内降级 + 如实告知」对用户永远好过「一直转圈」；需要完全恢复旧行为的场景
 * （排障、超长规划压测）用 {@code travel.agent.budget.enabled=false} 一键关闭。
 */
@Data
@Component
@ConfigurationProperties(prefix = "travel.agent.budget")
public class AgentBudgetProperties {

    /** 总开关：false 时不创建 deadline，所有阶段检查放行，行为与升级前一致 */
    private boolean enabled = true;

    /** 端到端总预算（毫秒）：覆盖意图 → 规划 → 审核 → 预览全链路；默认 30s */
    private long totalMs = 30_000L;

    /**
     * 为一次请求创建预算窗口。
     *
     * @param llmReadTimeoutMs 配置的单次 LLM 读超时（{@code travel.llm.read-timeout-ms}），
     *                         预算开启时作为每次调用的读超时上限来源
     */
    public AgentBudget newBudget(int llmReadTimeoutMs) {
        return enabled ? AgentBudget.start(totalMs, llmReadTimeoutMs) : AgentBudget.unlimited();
    }
}