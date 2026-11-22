package com.travel.agent;

import com.travel.agent.support.IntegrationTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 限流客户端 IP：默认（未配置受信代理）必须完全不读 X-Forwarded-For。
 *
 * <p>修复前 {@code RateLimitFilter} 无条件把 XFF 第一段当客户端 IP，
 * 攻击者每次换一个伪造值就换一个计数桶，登录爆破可无限绕过。
 */
@SpringBootTest(properties = {
        "travel.rate-limit.enabled=true",
        "travel.rate-limit.auth-limit=2"
})
class RateLimitForwardedForTest extends IntegrationTestSupport {

    private static final String LOGIN_BODY = "{\"username\":\"nobody\",\"password\":\"wrong-password\"}";

    @Test
    @DisplayName("未配置受信代理：伪造 X-Forwarded-For 不新建计数桶，第三次仍 429")
    void forgedForwardedForDoesNotBypassLimit() throws Exception {
        String ip = "10.9.8.1";

        login(ip, "1.2.3.4").andExpect(status().isUnauthorized());
        login(ip, "1.2.3.5").andExpect(status().isUnauthorized());
        // 换一个完全不同且每次不同的伪造 XFF：仍命中同一个桶
        login(ip, "6.6.6.6").andExpect(status().isTooManyRequests());
        login(ip, "7.7.7.7").andExpect(status().isTooManyRequests());
    }

    @Test
    @DisplayName("未配置受信代理：带 XFF 与不带 XFF 命中同一个桶")
    void forwardedForAndPlainRequestShareBucket() throws Exception {
        String ip = "10.9.8.2";

        login(ip, "1.1.1.1").andExpect(status().isUnauthorized());
        login(ip, null).andExpect(status().isUnauthorized());
        login(ip, "2.2.2.2").andExpect(status().isTooManyRequests());
    }

    /** @param forwardedFor null 表示不带 X-Forwarded-For 头 */
    private ResultActions login(String remoteAddr, String forwardedFor) throws Exception {
        var builder = post("/api/auth/login")
                .with(request -> {
                    request.setRemoteAddr(remoteAddr);
                    return request;
                })
                .contentType(MediaType.APPLICATION_JSON)
                .content(LOGIN_BODY);
        if (forwardedFor != null) {
            builder = builder.header("X-Forwarded-For", forwardedFor);
        }
        return mockMvc.perform(builder);
    }
}