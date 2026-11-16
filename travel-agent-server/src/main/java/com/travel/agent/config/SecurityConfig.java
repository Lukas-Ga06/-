package com.travel.agent.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.travel.agent.dto.ApiResponse;
import com.travel.agent.security.RateLimitFilter;
import com.travel.agent.security.JwtAuthenticationFilter;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;

@Configuration
@EnableWebSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private final JwtAuthenticationFilter jwtAuthenticationFilter;
    private final RateLimitFilter rateLimitFilter;
    private final ObjectMapper objectMapper;

    @Value("${travel.cors.allowed-origins}")
    private String allowedOrigins;

    /** H2 控制台默认关闭（H2_CONSOLE_ENABLED）；只有显式开启时才加入白名单 */
    @Value("${spring.h2.console.enabled:false}")
    private boolean h2ConsoleEnabled;

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        // 免登录路径。H2 控制台以 sa 空密码直连数据库，默认绝不能放行：
        // 未启用时该路径不在白名单里，任何请求都会先被认证拦截（避免"匿名读写全库"）。
        String[] publicPaths = h2ConsoleEnabled
                ? new String[]{"/api/auth/login", "/api/auth/register", "/api/auth/logout",
                        "/api/channels/**", "/h2-console/**", "/error", "/actuator/health"}
                : new String[]{"/api/auth/login", "/api/auth/register", "/api/auth/logout",
                        "/api/channels/**", "/error", "/actuator/health"};
        http
                .csrf(csrf -> csrf.disable())
                .cors(Customizer.withDefaults())
                .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .headers(headers -> headers.frameOptions(frame -> frame.sameOrigin()))
                // 未登录 / 无权限时同样返回统一 JSON，前端才能拿到 message
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint(unauthorizedEntryPoint())
                        .accessDeniedHandler(accessDeniedHandler()))
                .authorizeHttpRequests(auth -> auth
                        // SSE/异步请求结束时容器会发起 ASYNC dispatch，必须放行，否则连接被安全链拒绝
                        .dispatcherTypeMatchers(jakarta.servlet.DispatcherType.ASYNC).permitAll()
                        // 登录/注册/登出无需登录；/api/auth/me 必须认证（凭 Cookie 或头恢复登录态）
                        // /api/channels/** 多渠道入口：secret/签名即凭证（恒时比较，未配置密钥一律拒绝）
                        // /actuator/health 给探活；其余 actuator 端点仍需登录
                        .requestMatchers(publicPaths).permitAll()
                        .requestMatchers(org.springframework.http.HttpMethod.OPTIONS).permitAll()
                        .anyRequest().authenticated())
                .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class)
                // 放在 JWT 之后：已登录按用户限流，未登录（登录/注册）按 IP 限流
                .addFilterAfter(rateLimitFilter, JwtAuthenticationFilter.class);
        return http.build();
    }

    private AuthenticationEntryPoint unauthorizedEntryPoint() {
        return (request, response, exception) ->
                writeJson(response, HttpStatus.UNAUTHORIZED, ApiResponse.error(401, "未登录或登录状态已过期"));
    }

    private AccessDeniedHandler accessDeniedHandler() {
        return (request, response, exception) ->
                writeJson(response, HttpStatus.FORBIDDEN, ApiResponse.error(403, "没有权限执行该操作"));
    }

    private void writeJson(HttpServletResponse response, HttpStatus status, ApiResponse<?> body) throws IOException {
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        objectMapper.writeValue(response.getWriter(), body);
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOrigins(Arrays.stream(allowedOrigins.split(",")).map(String::trim).toList());
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS", "PATCH"));
        config.setAllowedHeaders(List.of("*"));
        // 登录态走 httpOnly Cookie：跨域部署时需要携带凭证（同源经反代则不经过这里）
        config.setAllowCredentials(true);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
