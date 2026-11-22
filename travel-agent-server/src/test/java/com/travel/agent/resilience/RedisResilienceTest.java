package com.travel.agent.resilience;

import com.travel.agent.security.RedisRateCounter;
import com.travel.agent.util.ExternalDegradationMetrics;
import io.lettuce.core.ClientOptions;
import io.lettuce.core.SocketOptions;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

import java.net.SocketTimeoutException;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Redis 限流的降级矩阵（T3）：连接被拒 / 命令超时 → <b>fail-open 放行</b>，绝不把业务打挂。
 *
 * <p>fail-open 不是静默降级：每次放行都必须留痕，本测试同时断言既有 {@code ratelimit.failopen}
 * 与统一口径 {@code external.degraded{provider=redis,reason=connection_error|timeout}} 都在涨。
 *
 * <p>恢复语义：本类<b>没有锁存</b>——Redis 恢复后，下一次命令成功即恢复真实限流（用 mock 的
 * 「第一次抛异常、第二次返回计数」覆盖）；与 Embedding 的「永久降级需重启」形成对照，
 * 两者都在 {@code docs/guide/external-dependencies.md} 里写清。
 *
 * <p>真实不可达端口用 127.0.0.1:1（connection refused 立即返回）+ 200ms 命令/连接超时，
 * 用例耗时在百毫秒级，不会拖慢全量测试。
 */
class RedisResilienceTest {

    private static final String KEY = "login:1.2.3.4";
    private static final long WINDOW_MS = 60_000;

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private LettuceConnectionFactory factory;

    @AfterEach
    void closeFactory() {
        if (factory != null) {
            factory.destroy();
        }
    }

    /** 指向不可达端口 1 的真实客户端：验证「真的连不上」也能 fail-open，而不是只信 mock */
    private RedisRateCounter unreachableCounter() {
        RedisStandaloneConfiguration standalone = new RedisStandaloneConfiguration("127.0.0.1", 1);
        LettuceClientConfiguration clientConfig = LettuceClientConfiguration.builder()
                .commandTimeout(Duration.ofMillis(200))
                .clientOptions(ClientOptions.builder()
                        .socketOptions(SocketOptions.builder().connectTimeout(Duration.ofMillis(200)).build())
                        .build())
                .build();
        factory = new LettuceConnectionFactory(standalone, clientConfig);
        factory.afterPropertiesSet();
        StringRedisTemplate template = new StringRedisTemplate(factory);
        template.afterPropertiesSet();
        return new RedisRateCounter(template, registry);
    }

    @Test
    @DisplayName("连接被拒（127.0.0.1:1）：fail-open 放行、不抛异常、两个指标都递增")
    void connectionRefusedFailsOpen() {
        RedisRateCounter counter = unreachableCounter();

        assertTimeoutPreemptively(Duration.ofSeconds(5), () -> {
            assertTrue(counter.tryAcquire(KEY, 1, WINDOW_MS, 1_000L),
                    "Redis 不可用时必须放行：限流是保护措施，不应反过来把服务打挂");
            // 第一次触发真实失败后，后续调用仍应 fail-open（不锁存、不累积连接阻塞）
            assertTrue(counter.tryAcquire(KEY, 1, WINDOW_MS, 1_001L));
        });

        assertThat(failOpenCount()).isGreaterThanOrEqualTo(2.0);
        DegradedMetrics.assertDegraded(registry,
                ExternalDegradationMetrics.PROVIDER_REDIS, ExternalDegradationMetrics.REASON_CONNECTION_ERROR);
    }

    @Test
    @DisplayName("连接被拒：retryAfterSeconds 返回至少 1 秒，不抛异常（避免 Retry-After: 0 放大重试）")
    void retryAfterDoesNotThrowAndNeverReturnsZero() {
        RedisRateCounter counter = unreachableCounter();

        assertTimeoutPreemptively(Duration.ofSeconds(5), () -> {
            long seconds = counter.retryAfterSeconds(KEY, WINDOW_MS, 1_000L);
            assertThat(seconds).isGreaterThanOrEqualTo(1L);
        });
        DegradedMetrics.assertDegraded(registry,
                ExternalDegradationMetrics.PROVIDER_REDIS, ExternalDegradationMetrics.REASON_CONNECTION_ERROR);
    }

    @Test
    @DisplayName("命令超时：fail-open 放行，reason=timeout")
    @SuppressWarnings("unchecked")
    void commandTimeoutFailsOpenAndIsClassifiedAsTimeout() {
        StringRedisTemplate template = mock(StringRedisTemplate.class);
        when(template.<Long>execute(any(RedisScript.class), any(List.class), eq("60000")))
                .thenThrow(new RedisConnectionFailureException("Read timed out",
                        new SocketTimeoutException("Read timed out")));
        RedisRateCounter counter = new RedisRateCounter(template, registry);

        assertTrue(counter.tryAcquire(KEY, 10, WINDOW_MS, 1_000L));
        DegradedMetrics.assertDegraded(registry,
                ExternalDegradationMetrics.PROVIDER_REDIS, ExternalDegradationMetrics.REASON_TIMEOUT);
        assertThat(failOpenCount()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("恢复：Redis 回来后计数恢复正常，fail-open 不锁存，降级指标不再增长")
    @SuppressWarnings("unchecked")
    void recoversOnceRedisIsBack() {
        StringRedisTemplate template = mock(StringRedisTemplate.class);
        when(template.<Long>execute(any(RedisScript.class), any(List.class), eq("60000")))
                .thenThrow(new RedisConnectionFailureException("connection refused"))
                .thenReturn(2L);
        RedisRateCounter counter = new RedisRateCounter(template, registry);

        assertTrue(counter.tryAcquire(KEY, 1, WINDOW_MS, 1_000L), "故障期间放行");
        // 第二次：Redis 已恢复，计数 2 > limit 1 → 正常拒绝，证明没有锁存
        assertThat(counter.tryAcquire(KEY, 1, WINDOW_MS, 1_100L)).isFalse();
        assertThat(failOpenCount()).isEqualTo(1.0);
        assertThat(DegradedMetrics.count(registry, ExternalDegradationMetrics.PROVIDER_REDIS,
                ExternalDegradationMetrics.REASON_CONNECTION_ERROR)).isEqualTo(1.0);
    }

    private double failOpenCount() {
        // 既有 fail-open 指标名（RedisRateCounter.FAIL_OPEN_METRIC，包内可见，这里用同名字面量断言）
        var counter = registry.find("ratelimit.failopen").counter();
        return counter == null ? 0.0 : counter.count();
    }
}