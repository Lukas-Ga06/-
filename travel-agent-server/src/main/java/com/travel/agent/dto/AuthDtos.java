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
