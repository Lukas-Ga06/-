package com.travel.agent.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;
import java.util.regex.Pattern;

@Component
@RequiredArgsConstructor
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    /** 票据唯一允许的两类只读路径（精确匹配，不做前缀/后缀放宽） */
    private static final String SSE_URI = "/api/agent/stream";
    private static final Pattern PHOTO_RAW_URI = Pattern.compile("^/api/photos/\\d+/raw$");

    private final JwtService jwtService;
    private final AccessTicketService accessTicketService;
    private final AuthCookieService authCookieService;

    /** 认证结果：用户名 + 是否"仅凭查询参数票据"完成认证 */
    private record Identity(String username, boolean ticketOnly) {
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        try {
            Identity identity = resolveIdentity(request);
            if (identity != null) {
                String username = identity.username();
                // 供同步请求链路深处的按用户逻辑使用（LLM 配额等）；异步执行器线程由 AgentEngine 另行设置
                UserContext.set(username);
                // 仅凭票据认证 = 只读会话。Cookie/Authorization 存在的请求不会走到这里设置该属性。
                if (identity.ticketOnly()) {
                    request.setAttribute(AuthAttributes.TICKET_ONLY, Boolean.TRUE);
                }
                if (SecurityContextHolder.getContext().getAuthentication() == null) {
                    UsernamePasswordAuthenticationToken authentication = new UsernamePasswordAuthenticationToken(
                            username, null, List.of(new SimpleGrantedAuthority("ROLE_USER")));
                    SecurityContextHolder.getContext().setAuthentication(authentication);
                }
            }
            filterChain.doFilter(request, response);
        } finally {
            UserContext.clear();
        }
    }

    private Identity resolveIdentity(HttpServletRequest request) {
        // 1. Authorization 头：非浏览器客户端（e2e 脚本、curl）与历史兼容
        String header = request.getHeader("Authorization");
        if (header != null && header.startsWith("Bearer ")) {
            return identity(jwtService.validate(header.substring(7)), false);
        }
        // 2. httpOnly Cookie：浏览器主路径（前端不再把 token 写 localStorage）
        String cookieToken = authCookieService.extract(request);
        if (cookieToken != null) {
            return identity(jwtService.validate(cookieToken), false);
        }
        // 3. 查询参数票据：只有它作为唯一凭证时才是只读会话
        return identity(ticketUsername(request), true);
    }

    private static Identity identity(String username, boolean ticketOnly) {
        return username == null ? null : new Identity(username, ticketOnly);
    }

    /**
     * 查询参数票据只对"无法自定义请求头"的两个只读场景生效：
     * GET /api/agent/stream（EventSource）与 GET /api/photos/{id}/raw（&lt;img&gt;）。
     *
     * <p>票据会随 URL 进入代理/网关日志、浏览器历史与 Referer，因此必须收窄用途：
     * 之前对任意 method/path 都接受票据，等于"泄漏一条日志 = 完整账号接管"，
     * 还可用票据调 POST /api/access-ticket 自我续期。这里用白名单彻底关掉这条路径。
     *
     * <p>同时校验票据 aud：照片路径只认 {@code media-read}，SSE 只认 {@code agent-stream}，
     * 两种票据互不通用（旧的无 aud 票据按 {@code agent-stream} 处理，兼容窗口 5 分钟）。
     *
     * <p>POST /api/agent/stream 本来就不接受票据（这里要求 GET），浏览器 fetch 自带 Cookie，
     * 所以 POST 入口没有"只读票据会话"这种状态。
     */
    private String ticketUsername(HttpServletRequest request) {
        if (!"GET".equalsIgnoreCase(request.getMethod())) {
            return null;
        }
        // getRequestURI() 不含 query string，也不含 context path（本项目 context path 为 /）
        String uri = request.getRequestURI();
        String expectedPurpose;
        if (SSE_URI.equals(uri)) {
            expectedPurpose = AccessTicketService.PURPOSE_AGENT_STREAM;
        } else if (PHOTO_RAW_URI.matcher(uri).matches()) {
            expectedPurpose = AccessTicketService.PURPOSE_MEDIA_READ;
        } else {
            return null;
        }
        String ticket = request.getParameter("ticket");
        return ticket == null ? null : accessTicketService.validate(ticket, expectedPurpose);
    }
}