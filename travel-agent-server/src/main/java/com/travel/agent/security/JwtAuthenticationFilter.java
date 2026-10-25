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

@Component
@RequiredArgsConstructor
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private final JwtService jwtService;
    private final AccessTicketService accessTicketService;
    private final AuthCookieService authCookieService;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String username = resolveUsername(request);
        if (username != null && SecurityContextHolder.getContext().getAuthentication() == null) {
            UsernamePasswordAuthenticationToken authentication = new UsernamePasswordAuthenticationToken(
                    username, null, List.of(new SimpleGrantedAuthority("ROLE_USER")));
            SecurityContextHolder.getContext().setAuthentication(authentication);
        }
        filterChain.doFilter(request, response);
    }

    private String resolveUsername(HttpServletRequest request) {
        // 1. Authorization 头：非浏览器客户端（e2e 脚本、curl）与历史兼容
        String header = request.getHeader("Authorization");
        if (header != null && header.startsWith("Bearer ")) {
            return jwtService.validate(header.substring(7));
        }
        // 2. httpOnly Cookie：浏览器主路径（前端不再把 token 写 localStorage）
        String cookieToken = authCookieService.extract(request);
        if (cookieToken != null) {
            return jwtService.validate(cookieToken);
        }
        // EventSource / <img> 不支持自定义 Header，只能走查询参数。
        // 这里只接受短期只读票据：登录 JWT 一旦出现在 URL 里就会进日志与浏览器历史。
        String ticket = request.getParameter("ticket");
        return ticket == null ? null : accessTicketService.validate(ticket);
    }
}
