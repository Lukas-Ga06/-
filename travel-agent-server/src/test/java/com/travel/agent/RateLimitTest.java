package com.travel.agent;

import com.travel.agent.support.IntegrationTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 限流生效验证。单独开一个上下文：只在本类打开限流并把登录阈值调到 2，
 * 避免影响其它需要大量注册/登录的测试。
 */
@SpringBootTest(properties = {
        "travel.rate-limit.enabled=true",
        "travel.rate-limit.auth-limit=2"
})
class RateLimitTest extends IntegrationTestSupport {

    private static final String LOGIN_BODY = "{\"username\":\"nobody\",\"password\":\"wrong-password\"}";

    @Test
    @DisplayName("同一 IP 超过登录阈值后返回 429 并带 Retry-After")
    void loginIsRateLimitedPerIp() throws Exception {
        String ip = "10.9.9.1";

        for (int i = 0; i < 2; i++) {
            mockMvc.perform(post("/api/auth/login")
                            .with(request -> {
                                request.setRemoteAddr(ip);
                                return request;
                            })
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(LOGIN_BODY))
                    .andExpect(status().isUnauthorized());
        }

        MvcResult limited = mockMvc.perform(post("/api/auth/login")
                        .with(request -> {
                            request.setRemoteAddr(ip);
                            return request;
                        })
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(LOGIN_BODY))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value(429))
                .andExpect(header().exists("Retry-After"))
                .andReturn();
        // Retry-After: 0 会诱导客户端立刻重试，反而放大压力；必须 >= 1
        assertThat(Integer.parseInt(limited.getResponse().getHeader("Retry-After")))
                .isGreaterThanOrEqualTo(1);
    }

    @Test
    @DisplayName("不同 IP 之间互不影响")
    void otherIpIsNotAffected() throws Exception {
        String limited = "10.9.9.2";
        for (int i = 0; i < 2; i++) {
            mockMvc.perform(post("/api/auth/login")
                            .with(request -> {
                                request.setRemoteAddr(limited);
                                return request;
                            })
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(LOGIN_BODY))
                    .andExpect(status().isUnauthorized());
        }

        mockMvc.perform(post("/api/auth/login")
                        .with(request -> {
                            request.setRemoteAddr("10.9.9.3");
                            return request;
                        })
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(LOGIN_BODY))
                .andExpect(status().isUnauthorized());
    }
}
