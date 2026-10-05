package com.travel.agent.security;

import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 极简固定窗口限流器（进程内，无第三方依赖）。
 *
 * 选择固定窗口而不是令牌桶：实现足够短、行为可预测，个人助理场景够用。
 * 计数不落库，重启即清零；多实例部署需要换成 Redis 计数。
 */
@Component
public class RateLimiter {

    private record Window(long startMillis, int count) {
    }

    private static final int PRUNE_THRESHOLD = 10_000;

    private final Map<String, Window> buckets = new ConcurrentHashMap<>();

    /**
     * 尝试占用一次配额。
     *
     * @param nowMillis 当前时间（由调用方传入，便于测试用固定时间验证窗口语义）
     * @return true 表示放行，false 表示已超出限额
     */
    public boolean tryAcquire(String key, int limit, long windowMillis, long nowMillis) {
        if (limit <= 0) {
            return true;
        }
        Window window = buckets.compute(key, (k, current) -> {
            if (current == null || nowMillis - current.startMillis() >= windowMillis) {
                return new Window(nowMillis, 1);
            }
            return new Window(current.startMillis(), current.count() + 1);
        });
        if (buckets.size() > PRUNE_THRESHOLD) {
            prune(nowMillis, windowMillis);
        }
        return window.count() <= limit;
    }

    /** 距离当前窗口结束还有多久（用于 Retry-After） */
    public long retryAfterSeconds(String key, long windowMillis, long nowMillis) {
        Window window = buckets.get(key);
        if (window == null) {
            return 0;
        }
        long remain = window.startMillis() + windowMillis - nowMillis;
        return Math.max(1, (remain + 999) / 1000);
    }

    /** 清理已经过期的窗口，避免长期运行后 Map 无限增长 */
    void prune(long nowMillis, long windowMillis) {
        buckets.entrySet().removeIf(entry -> nowMillis - entry.getValue().startMillis() >= windowMillis);
    }

    public void reset() {
        buckets.clear();
    }
}
