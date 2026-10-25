package com.travel.agent.security;

/**
 * 当前请求用户名（ThreadLocal）。
 *
 * 用途：LLM 按用户配额需要在调用深处知道"这次调用属于谁"，而不改变各服务的方法签名。
 * - JwtAuthenticationFilter 在同步请求线程上设置（finally 清理）；
 * - AgentEngine.handle 在执行线程上设置（覆盖 SSE 异步执行器与 Telegram/Webhook 渠道线程，
 *   filter 设置的值对异步执行器线程不可见，这里补上）；
 * - 两处都可能设置，清理各自 finally 进行，重复清理无副作用。
 * 取不到值（系统级后台调用）视为不限额，见 LlmQuotaService。
 */
public final class UserContext {

    private static final ThreadLocal<String> CURRENT = new ThreadLocal<>();

    private UserContext() { }

    public static void set(String username) {
        CURRENT.set(username);
    }

    public static String get() {
        return CURRENT.get();
    }

    public static void clear() {
        CURRENT.remove();
    }
}
