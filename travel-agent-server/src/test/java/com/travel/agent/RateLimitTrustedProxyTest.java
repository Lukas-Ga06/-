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
 * 限流客户端 IP：配置受信代理后，从 X-Forwarded-For 右往左取第一个非受信地址。
 *
 * <p>本类 remoteAddr 固定为 10.x（命中 10.0.0.0/8 受信代理），模拟"请求经反向代理转发"。
 */
@SpringBootTest(properties = {
        "travel.rate-limit.enabled=true",
        "travel.rate-limit.auth-limit=2",
        "travel.rate-limit.trusted-proxies=10.0.0.0/8"
})
class RateLimitTrustedProxyTest extends IntegrationTestSupport {

    private static final String LOGIN_BODY = "{\"username\":\"nobody\",\"password\":\"wrong-password\"}";

    @Test
    @DisplayName("受信代理转发：取最右侧非受信地址，伪造的左侧段不改变计数桶")
    void rightmostUntrustedHopIsUsedAsClientIp() throws Exception {
        String proxy = "10.1.2.3";

        // 真实客户端 1.1.1.1，客户端伪造的左侧段各不相同
        login(proxy, "6.6.6.6, 1.1.1.1").andExpect(status().isUnauthorized());
        login(proxy, "7.7.7.7, 1.1.1.1").andExpect(status().isUnauthorized());
        // 仍取最右侧非受信地址 1.1.1.1 → 同一个桶 → 429
        login(proxy, "9.9.9.9, 1.1.1.1").andExpect(status().isTooManyRequests());

        // 换一个真实客户端 1.1.1.2 → 新桶，不应被上面的 429 影响
        login(proxy, "9.9.9.9, 1.1.1.2").andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("非受信来源即使带 XFF 也不采信：按自身 remoteAddr 计数")
    void untrustedPeerIgnoresForwardedFor() throws Exception {
        String direct = "192.0.2.7";

        login(direct, "1.1.1.1").andExpect(status().isUnauthorized());
        login(direct, "2.2.2.2").andExpect(status().isUnauthorized());
        login(direct, "3.3.3.3").andExpect(status().isTooManyRequests());
    }

    @Test
    @DisplayName("XFF 全部跳都是受信代理时退回 remoteAddr")
    void allHopsTrustedFallsBackToRemoteAddr() throws Exception {
        String proxy = "10.1.2.4";

        login(proxy, "10.5.5.5, 10.6.6.6").andExpect(status().isUnauthorized());
        login(proxy, "10.7.7.7").andExpect(status().isUnauthorized());
        // 都受信 → 都用 remoteAddr 10.1.2.4 作桶，第三次（不带 XFF）同样 429
        login(proxy, null).andExpect(status().isTooManyRequests());
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