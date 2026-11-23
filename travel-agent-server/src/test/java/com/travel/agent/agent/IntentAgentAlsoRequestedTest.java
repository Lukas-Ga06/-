package com.travel.agent.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.travel.agent.llm.LlmClient;
import com.travel.agent.llm.LlmMessage;
import com.travel.agent.tool.ToolRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * P0-B 多意图：LLM 路径把「其余独立请求」解析进 IntentResult.alsoRequested，
 * 并过滤空值/超长、限制条数；规则兜底路径保持空（不臆造未执行请求）。
 */
class IntentAgentAlsoRequestedTest {

    /** 可捕获提示词的脚本化 LLM */
    private static final class ScriptedLlm implements LlmClient {
        final List<List<LlmMessage>> requests = new ArrayList<>();
        private final String response;

        ScriptedLlm(String response) {
            this.response = response;
        }

        @Override
        public String chat(List<LlmMessage> messages, double temperature) {
            requests.add(messages);
            return response;
        }

        @Override
        public boolean available() {
            return true;
        }
    }

    private static final class UnavailableLlm implements LlmClient {
        @Override
        public String chat(List<LlmMessage> messages, double temperature) {
            throw new IllegalStateException("不应被调用");
        }

        @Override
        public boolean available() {
            return false;
        }
    }

    private final ObjectMapper objectMapper = new ObjectMapper();

    private IntentAgent intentAgent(LlmClient llm) {
        return new IntentAgent(llm, objectMapper, new ToolRegistry(List.of()));
    }

    private AgentContext context(String message) {
        AgentContext ctx = new AgentContext();
        ctx.setUserId(1L);
        ctx.setUsername("tester");
        ctx.setMessage(message);
        return ctx;
    }

    @Test
    @DisplayName("解析 alsoRequested：过滤空值/超长，最多保留 3 条")
    void parsesAndFiltersAlsoRequested() {
        String tooLong = "很长".repeat(21); // 42 字 > 40
        String response = "{\"intent\":\"PLAN_TRIP\",\"target\":\"NONE\",\"alsoRequested\":["
                + "\"查一下上海天气\",\"\",\"把第二天的酒店换掉\",\"" + tooLong + "\",\"帮我记一笔账\",\"第四条多余\"]}";
        ScriptedLlm llm = new ScriptedLlm(response);

        IntentResult result = intentAgent(llm).detect(context("帮我规划上海三日游，顺便查天气、换酒店、记账"), null);

        assertThat(result.getIntent()).isEqualTo(IntentResult.PLAN_TRIP);
        assertThat(result.getAlsoRequested())
                .containsExactly("查一下上海天气", "把第二天的酒店换掉", "帮我记一笔账");
    }

    @Test
    @DisplayName("没有 alsoRequested 字段 / 不是数组时按空列表处理（不臆造未执行请求）")
    void missingAlsoRequestedYieldsEmpty() {
        ScriptedLlm llm = new ScriptedLlm("{\"intent\":\"QUERY_KNOWLEDGE\",\"target\":\"NONE\"}");
        IntentResult result = intentAgent(llm).detect(context("杭州有什么好玩的"), null);

        assertThat(result.getAlsoRequested()).isEmpty();
    }

    @Test
    @DisplayName("规则兜底路径 alsoRequested 保持为空")
    void ruleFallbackHasNoAlsoRequested() {
        IntentResult result = intentAgent(new UnavailableLlm()).detect(context("你好"), null);

        assertThat(result.getIntent()).isEqualTo(IntentResult.CHITCHAT);
        assertThat(result.getAlsoRequested()).isEmpty();
    }

    @Test
    @DisplayName("提示词要求模型把其余请求放进 alsoRequested，且当前仍只处理最重要的一个意图")
    void promptAsksForAlsoRequested() {
        ScriptedLlm llm = new ScriptedLlm("{\"intent\":\"CHITCHAT\",\"target\":\"NONE\"}");
        intentAgent(llm).detect(context("你好"), null);

        String prompt = llm.requests.get(0).stream()
                .map(LlmMessage::content)
                .collect(java.util.stream.Collectors.joining("\n"));
        assertThat(prompt).contains("alsoRequested");
        assertThat(prompt).contains("最多 3 条、每条不超过 40 字");
        assertThat(prompt).contains("只处理最重要的一个意图");
    }
}
