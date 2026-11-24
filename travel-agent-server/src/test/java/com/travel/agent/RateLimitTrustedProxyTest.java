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
 *
 * <p>2026-11-23 容器验收补充：compose 曾把整个 {@code 172.16.0.0/12} 当受信网段，
 * 来源落在私网段的客户端被当成受信跳，XFF 被跳过后退回 nginx 容器 IP，全部塌成一个桶。
 * 修复后 compose 只信任 nginx 一个固定地址（{@code /32}），见 {@link #onlyConfiguredProxyAddressIsTrusted()}。
 */
@SpringBootTest(properties = {
        "travel.rate-limit.enabled=true",
        "travel.rate-limit.auth-limit=2",
        "travel.rate-limit.trusted-proxies=10.0.0.0/8,172.31.240.10"
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

    @Test
    @DisplayName("compose 单地址受信：同网段来源不再被当成受信跳而塌成代理桶")
    void onlyConfiguredProxyAddressIsTrusted() throws Exception {
        String proxy = "172.31.240.10";

        // 同网段的另一个容器/宿主机来源（172.31.240.11/12）：旧配置 172.16.0.0/12 会把它们
        // 当作受信跳跳过，退回代理 IP 共用一个桶；/32 配置下它们各自是独立客户端。
        login(proxy, "172.31.240.11").andExpect(status().isUnauthorized());
        login(proxy, "172.31.240.11").andExpect(status().isUnauthorized());
        login(proxy, "172.31.240.12").andExpect(status().isUnauthorized());
        login(proxy, "172.31.240.12").andExpect(status().isUnauthorized());
        // 两个来源都没有被并进代理桶：各自的桶打满后才 429
        login(proxy, "172.31.240.11").andExpect(status().isTooManyRequests());
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