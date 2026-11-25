package com.travel.agent.security;

import io.micrometer.core.instrument.MeterRegistry;
import com.travel.agent.util.ExternalDegradationMetrics;
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
 *
 * <p>fail-open 不是静默降级：每次放行都要留痕（WARN 日志带 bucket/key 与异常类型，
 * 并累加 {@code ratelimit.failopen} 计数器），否则"Redis 挂了三天没人发现"就是下一次事故的伏笔。
 */
@Component
@ConditionalOnProperty(prefix = "travel.rate-limit", name = "store", havingValue = "redis")
@RequiredArgsConstructor
@Slf4j
public class RedisRateCounter implements RateCounter {

    /** fail-open 计数指标：触发即代表本轮限流并未生效 */
    static final String FAIL_OPEN_METRIC = "ratelimit.failopen";

    /** KEYS[1]=计数键，ARGV[1]=窗口毫秒；返回窗口内计数 */
    private static final DefaultRedisScript<Long> INCR_SCRIPT = new DefaultRedisScript<>("""
            local count = redis.call('INCR', KEYS[1])
            if count == 1 then
                redis.call('PEXPIRE', KEYS[1], ARGV[1])
            end
            return count
            """, Long.class);

    private final StringRedisTemplate redisTemplate;
    private final MeterRegistry meterRegistry;

    @Override
    public boolean tryAcquire(String key, int limit, long windowMillis, long nowMillis) {
        try {
            Long count = redisTemplate.execute(INCR_SCRIPT, List.of("ratelimit:" + key),
                    String.valueOf(windowMillis));
            return count == null || count <= limit;
        } catch (Exception e) {
            failOpen("tryAcquire", key, e);
            return true;
        }
    }

    @Override
    public long retryAfterSeconds(String key, long windowMillis, long nowMillis) {
        try {
            Long remainMillis = redisTemplate.getExpire("ratelimit:" + key,
                    java.util.concurrent.TimeUnit.MILLISECONDS);
            if (remainMillis == null || remainMillis <= 0) {
                // 窗口已结束或 TTL 读不到：至少让客户端等 1 秒，避免 Retry-After: 0
                return 1;
            }
            return Math.max(1, (remainMillis + 999) / 1000);
        } catch (Exception e) {
            failOpen("retryAfterSeconds", key, e);
            return 1;
        }
    }

    /** Redis 异常：告警 + 计数 + 放行（限流不可用时不能让业务跟着挂） */
    private void failOpen(String operation, String bucket, Exception e) {
        // bucket 来自限流键（用户可控），异常 message 也可能含外部输入：进日志前统一剥 CR/LF
        log.warn("Redis 限流不可用，本次 fail-open 放行: bucket={} operation={} error={}: {}",
                bucket == null ? "null" : bucket.replace("\r", "_").replace("\n", "_"),
                operation, e.getClass().getSimpleName(),
                e.getMessage() == null ? "null" : e.getMessage().replace("\r", "_").replace("\n", "_"));
        meterRegistry.counter(FAIL_OPEN_METRIC).increment();
        // 统一降级口径（T3）：ratelimit.failopen 说明「限流没生效」，external.degraded 说明「依赖不可用」，
        // 两者是同一事件的两个视角，告警按后者统一聚合到全部外部依赖
        ExternalDegradationMetrics.record(meterRegistry, ExternalDegradationMetrics.PROVIDER_REDIS, e);
    }
}