package com.travel.agent.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** 限流器语义：窗口内计数、窗口滚动重置、超限与重试提示 */
class RateLimiterTest {

    private final RateLimiter limiter = new RateLimiter();
    private static final long WINDOW = 60_000L;

    @Test
    @DisplayName("窗口内超过限额后拒绝，窗口滚动后重新放行")
    void enforcesLimitWithinWindow() {
        long start = 1_000_000L;
        assertThat(limiter.tryAcquire("k", 2, WINDOW, start)).isTrue();
        assertThat(limiter.tryAcquire("k", 2, WINDOW, start + 1)).isTrue();
        assertThat(limiter.tryAcquire("k", 2, WINDOW, start + 2)).isFalse();

        // 窗口滚动后重新计数
        assertThat(limiter.tryAcquire("k", 2, WINDOW, start + WINDOW + 1)).isTrue();
    }

    @Test
    @DisplayName("不同 key 互不影响")
    void isolatesKeys() {
        long now = 5_000L;
        assertThat(limiter.tryAcquire("a", 1, WINDOW, now)).isTrue();
        assertThat(limiter.tryAcquire("a", 1, WINDOW, now)).isFalse();
        assertThat(limiter.tryAcquire("b", 1, WINDOW, now)).isTrue();
    }

    @Test
    @DisplayName("limit <= 0 视为不限流")
    void zeroLimitMeansUnlimited() {
        long now = 1L;
        for (int i = 0; i < 100; i++) {
            assertThat(limiter.tryAcquire("free", 0, WINDOW, now)).isTrue();
        }
    }

    @Test
    @DisplayName("超限后给出 Retry-After 秒数")
    void reportsRetryAfter() {
        long now = 10_000L;
        limiter.tryAcquire("k", 1, WINDOW, now);
        limiter.tryAcquire("k", 1, WINDOW, now);

        assertThat(limiter.retryAfterSeconds("k", WINDOW, now + 1_000)).isEqualTo(59);
        assertThat(limiter.retryAfterSeconds("unknown", WINDOW, now)).isZero();
    }

    @Test
    @DisplayName("过期窗口会被清理，避免长期运行内存增长")
    void prunesExpiredWindows() {
        long now = 100_000L;
        limiter.tryAcquire("old", 5, WINDOW, now);
        limiter.prune(now + WINDOW + 1, WINDOW);

        assertThat(limiter.retryAfterSeconds("old", WINDOW, now + WINDOW + 1)).isZero();
    }
}
