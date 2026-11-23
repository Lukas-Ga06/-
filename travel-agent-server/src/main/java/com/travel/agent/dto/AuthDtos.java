package com.travel.agent.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

public class AuthDtos {

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class LoginRequest {
        @NotBlank(message = "不能为空")
        private String username;
        @NotBlank(message = "不能为空")
        private String password;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class RegisterRequest {
        @NotBlank(message = "不能为空")
        @Size(min = 3, max = 32, message = "长度需为 3-32 个字符")
        private String username;
        @NotBlank(message = "不能为空")
        @Size(min = 6, max = 64, message = "长度需为 6-64 个字符")
        private String password;
        @Size(max = 32, message = "长度不能超过 32 个字符")
        private String nickname;
    }

    /**
     * 注销账号的确认凭据。
     *
     * <p>请求体整体可选，但一旦提供，{@code password} 必须非空白——只凭 httpOnly Cookie
     * 不能删除账号（浏览器被借用、CSRF 之外的误触都要再确认一次）。密码错误与账号不存在
     * 统一按 401 处理，不向调用方泄露账号是否存在。
     */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class DeleteAccountRequest {
        private String password;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class AuthResponse {
        private String token;
        private Long id;
        private String username;
        private String nickname;

        public static AuthResponse of(String token, com.travel.agent.entity.User user) {
            return AuthResponse.builder()
                    .token(token)
                    .id(user.getId())
                    .username(user.getUsername())
                    .nickname(user.getNickname())
                    .build();
        }
    }
}
