package com.travel.agent.controller;

import com.travel.agent.dto.ApiResponse;
import com.travel.agent.dto.AuthDtos;
import com.travel.agent.entity.User;
import com.travel.agent.exception.BusinessException;
import com.travel.agent.security.AuthCookieService;
import com.travel.agent.service.AccountDeletionService;
import com.travel.agent.service.AuthService;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.DeleteMapping;
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
    private final AccountDeletionService accountDeletionService;
    private final PasswordEncoder passwordEncoder;

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

    /**
     * 注销账号：删除本人全部旅行、照片、会话与派生数据，并清掉登录 Cookie。
     *
     * <p>必须二次确认密码：只有 httpOnly Cookie 不足以证明「此刻是账号本人在操作」。
     * 请求体缺省或密码空白 → 400；密码错误 → 401「密码错误」（不区分账号是否存在，避免枚举）。
     * 用户已不存在（例如重复调用）→ 401，不落成 500。
     *
     * <p>照片文件由 {@link AccountDeletionService} 在数据库事务提交后清理；
     * 本方法只在删除成功后清 Cookie，失败路径保持登录态不变，便于用户重试。
     */
    @DeleteMapping("/me")
    public ApiResponse<Void> deleteMe(@RequestBody(required = false) AuthDtos.DeleteAccountRequest request,
                                      Authentication authentication,
                                      HttpServletResponse response) {
        if (request == null || request.getPassword() == null || request.getPassword().isBlank()) {
            throw new BusinessException(400, "请输入密码确认注销");
        }
        User user = authService.requireUser(authentication.getName());
        if (!passwordEncoder.matches(request.getPassword(), user.getPassword())) {
            throw new BusinessException(401, "密码错误");
        }
        accountDeletionService.deleteAccount(user.getId(), user.getUsername());
        authCookieService.clear(response);
        return ApiResponse.ok(null);
    }

    /** 登出：清掉浏览器里的登录 Cookie（幂等） */
    @PostMapping("/logout")
    public ApiResponse<Void> logout(HttpServletResponse response) {
        authCookieService.clear(response);
        return ApiResponse.ok(null);
    }
}
