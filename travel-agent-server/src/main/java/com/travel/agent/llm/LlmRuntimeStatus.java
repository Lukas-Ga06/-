package com.travel.agent.llm;

import org.springframework.stereotype.Component;

/**
 * 大模型「运行态」：记录**最近一次真实调用**的成败，与「配置里有没有 Key」分离。
 *
 * <p>为什么需要它（V2.2 验收 P5）：{@code llmConfigured} 只说明配置里读到了 Key，
 * 实际可能 100% 失败（配额用尽、Key 失效、网络不通）；验收报告里就出现过
 * 「LLM 全失败但状态页仍报 llmConfigured=true」的自报失真。状态接口用
 * {@link #verified()} 如实回答「上一次真实调用到底成没成」。
 *
 * <p>为什么状态放这里而不是直接查 {@link LlmProperties}：{@code DelegatingLlmClient}
 * 是所有调用方实际注入的唯一入口（真实模型、配额闸门、降级都在它里面），
 * 在它的出口处记录一次即可覆盖全部调用路径，且不改变任何既有降级行为。
 *
 * <p>并发语义：{@code volatile} 保存最近一次结果即可——状态页只需要「最新快照」，
 * 多次并发调用之间的先后不要求严格线性化；未调用过（{@code null}）时 {@link #verified()} 为 false。
 */
@Component
public class LlmRuntimeStatus {

    /** null = 本次进程还没有发生过调用；TRUE/FALSE = 最近一次调用的结果 */
    private volatile Boolean lastCallSucceeded;

    /** 记录一次成功（拿到非空模型输出） */
    public void recordSuccess() {
        lastCallSucceeded = Boolean.TRUE;
    }

    /** 记录一次失败（模型不可用 / 配额拒绝 / 调用异常 / 空输出） */
    public void recordFailure() {
        lastCallSucceeded = Boolean.FALSE;
    }

    /** 最近一次真实调用是否成功；从未调用或最近一次失败都为 false */
    public boolean verified() {
        return Boolean.TRUE.equals(lastCallSucceeded);
    }
}
