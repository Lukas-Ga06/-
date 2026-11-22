package com.travel.agent.security;

/**
 * 认证相关请求属性名。
 *
 * <p>放在常量类里而不是散在各处字符串：写方（{@link JwtAuthenticationFilter}）与读方
 * （{@code AgentController} / {@code AgentEngine}）必须引用同一个名字，写错一个字
 * 就会静默退回"票据能写数据"的旧行为，而且编译期发现不了。
 */
public final class AuthAttributes {

    /**
     * 本次请求<b>仅凭查询参数票据</b>认证（既没有 Authorization 头，也没有 httpOnly Cookie）。
     *
     * <p>票据是"无法自定义请求头"场景的兜底凭证（EventSource / {@code <img>}），
     * 会随 URL 进入代理日志、浏览器历史与 Referer，泄漏面远大于 Cookie。
     * 因此仅凭票据认证的请求被视为<b>只读会话</b>：可以问、可以规划，
     * 但不能直接落库写入（见 {@code AgentEngine}）。
     */
    public static final String TICKET_ONLY = "travel.auth.ticketOnly";

    private AuthAttributes() {
    }
}