package com.travel.agent.config;

import com.travel.agent.agent.AgentBudget;
import com.travel.agent.agent.AgentSpend;
import com.travel.agent.llm.LlmProperties;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Agent 主链路端到端预算配置（{@code travel.agent.budget.*}）：时间 deadline + 花费上限。
 *
 * <p>为什么默认开启：慢依赖把一次对话拖到分钟级是线上真实发生的问题（验收实测 128.13s），
 * 而「预算内降级 + 如实告知」对用户永远好过「一直转圈」；需要完全恢复旧行为的场景
 * （排障、超长规划压测）用 {@code travel.agent.budget.enabled=false} 一键关闭。
 *
 * <p>第二阶段新增 {@code travel.agent.budget.spend.*}：默认值刻意<b>宽松</b>——
 * 它们的作用是拦住「失控循环」而不是给正常请求设 SLO（30s 的 deadline 决定了正常一轮
 * 大概十几次调用、十几万 token 上限）。要收紧成本时再调小。
 *
 * <p>费用上限只在 {@code travel.llm.prompt-price-per-1k-usd} /
 * {@code travel.llm.completion-price-per-1k-usd} 配置了真实单价后才可能触发：
 * 没配价目表时费用恒为 0，绝不拿猜测的单价去卡用户请求。
 */
@Data
@Component
@ConfigurationProperties(prefix = "travel.agent.budget")
public class AgentBudgetProperties {

    /** 总开关：false 时不创建 deadline、不创建花费预算，所有阶段检查放行，行为与升级前一致 */
    private boolean enabled = true;

    /** 端到端总预算（毫秒）：覆盖意图 → 规划 → 审核 → 预览全链路；默认 30s */
    private long totalMs = 30_000L;

    /** 花费预算（调用次数/工具次数/重试/token/费用），与时间预算叠加生效 */
    private Spend spend = new Spend();

    /**
     * 为一次请求创建预算窗口。
     *
     * <p>LLM 读超时与单价都取自 {@link LlmProperties}：读超时用于封顶单次调用，
     * 单价用于把 token 折算成费用上限的判定口径——两处必须是同一份配置，
     * 否则「预算里的费用」与「成本看板上的费用」会长期不一致。
     */
    public AgentBudget newBudget(LlmProperties llm) {
        if (!enabled) {
            return AgentBudget.unlimited();
        }
        int readTimeoutMs = llm == null ? 0 : llm.getReadTimeoutMs();
        return AgentBudget.start(totalMs, readTimeoutMs, spend.toSpend(llm));
    }

    /**
     * 花费预算配置。占位符取值口径：<b>负数 = 不设限</b>，0 表示「一次都不允许」——
     * 需要放开某一项时写 -1，而不是写 0。
     */
    @Data
    public static class Spend {

        /** 花费预算总开关：false 时只保留时间预算（行为与第二阶段之前一致） */
        private boolean enabled = true;

        /** 单轮 LLM 调用次数上限（含失败/超时的尝试）；默认 30，正常一轮（意图+研究+规划+审核+润色）约 8–15 次 */
        private int maxLlmCalls = 30;

        /**
         * 单轮工具调用次数上限；默认 200。
         *
         * <p>为什么不是几十：确定性规划路径每天约 8 次工具（上午/下午选点 + 午餐/晚餐选店 +
         * 每段路线耗时），7 天行程合法用量就有 60+ 次；把它当成「每轮只准调 20 次」会把
         * 正常的长行程规划直接打到降级。这里拦的是<b>风暴</b>（模型循环/重试把次数放大一个数量级），
         * 不是日常用量。
         */
        private int maxToolCalls = 200;

        /** 单轮重试次数上限（结构化规划带反馈重试 + 审核重规划）；默认 5 */
        private int maxRetries = 5;

        /** 单轮 token 总量上限（输入 + 输出，来自供应商 usage）；默认 20 万 */
        private long maxTokens = 200_000L;

        /** 单轮估算费用上限（美元）；默认 1.00。单价未配置时该项不会触发 */
        private double maxCostUsd = 1.0d;

        /** 按配置构造花费预算；关闭或未配置时返回不限量实例 */
        public AgentSpend toSpend(LlmProperties llm) {
            if (!enabled) {
                return AgentSpend.unlimited();
            }
            double promptPrice = llm == null ? 0d : llm.getPromptPricePer1kUsd();
            double completionPrice = llm == null ? 0d : llm.getCompletionPricePer1kUsd();
            return AgentSpend.of(new AgentSpend.Config(true, maxLlmCalls, maxToolCalls, maxRetries,
                    maxTokens, maxCostUsd, promptPrice, completionPrice));
        }
    }
}