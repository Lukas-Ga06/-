package com.travel.agent.security;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Redis 限流计数：Lua 自增结果与限额比较、TTL 换算 Retry-After、Redis 故障 fail-open 留痕 */
class RedisRateCounterTest {

    @SuppressWarnings("unchecked")
    private final StringRedisTemplate template = mock(StringRedisTemplate.class);

    private final MeterRegistry meterRegistry = new SimpleMeterRegistry();

    private final RedisRateCounter counter = new RedisRateCounter(template, meterRegistry);

    @Test
    @SuppressWarnings("unchecked")
    void limitsByLuaCount() {
        when(template.<Long>execute(any(RedisScript.class), any(List.class), eq("60000")))
                .thenReturn(1L, 2L, 3L);
        assertTrue(counter.tryAcquire("k", 2, 60_000, 1_000));
        assertTrue(counter.tryAcquire("k", 2, 60_000, 1_001));
        assertFalse(counter.tryAcquire("k", 2, 60_000, 1_002), "窗口内第 3 次超限");
    }

    @Test
    @SuppressWarnings("unchecked")
    void redisFailureFailsOpenAndIsCounted() {
        when(template.<Long>execute(any(RedisScript.class), any(List.class), eq("60000")))
                .thenThrow(new IllegalStateException("connection refused"));

        assertTrue(counter.tryAcquire("k", 2, 60_000, 1_000),
                "Redis 故障时放行（限流是保护措施，不应反向打挂服务）");
        // fail-open 必须留痕：静默降级会让"限流其实没生效"长期无人发现
        double failOpen = meterRegistry.get(RedisRateCounter.FAIL_OPEN_METRIC).counter().count();
        assertEquals(1.0, failOpen);
    }

    @Test
    void retryAfterUsesRedisTtlAndNeverReturnsZero() {
        when(template.getExpire("ratelimit:k", java.util.concurrent.TimeUnit.MILLISECONDS)).thenReturn(1500L);
        assertEquals(2, counter.retryAfterSeconds("k", 60_000, 1_000));

        // TTL 已消失/读不到：至少 1 秒，杜绝 Retry-After: 0（客户端会立刻重试，反而放大压力）
        when(template.getExpire("ratelimit:k", java.util.concurrent.TimeUnit.MILLISECONDS)).thenReturn(-2L);
        assertEquals(1, counter.retryAfterSeconds("k", 60_000, 1_000));

        // Redis 异常：同样给 1 秒，并计入 fail-open
        when(template.getExpire("ratelimit:k", java.util.concurrent.TimeUnit.MILLISECONDS))
                .thenThrow(new IllegalStateException("connection refused"));
        assertEquals(1, counter.retryAfterSeconds("k", 60_000, 1_000));
    }
}