package com.travel.agent.security;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Service;

/**
 * 登录态 httpOnly Cookie。
 *
 * 背景：token 存 localStorage 可被任何 XSS 脚本读走（照片上传的存储型 XSS 已堵，
 * 但存储位置本身仍是暴露面）。登录/注册成功后由服务端种 httpOnly Cookie，
 * 浏览器后续请求自动携带；Authorization 头仍被接受（e2e 脚本与非浏览器客户端用），
 * 前端不再把 token 写进 localStorage。
 */
@Service
public class AuthCookieService {

    public static final String COOKIE_NAME = "travel_agent_token";

    private final JwtService jwtService;
    private final boolean secure;

    public AuthCookieService(JwtService jwtService,
                             @Value("${travel.jwt.cookie-secure:false}") boolean secure) {
        this.jwtService = jwtService;
        this.secure = secure;
    }

    /** 登录/注册成功后种 Cookie；maxAge 与 token 有效期对齐 */
    public void write(HttpServletResponse response, String token) {
        ResponseCookie cookie = ResponseCookie.from(COOKIE_NAME, token)
                .httpOnly(true)
                .secure(secure)
                .sameSite("Lax")
                .path("/")
                .maxAge(jwtService.expireSeconds())
                .build();
        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
    }

    /** 登出：清掉浏览器里的登录 Cookie */
    public void clear(HttpServletResponse response) {
        ResponseCookie cookie = ResponseCookie.from(COOKIE_NAME, "")
                .httpOnly(true)
                .secure(secure)
                .sameSite("Lax")
                .path("/")
                .maxAge(0)
                .build();
        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
    }

    /** 从请求中取出 token；无 Cookie 返回 null */
    public String extract(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return null;
        }
        for (Cookie cookie : cookies) {
            if (COOKIE_NAME.equals(cookie.getName()) && cookie.getValue() != null && !cookie.getValue().isBlank()) {
                return cookie.getValue();
            }
        }
        return null;
    }
}
