package com.travel.agent.resilience;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 降级指标断言工具：把 {@code external.degraded{provider,reason}} 的读取收敛到一处，
 * 避免每个用例重复拼 Micrometer 的 tag 查找（也保证五个依赖的断言口径一致）。
 */
final class DegradedMetrics {

    static final String METRIC = "external.degraded";

    private DegradedMetrics() {
    }

    /** 读计数；指标尚未创建时返回 0（而不是 NPE），便于断言「正常路径不产生降级」 */
    static double count(MeterRegistry registry, String provider, String reason) {
        Counter counter = registry.find(METRIC).tag("provider", provider).tag("reason", reason).counter();
        return counter == null ? 0.0 : counter.count();
    }

    /** 断言该 provider/reason 至少发生一次降级 */
    static void assertDegraded(MeterRegistry registry, String provider, String reason) {
        assertThat(count(registry, provider, reason))
                .as("expected %s{provider=%s,reason=%s} to be recorded", METRIC, provider, reason)
                .isGreaterThanOrEqualTo(1.0);
    }

    /** 断言该 provider 从未记录过任何降级（正常路径/恢复路径） */
    static void assertNeverDegraded(MeterRegistry registry, String provider) {
        assertThat(registry.find(METRIC).tag("provider", provider).counters())
                .as("expected no %s{provider=%s}", METRIC, provider)
                .isEmpty();
    }
}