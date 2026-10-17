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
    private final RateLimiter limiter;
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
            long retryAfter = limiter.retryAfterSeconds(key, windowMillis, now);
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
        if (path.startsWith("/api/auth/")) {
            return new Rule("auth", "ip", properties.getAuthLimit());
        }
        if (path.startsWith("/api/agent/")) {
            return new Rule("agent", "user", properties.getAgentLimit());
        }
        if ("POST".equals(method)
                && (path.endsWith("/photos") || path.equals("/api/office/invoice/upload"))) {
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

    private String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }
}
