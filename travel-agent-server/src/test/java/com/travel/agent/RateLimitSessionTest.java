package com.travel.agent;

import com.travel.agent.support.IntegrationTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 会话恢复专用限流桶：{@code GET /api/auth/me} 不能被登录桶（按 IP，默认 10）限流。
 *
 * <p>回归背景：前端每次刷新页面都要调 /me 恢复登录态。之前它落在 {@code /api/auth/**}
 * 的登录桶里，多标签页/多设备并发刷新会把同一 IP 的登录配额打满，
 * 用户侧表现是"刚登录就被 429 踢出登录页"，且真正需要保护的登录爆破限额被无辜消耗。
 *
 * <p>本类单独开上下文：打开限流、登录阈值调到 2（够注册一次 + 登录一次），
 * 会话桶保持默认 60。
 */
@SpringBootTest(properties = {
        "travel.rate-limit.enabled=true",
        "travel.rate-limit.auth-limit=2",
        "travel.rate-limit.session-limit=60"
})
class RateLimitSessionTest extends IntegrationTestSupport {

    @Test
    @DisplayName("连续 20 次 /me 不触发 429，且登录桶未被消耗、随后登录仍可用")
    void sessionBucketDoesNotExhaustLoginBucket() throws Exception {
        // 注册会消耗 1 次登录桶（auth-limit=2）
        Account account = registerNew();

        for (int i = 0; i < 20; i++) {
            mockMvc.perform(get("/api/auth/me").header("Authorization", account.bearer()))
                    .andExpect(status().isOk());
        }

        // 登录桶只剩 1 次：若 /me 混在登录桶里，这里就已经 429 了
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"%s\",\"password\":\"%s\"}"
                                .formatted(account.username(), PASSWORD)))
                .andExpect(status().isOk());
    }
}