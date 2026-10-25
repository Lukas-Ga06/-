package com.travel.agent.security;

/**
 * 限流计数器：固定窗口语义（与限流策略解耦）。
 * 进程内实现默认启用；travel.rate-limit.store=redis 时切换为 Redis 计数，多实例共享配额。
 */
public interface RateCounter {

    /**
     * 尝试占用一次配额。
     *
     * @param nowMillis 当前时间（由调用方传入，便于测试用固定时间验证窗口语义）
     */
    boolean tryAcquire(String key, int limit, long windowMillis, long nowMillis);

    /** 距离当前窗口结束还有多久（用于 Retry-After），秒；窗口不存在时返回 0 */
    long retryAfterSeconds(String key, long windowMillis, long nowMillis);
}
