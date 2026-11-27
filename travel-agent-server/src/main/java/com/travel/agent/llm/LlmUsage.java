package com.travel.agent.llm;

/**
 * 一次 LLM 调用的真实 token 用量（来自供应商响应的 {@code usage}）。
 *
 * <p>为什么单独建类型：花费预算的口径必须是「供应商实际计费的 token」，而不是按字符数
 * 反推的估算值。估算值在中文/JSON/长提示词下偏差极大，一旦进入预算判定就会
 * 「账面上没超、实际已超」。所以只有解析到 {@code usage} 才有量，没有就是
 * {@link #NONE}——宁可少记一次，也不往预算里写猜测值。
 *
 * @param promptTokens     输入 token（OpenAI 的 {@code prompt_tokens} / 兼容实现的 {@code input_tokens}）
 * @param completionTokens 输出 token（{@code completion_tokens} / {@code output_tokens}）
 */
public record LlmUsage(long promptTokens, long completionTokens) {

    /** 没有 usage（未配置、被跳过、供应商未返回、或调用失败）：不计入任何预算项 */
    public static final LlmUsage NONE = new LlmUsage(0L, 0L);

    /** 是否存在可记账的用量 */
    public boolean isPresent() {
        return promptTokens > 0 || completionTokens > 0;
    }

    /** 总 token（输入 + 输出）：花费预算的 token 上限按它判定 */
    public long totalTokens() {
        return Math.max(0L, promptTokens) + Math.max(0L, completionTokens);
    }
}