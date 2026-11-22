package com.travel.agent.controller;

import com.travel.agent.agent.AgentContext;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SSE 生命周期回调的计数与取消语义（包可见单测）。
 *
 * <p>SseEmitter 的 onCompletion/onTimeout/onError 只能由 Servlet 容器触发，
 * 因此这里直接驱动 {@link AgentController.StreamLifecycle} 的包可见方法：
 * 重点验证「正常收尾不误记为断连」「半途断开计数并取消」「多个回调只计一次」。
 */
class SseStreamLifecycleTest {

    private static final String METRIC = "sse.disconnects";

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final AgentContext ctx = new AgentContext();

    private AgentController.StreamLifecycle lifecycle() {
        return new AgentController.StreamLifecycle(ctx, registry);
    }

    private double count(String reason) {
        Counter counter = registry.find(METRIC).tag("reason", reason).counter();
        return counter == null ? 0.0 : counter.count();
    }

    @Test
    @DisplayName("引擎正常收尾后 onCompletion 触发不算断连")
    void completionAfterFinishIsNotADisconnect() {
        AgentController.StreamLifecycle lifecycle = lifecycle();

        lifecycle.markFinished();
        lifecycle.onCompletion();

        assertThat(lifecycle.isFinished()).isTrue();
        assertThat(count("complete")).isZero();
        assertThat(ctx.isCancelled()).as("正常完成不能取消任何东西").isFalse();
    }

    @Test
    @DisplayName("客户端半途断开（未 finish 的 onCompletion）计 complete 并取消任务")
    void completionBeforeFinishCountsAsDisconnect() {
        AgentController.StreamLifecycle lifecycle = lifecycle();

        lifecycle.onCompletion();

        assertThat(count("complete")).isEqualTo(1.0);
        assertThat(ctx.isCancelled()).isTrue();
    }

    @Test
    @DisplayName("超时计 timeout 并取消任务")
    void timeoutCountsAndCancels() {
        AgentController.StreamLifecycle lifecycle = lifecycle();

        lifecycle.onTimeout();

        assertThat(count("timeout")).isEqualTo(1.0);
        assertThat(ctx.isCancelled()).isTrue();
    }

    @Test
    @DisplayName("连接错误计 error 并取消任务")
    void errorCountsAndCancels() {
        AgentController.StreamLifecycle lifecycle = lifecycle();

        lifecycle.onError(new IllegalStateException("broken pipe"));

        assertThat(count("error")).isEqualTo(1.0);
        assertThat(ctx.isCancelled()).isTrue();
    }

    @Test
    @DisplayName("同一条连接先后触发多个回调只计一次，且保留最先发生的原因")
    void onlyTheFirstCallbackOfAConnectionCounts() {
        AgentController.StreamLifecycle lifecycle = lifecycle();

        lifecycle.onTimeout();
        lifecycle.onError(new IllegalStateException("late error"));
        lifecycle.onCompletion();

        assertThat(count("timeout")).isEqualTo(1.0);
        assertThat(count("error")).isZero();
        assertThat(count("complete")).isZero();
    }

    @Test
    @DisplayName("引擎取消标记可重复置位且只影响本轮上下文")
    void cancelIsIdempotent() {
        assertThat(ctx.isCancelled()).isFalse();
        ctx.cancel();
        ctx.cancel();
        assertThat(ctx.isCancelled()).isTrue();
    }
}