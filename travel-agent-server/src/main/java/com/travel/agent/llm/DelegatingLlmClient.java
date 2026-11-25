package com.travel.agent.llm;

import com.travel.agent.security.UserContext;
import com.travel.agent.service.LlmQuotaService;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.util.List;

/** 委托客户端：优先真实模型，失败或未启用时自动降级；所有调用方实际注入的就是它 */
@Component
@Primary
@Slf4j
@RequiredArgsConstructor
public class DelegatingLlmClient implements LlmClient {

    private final OpenAiCompatibleLlmClient openAiClient;
    private final LlmProperties props;
    private final LlmQuotaService llmQuotaService;
    /** 所有调用方都注入 LlmClient（拿到的是这个委托类），因此"跳过"计数放在这里 */
    private final MeterRegistry meterRegistry;
    /** P5：记录最近一次真实调用的成败，供 /api/system/status 的 llmVerified 如实上报 */
    private final LlmRuntimeStatus runtimeStatus;

    @Override
    public String chat(List<LlmMessage> messages, double temperature) {
        if (!openAiClient.available()) {
            // 未配置 Key / 显式关闭：整条链路走规则引擎，这里记一笔便于在
            // /actuator/metrics/llm.calls 上直接看出"模型没在跑"
            meterRegistry.counter("llm.calls", "result", "skipped").increment();
            // 没发起真实调用也如实记为"未成功"：对外绝不能宣称模型可用
            runtimeStatus.recordFailure();
            return null;
        }
        if (!llmQuotaService.tryAcquire(UserContext.get())) {
            // 按用户日配额耗尽：等同"模型不可用"，调用方走既有降级路径
            meterRegistry.counter("llm.calls", "result", "quota").increment();
            runtimeStatus.recordFailure();
            return null;
        }
        String result = openAiClient.chat(messages, temperature);
        if (result != null && !result.isBlank()) {
            runtimeStatus.recordSuccess();
            return result;
        }
        runtimeStatus.recordFailure();
        return null;
    }

    /** 保留调用方指定的独立读超时（润色等辅助能力靠它不拖全局 30s），经配额闸门后原样透传 */
    @Override
    public String chat(List<LlmMessage> messages, double temperature, int readTimeoutMs) {
        if (!openAiClient.available()) {
            meterRegistry.counter("llm.calls", "result", "skipped").increment();
            runtimeStatus.recordFailure();
            return null;
        }
        if (!llmQuotaService.tryAcquire(UserContext.get())) {
            meterRegistry.counter("llm.calls", "result", "quota").increment();
            runtimeStatus.recordFailure();
            return null;
        }
        String result = openAiClient.chat(messages, temperature, readTimeoutMs);
        if (result != null && !result.isBlank()) {
            runtimeStatus.recordSuccess();
            return result;
        }
        runtimeStatus.recordFailure();
        return null;
    }

    /** usage 由真实客户端解析，委托类原样透传（跳过/配额不足时它没有写用量，读到的就是 NONE） */
    @Override
    public LlmUsage takeLastUsage() {
        return openAiClient.takeLastUsage();
    }

    @Override
    public boolean available() {
        return openAiClient.available();
    }
}
