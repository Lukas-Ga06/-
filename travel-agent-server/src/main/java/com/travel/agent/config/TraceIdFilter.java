package com.travel.agent.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

/**
 * 请求链路标识。
 *
 * 每个请求生成（或沿用上游传入的）traceId，放进日志 MDC 并回写响应头，
 * 这样"用户报错 → 找到那一次请求的全部日志"不再靠时间戳猜。
 * 用 X-Trace-Id 而非自造格式，网关/APM 也能识别。
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class TraceIdFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Trace-Id";
    public static final String MDC_KEY = "traceId";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String incoming = request.getHeader(HEADER);
        String traceId = incoming != null && incoming.matches("[A-Za-z0-9_-]{6,64}")
                ? incoming
                : UUID.randomUUID().toString().replace("-", "").substring(0, 16);

        MDC.put(MDC_KEY, traceId);
        response.setHeader(HEADER, traceId);
        try {
            filterChain.doFilter(request, response);
        } finally {
            // 线程复用，必须清理，否则会串到下一个请求的日志里
            MDC.remove(MDC_KEY);
        }
    }
}
