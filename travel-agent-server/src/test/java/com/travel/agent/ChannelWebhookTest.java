package com.travel.agent;

import com.travel.agent.support.IntegrationTestSupport;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 通用 Webhook 渠道：密钥即凭证（缺失/错误一律 401），有效密钥走同一个 AgentEngine
 * （测试 profile 下 LLM 关闭，走规则路径），账号不存在 404。
 */
@TestPropertySource(properties = {
        "travel.channels.webhook.enabled=true",
        "travel.channels.webhook.secret=test-webhook-secret"
})
class ChannelWebhookTest extends IntegrationTestSupport {

    private static final String SECRET = "test-webhook-secret";

    @Test
    void missingOrWrongSecretIsUnauthorized() throws Exception {
        mockMvc.perform(post("/api/channels/webhook")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"someone\",\"text\":\"你好\"}"))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(post("/api/channels/webhook")
                        .header("X-Webhook-Secret", "wrong-secret")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"someone\",\"text\":\"你好\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void validSecretRoutesMessageThroughAgentEngine() throws Exception {
        Account account = registerNew();
        mockMvc.perform(post("/api/channels/webhook")
                        .header("X-Webhook-Secret", SECRET)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + account.username() + "\",\"text\":\"记一下明天办签证\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.reply").value(org.hamcrest.Matchers.containsString("已添加备忘")));
    }

    @Test
    void unknownLocalUserIsNotFound() throws Exception {
        mockMvc.perform(post("/api/channels/webhook")
                        .header("X-Webhook-Secret", SECRET)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"no_such_user_xz\",\"text\":\"你好\"}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void blankTextIsBadRequest() throws Exception {
        Account account = registerNew();
        mockMvc.perform(post("/api/channels/webhook")
                        .header("X-Webhook-Secret", SECRET)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + account.username() + "\",\"text\":\"\"}"))
                .andExpect(status().isBadRequest());
    }
}
