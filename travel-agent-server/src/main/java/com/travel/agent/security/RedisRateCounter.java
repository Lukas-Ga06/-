package com.travel.agent.security;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Redis 固定窗口限流计数器（travel.rate-limit.store=redis 时启用）。
 *
 * INCR + 首次 PEXPIRE 用 Lua 保证原子；计数跨实例共享，任一实例超限全集群生效。
 * Redis 不可用时按"限流不可用"放行并告警——限流是保护措施，不应反过来把服务打挂。
 */
@Component
@ConditionalOnProperty(prefix = "travel.rate-limit", name = "store", havingValue = "redis")
@RequiredArgsConstructor
@Slf4j
public class RedisRateCounter implements RateCounter {

    /** KEYS[1]=计数键，ARGV[1]=窗口毫秒；返回窗口内计数 */
    private static final DefaultRedisScript<Long> INCR_SCRIPT = new DefaultRedisScript<>("""
            local count = redis.call('INCR', KEYS[1])
            if count == 1 then
                redis.call('PEXPIRE', KEYS[1], ARGV[1])
            end
            return count
            """, Long.class);

    private final StringRedisTemplate redisTemplate;

    @Override
    public boolean tryAcquire(String key, int limit, long windowMillis, long nowMillis) {
        try {
            Long count = redisTemplate.execute(INCR_SCRIPT, List.of("ratelimit:" + key),
                    String.valueOf(windowMillis));
            return count == null || count <= limit;
        } catch (Exception e) {
            log.warn("Redis 限流计数失败，本次放行: {}", e.getMessage());
            return true;
        }
    }

    @Override
    public long retryAfterSeconds(String key, long windowMillis, long nowMillis) {
        try {
            Long remainMillis = redisTemplate.getExpire("ratelimit:" + key,
                    java.util.concurrent.TimeUnit.MILLISECONDS);
            if (remainMillis == null || remainMillis <= 0) {
                return 0;
            }
            return Math.max(1, (remainMillis + 999) / 1000);
        } catch (Exception e) {
            return 0;
        }
    }
}
