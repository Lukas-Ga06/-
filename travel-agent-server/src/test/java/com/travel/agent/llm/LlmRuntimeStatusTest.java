package com.travel.agent.llm;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** P5：最近一次真实调用状态的轻量记录器（未调用过 = 未验证）。 */
class LlmRuntimeStatusTest {

    @Test
    void notVerifiedBeforeAnyCall() {
        assertThat(new LlmRuntimeStatus().verified()).isFalse();
    }

    @Test
    void followsTheLatestCallResult() {
        LlmRuntimeStatus status = new LlmRuntimeStatus();
        status.recordSuccess();
        assertThat(status.verified()).isTrue();
        status.recordFailure();
        assertThat(status.verified()).isFalse();
        status.recordSuccess();
        assertThat(status.verified()).isTrue();
    }
}
