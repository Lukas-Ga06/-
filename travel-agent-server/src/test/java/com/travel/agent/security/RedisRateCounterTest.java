package com.travel.agent.security;

import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Redis 限流计数：Lua 自增结果与限额比较、TTL 换算 Retry-After、Redis 故障放行 */
class RedisRateCounterTest {

    @SuppressWarnings("unchecked")
    private StringRedisTemplate template = mock(StringRedisTemplate.class);

    private final RedisRateCounter counter = new RedisRateCounter(template);

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
    void redisFailureFailsOpen() {
        when(template.<Long>execute(any(RedisScript.class), any(List.class), eq("60000")))
                .thenThrow(new IllegalStateException("connection refused"));
        assertTrue(counter.tryAcquire("k", 2, 60_000, 1_000), "Redis 故障时放行（限流是保护措施，不应反向打挂服务）");
    }

    @Test
    void retryAfterUsesRedisTtl() {
        when(template.getExpire("ratelimit:k", java.util.concurrent.TimeUnit.MILLISECONDS)).thenReturn(1500L);
        assertEquals(2, counter.retryAfterSeconds("k", 60_000, 1_000));
        when(template.getExpire("ratelimit:k", java.util.concurrent.TimeUnit.MILLISECONDS)).thenReturn(-2L);
        assertEquals(0, counter.retryAfterSeconds("k", 60_000, 1_000));
    }
}
