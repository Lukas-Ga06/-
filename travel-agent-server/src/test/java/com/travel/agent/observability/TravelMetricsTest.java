package com.travel.agent.observability;

import com.travel.agent.llm.LlmProperties;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link TravelMetrics} 的指标契约测试（T5 可观测性）。
 *
 * <p>这里断言的是<b>对外契约</b>：指标名、tag 取值、累加语义。看板/告警按这些字符串写死，
 * 改名或改 tag 必须让本测试先红——否则线上表现是「面板静默变空、告警永不触发」。
 *
 * <p>不启动 Spring 容器：SimpleMeterRegistry 足够验证命名与累加，
 * 指标故障隔离则用一个会抛异常的重启 registry 验证。
 */
class TravelMetricsTest {

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final LlmProperties props = new LlmProperties();

    private TravelMetrics metrics() {
        return new TravelMetrics(registry, props);
    }

    /** 未创建过的 tag 组合按 0 处理（registry.get 会抛 MeterNotFoundException，不适合断言"没记"） */
    private double count(String name, String tagKey, String tagValue) {
        return registry.find(name).tag(tagKey, tagValue).counters().stream()
                .mapToDouble(io.micrometer.core.instrument.Counter::count).sum();
    }

    @Test
    @DisplayName("规划结果：success / degraded / failed 三个 tag 各自累加")
    void planOutcomeTags() {
        TravelMetrics metrics = metrics();

        metrics.planOutcome(TravelMetrics.RESULT_SUCCESS);
        metrics.planOutcome(TravelMetrics.RESULT_SUCCESS);
        metrics.planOutcome(TravelMetrics.RESULT_DEGRADED);
        metrics.planOutcome(TravelMetrics.RESULT_FAILED);

        assertThat(count(TravelMetrics.PLAN_OUTCOME, "result", "success")).isEqualTo(2.0);
        assertThat(count(TravelMetrics.PLAN_OUTCOME, "result", "degraded")).isEqualTo(1.0);
        assertThat(count(TravelMetrics.PLAN_OUTCOME, "result", "failed")).isEqualTo(1.0);
    }

    @Test
    @DisplayName("确认提交：success / conflict / failed 三个 tag 各自累加")
    void confirmOutcomeTags() {
        TravelMetrics metrics = metrics();

        metrics.confirmOutcome(TravelMetrics.RESULT_SUCCESS);
        metrics.confirmOutcome(TravelMetrics.RESULT_CONFLICT);
        metrics.confirmOutcome(TravelMetrics.RESULT_CONFLICT);
        metrics.confirmOutcome(TravelMetrics.RESULT_FAILED);

        assertThat(count(TravelMetrics.CONFIRM_OUTCOME, "result", "success")).isEqualTo(1.0);
        assertThat(count(TravelMetrics.CONFIRM_OUTCOME, "result", "conflict")).isEqualTo(2.0);
        assertThat(count(TravelMetrics.CONFIRM_OUTCOME, "result", "failed")).isEqualTo(1.0);
    }

    @Test
    @DisplayName("RAG 检索：hit / miss 按结果非空与否记账")
    void ragRetrievalTags() {
        TravelMetrics metrics = metrics();

        metrics.ragRetrieval(true);
        metrics.ragRetrieval(false);
        metrics.ragRetrieval(false);

        assertThat(count(TravelMetrics.RAG_RETRIEVAL, "result", "hit")).isEqualTo(1.0);
        assertThat(count(TravelMetrics.RAG_RETRIEVAL, "result", "miss")).isEqualTo(2.0);
    }

    @Test
    @DisplayName("LLM 用量：token 分 prompt/completion，成本按单价估算")
    void llmTokensAndCost() {
        props.setPromptPricePer1kUsd(0.002);
        props.setCompletionPricePer1kUsd(0.004);
        TravelMetrics metrics = metrics();

        metrics.recordLlmUsage(1000, 500);

        assertThat(count(TravelMetrics.LLM_TOKENS, "type", "prompt")).isEqualTo(1000.0);
        assertThat(count(TravelMetrics.LLM_TOKENS, "type", "completion")).isEqualTo(500.0);
        // 1000/1000*0.002 + 500/1000*0.004 = 0.004 USD
        assertThat(registry.get(TravelMetrics.LLM_COST_USD).counter().count())
                .isCloseTo(0.004, within(1e-9));
    }

    @Test
    @DisplayName("单价默认 0：只记 token，不创建成本指标（不虚报 0 成本）")
    void costSkippedWhenPriceUnset() {
        TravelMetrics metrics = metrics();

        metrics.recordLlmUsage(2000, 1000);

        assertThat(count(TravelMetrics.LLM_TOKENS, "type", "prompt")).isEqualTo(2000.0);
        assertThat(registry.find(TravelMetrics.LLM_COST_USD).counter()).isNull();
    }

    @Test
    @DisplayName("usage 缺失（0/0）：不创建任何空指标，避免把「没数据」画成 0")
    void zeroUsageCreatesNoMeters() {
        TravelMetrics metrics = metrics();

        metrics.recordLlmUsage(0, 0);

        assertThat(registry.find(TravelMetrics.LLM_TOKENS).counters()).isEmpty();
        assertThat(registry.find(TravelMetrics.LLM_COST_USD).counter()).isNull();
    }

    @Test
    @DisplayName("指标故障隔离：registry 抛异常也不能反噬业务调用")
    void metricFailureNeverPropagates() {
        MeterRegistry broken = mock(MeterRegistry.class);
        when(broken.counter(anyString(), any(String[].class)))
                .thenThrow(new IllegalStateException("registry down"));
        TravelMetrics metrics = new TravelMetrics(broken, props);

        assertThatCode(() -> {
            metrics.planOutcome(TravelMetrics.RESULT_SUCCESS);
            metrics.confirmOutcome(TravelMetrics.RESULT_FAILED);
            metrics.ragRetrieval(true);
            metrics.recordLlmUsage(10, 10);
        }).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("成本估算对负 token 归零，不产生负成本")
    void negativeTokensClampToZero() {
        props.setPromptPricePer1kUsd(0.01);
        TravelMetrics metrics = metrics();

        assertThat(metrics.estimateCostUsd(-100, -100)).isZero();
        ReflectionTestUtils.invokeMethod(metrics, "estimateCostUsd", -5L, 0L);
    }
}