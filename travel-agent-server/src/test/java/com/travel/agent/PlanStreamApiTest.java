package com.travel.agent;

import com.travel.agent.support.IntegrationTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * POST /api/agent/stream：结构化规划表单的流式端点。
 * 与 GET 版共用执行逻辑，但 message 走请求体、上限放宽到 2 万字符（攻略正文编译进需求）。
 */
class PlanStreamApiTest extends IntegrationTestSupport {

    @Test
    @DisplayName("无凭证访问 POST stream 返回 401")
    void postStreamRequiresAuth() throws Exception {
        mockMvc.perform(post("/api/agent/stream")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"规划东京三日游\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("message 超过 2 万字符被校验拒绝")
    void oversizedMessageRejected() throws Exception {
        Account user = registerNew();
        String oversized = "旅".repeat(20001);
        mockMvc.perform(post("/api/agent/stream")
                        .header("Authorization", user.bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(java.util.Map.of("message", oversized))))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("合法长文本需求正常建立 SSE 流并完成（测试 profile 走规则引擎）")
    void longMessageStreamsAndCompletes() throws Exception {
        Account user = registerNew();
        // 攻略整合形态的长文本：正文 + 需求说明，裁到 15000 字
        String guideBody = "上午去外滩，中午吃生煎，下午逛豫园。".repeat(1400);
        String message = ("请整合以下参考攻略为我规划上海行程：\n【攻略1】" + guideBody).substring(0, 15000);
        MvcResult done = sseDispatch(post("/api/agent/stream")
                .header("Authorization", user.bearer())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(java.util.Map.of("message", message))));
        org.assertj.core.api.Assertions.assertThat(done.getResponse().getContentType())
                .startsWith(MediaType.TEXT_EVENT_STREAM_VALUE);
    }
}
