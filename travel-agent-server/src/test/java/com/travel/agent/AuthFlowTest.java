package com.travel.agent;

import com.travel.agent.support.IntegrationTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 认证链路：注册 / 登录 / 未授权访问 */
class AuthFlowTest extends IntegrationTestSupport {

    @Test
    @DisplayName("注册后拿到 token，可用该 token 访问受保护接口")
    void registerThenAccessProtectedEndpoint() throws Exception {
        Account account = registerNew();
        assertThat(account.token()).isNotBlank();

        mockMvc.perform(get("/api/trips").header("Authorization", account.bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));
    }

    @Test
    @DisplayName("登录成功返回 token；密码错误返回业务码 401")
    void loginSucceedsAndWrongPasswordIsRejected() throws Exception {
        Account account = registerNew();

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"%s\",\"password\":\"%s\"}".formatted(account.username(), PASSWORD)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.token").isNotEmpty());

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"%s\",\"password\":\"wrong-password\"}".formatted(account.username())))
                .andExpect(status().is4xxClientError())
                .andExpect(jsonPath("$.code").value(401));
    }

    @Test
    @DisplayName("未携带 token 访问受保护接口会被拒绝")
    void anonymousRequestIsRejected() throws Exception {
        mockMvc.perform(get("/api/trips"))
                .andExpect(status().is4xxClientError());
    }

    @Test
    @DisplayName("伪造的 token 会被拒绝")
    void forgedTokenIsRejected() throws Exception {
        mockMvc.perform(get("/api/trips").header("Authorization", "Bearer not-a-real-token"))
                .andExpect(status().is4xxClientError());
    }
}
