package com.travel.agent.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.travel.agent.config.RateLimitProperties;
import com.travel.agent.dto.ApiResponse;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * 接口限流。
 *
 * 挂在 JwtAuthenticationFilter 之后：
 * 未登录接口（登录/注册）按客户端 IP 计，已登录接口按用户名计。
 * 超限返回 429 + 统一 JSON，并带 Retry-After。
 */
@Component
@RequiredArgsConstructor
public class RateLimitFilter extends OncePerRequestFilter {

    private final RateLimitProperties properties;
    private final RateCounter limiter;
    private final ObjectMapper objectMapper;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        if (!properties.isEnabled()) {
            filterChain.doFilter(request, response);
            return;
        }
        Rule rule = ruleFor(request);
        if (rule == null) {
            filterChain.doFilter(request, response);
            return;
        }
        long windowMillis = properties.getWindowSeconds() * 1000L;
        long now = System.currentTimeMillis();
        String key = rule.scope() + "|" + identity(request) + "|" + request.getMethod() + " " + rule.name();

        if (!limiter.tryAcquire(key, rule.limit(), windowMillis, now)) {
            // 兜底为 1：Retry-After: 0 会让客户端立即重试，反而放大压力
            long retryAfter = Math.max(1, limiter.retryAfterSeconds(key, windowMillis, now));
            response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.setCharacterEncoding(StandardCharsets.UTF_8.name());
            response.setHeader("Retry-After", String.valueOf(retryAfter));
            objectMapper.writeValue(response.getWriter(),
                    ApiResponse.error(429, "操作过于频繁，请 " + retryAfter + " 秒后再试"));
            return;
        }
        filterChain.doFilter(request, response);
    }

    private record Rule(String name, String scope, int limit) {
    }

    private Rule ruleFor(HttpServletRequest request) {
        String path = request.getRequestURI();
        String method = request.getMethod();
        // 会话恢复（GET /api/auth/me）单独一个桶：它不在登录爆破的攻击面上，
        // 但会被"每次刷新都调一次"的前端高频命中，混在 auth 桶里会把正常用户踢成 429。
        if ("GET".equals(method) && "/api/auth/me".equals(path)) {
            return new Rule("session", "user", properties.getSessionLimit());
        }
        if (path.startsWith("/api/auth/")) {
            return new Rule("auth", "ip", properties.getAuthLimit());
        }
        if (path.startsWith("/api/agent/")) {
            return new Rule("agent", "user", properties.getAgentLimit());
        }
        if ("POST".equals(method) && path.endsWith("/photos")) {
            return new Rule("upload", "user", properties.getUploadLimit());
        }
        if (path.startsWith("/api/")) {
            return new Rule("default", "user", properties.getDefaultLimit());
        }
        return null;
    }

    /** 已登录按用户名，未登录（如登录接口本身）按客户端 IP */
    private String identity(HttpServletRequest request) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.isAuthenticated()
                && !"anonymousUser".equals(authentication.getName())) {
            return "user:" + authentication.getName();
        }
        return "ip:" + clientIp(request);
    }

    /**
     * 取客户端 IP 用于按 IP 限流。
     *
     * <p>默认（trusted-proxies 为空）完全不读 X-Forwarded-For：它是客户端可任意伪造的请求头，
     * 之前直接取第一段导致"每次换一个 XFF 就换一个计数桶"，登录爆破可无限绕过。
     *
     * <p>仅当 TCP 源地址命中受信代理时，才解析 XFF，并从右往左跳过受信代理，
     * 返回第一个不可信地址（最右一跳才是最近代理追加的真实来源；左侧段可能是客户端伪造的）。
     * 全部跳都受信或 XFF 为空时退回 remoteAddr。
     */
    private String clientIp(HttpServletRequest request) {
        String remote = request.getRemoteAddr();
        if (!TrustedProxies.matches(remote, properties.getTrustedProxies())) {
            return remote;
        }
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded == null || forwarded.isBlank()) {
            return remote;
        }
        String[] hops = forwarded.split(",");
        for (int i = hops.length - 1; i >= 0; i--) {
            String hop = hops[i].trim();
            if (hop.isEmpty()) {
                continue;
            }
            if (!TrustedProxies.matches(hop, properties.getTrustedProxies())) {
                return hop;
            }
        }
        return remote;
    }
}
