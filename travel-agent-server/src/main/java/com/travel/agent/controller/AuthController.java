package com.travel.agent.controller;

import com.travel.agent.dto.ApiResponse;
import com.travel.agent.dto.AuthDtos;
import com.travel.agent.entity.User;
import com.travel.agent.security.AuthCookieService;
import com.travel.agent.service.AuthService;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;
    private final AuthCookieService authCookieService;

    @PostMapping("/register")
    public ApiResponse<AuthDtos.AuthResponse> register(@Valid @RequestBody AuthDtos.RegisterRequest request,
                                                       HttpServletResponse response) {
        AuthDtos.AuthResponse auth = authService.register(request.getUsername(), request.getPassword(), request.getNickname());
        authCookieService.write(response, auth.getToken());
        return ApiResponse.ok(auth);
    }

    @PostMapping("/login")
    public ApiResponse<AuthDtos.AuthResponse> login(@Valid @RequestBody AuthDtos.LoginRequest request,
                                                    HttpServletResponse response) {
        AuthDtos.AuthResponse auth = authService.login(request.getUsername(), request.getPassword());
        authCookieService.write(response, auth.getToken());
        return ApiResponse.ok(auth);
    }

    /**
     * 恢复登录态：页面刷新后内存里的用户信息没了，凭 httpOnly Cookie 换回用户资料。
     * 未登录由安全链统一返回 401，前端据此跳登录页。
     */
    @GetMapping("/me")
    public ApiResponse<AuthDtos.AuthResponse> me(Authentication authentication) {
        User user = authService.requireUser(authentication.getName());
        return ApiResponse.ok(AuthDtos.AuthResponse.builder()
                .id(user.getId())
                .username(user.getUsername())
                .nickname(user.getNickname())
                .build());
    }

    /** 登出：清掉浏览器里的登录 Cookie（幂等） */
    @PostMapping("/logout")
    public ApiResponse<Void> logout(HttpServletResponse response) {
        authCookieService.clear(response);
        return ApiResponse.ok(null);
    }
}
