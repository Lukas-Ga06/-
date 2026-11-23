package com.travel.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.travel.agent.agent.IntentAgent;
import com.travel.agent.agent.IntentResult;
import com.travel.agent.support.IntegrationTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * P0-B 多意图：REST {@code /api/agent/chat} 与 SSE {@code /api/agent/stream} 两条出口
 * 都必须在最终回复里带上「另外你提到的 X 这次没有执行」的提示，且只出现一次。
 *
 * <p>用 mock 的 {@link IntentAgent} 直接产出 alsoRequested（真实 LLM 不在测试里调用），
 * 其余链路（handler 发 complete 事件、引擎落会话、SSE 序列化）都走真实实现。
 */
class MultiIntentNoticeApiTest extends IntegrationTestSupport {

    @MockitoBean
    private IntentAgent intentAgent;

    private static final String NOTICE = "另外你提到的";

    private void stubIntentWithAlsoRequested() {
        when(intentAgent.detect(any(), any())).thenReturn(IntentResult.builder()
                .intent(IntentResult.QUERY_KNOWLEDGE)
                .target(IntentResult.TARGET_NONE)
                .alsoRequested(List.of("查一下天气"))
                .build());
    }

    @Test
    @DisplayName("同步 /chat：最终 message 带一次多意图提示")
    void chatResponseCarriesNoticeOnce() throws Exception {
        Account account = registerNew();
        stubIntentWithAlsoRequested();

        MvcResult result = mockMvc.perform(post("/api/agent/chat")
                        .header("Authorization", account.bearer())
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"杭州有什么好玩的，另外帮我查天气\"}"))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode data = body(result).path("data");
        String message = data.path("message").asText();
        assertThat(message).contains("另外你提到的「查一下天气」这次没有执行");
        assertThat(countNotice(message)).isEqualTo(1);
    }

    @Test
    @DisplayName("SSE /stream：complete 事件里同样带一次多意图提示（不能只在 REST 响应里）")
    void sseCompleteEventCarriesNoticeOnce() throws Exception {
        Account account = registerNew();
        stubIntentWithAlsoRequested();

        MvcResult result = sseDispatch(get("/api/agent/stream")
                .header("Authorization", account.bearer())
                .param("message", "杭州有什么好玩的，另外帮我查天气"));

        String stream = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(stream).contains("另外你提到的「查一下天气」这次没有执行");
        assertThat(countNotice(stream)).as("两条出口都不能重复追加").isEqualTo(1);
    }

    private static int countNotice(String text) {
        int count = 0;
        int index = text.indexOf(NOTICE);
        while (index >= 0) {
            count++;
            index = text.indexOf(NOTICE, index + NOTICE.length());
        }
        return count;
    }
}
