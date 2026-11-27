package com.travel.agent.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.travel.agent.llm.LlmClient;
import com.travel.agent.llm.LlmMessage;
import com.travel.agent.tool.AgentTool;
import com.travel.agent.tool.RiskLevel;
import com.travel.agent.tool.ToolRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 内置可调用工具白名单：{@code ocr_invoice} 能进提示词目录，也能被模型选为 {@code INVOKE_TOOL}；
 * 未注册或不在白名单的工具一律交回规则引擎，绝不执行模型编造的工具名。
 *
 * <p>纯单测：脚本化 {@link LlmClient}，不发网络请求（R6）。
 */
class IntentAgentBuiltinToolTest {

    /** 桩工具的描述单独取一个可搜索的句子，避免与提示词正文里的「发票识别」字样混淆 */
    private static final String STUB_DESCRIPTION = "识别当前用户最新一张发票照片（金额/商户/日期/类目）";

    /** 只读桩工具：本测试只校验「名字 + 描述」进目录的判定，execute 不会被调用 */
    private static final class StubTool implements AgentTool {
        private final String name;

        StubTool(String name) {
            this.name = name;
        }

        @Override
        public String getName() {
            return name;
        }

        @Override
        public String getDescription() {
            return STUB_DESCRIPTION;
        }

        @Override
        public RiskLevel getRiskLevel() {
            return RiskLevel.LOW;
        }

        @Override
        public Object execute(Map<String, Object> args) {
            return Map.of("available", true);
        }
    }

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

    private final ObjectMapper objectMapper = new ObjectMapper();

    private IntentAgent intentAgent(LlmClient llm, AgentTool... tools) {
        return new IntentAgent(llm, objectMapper, new ToolRegistry(List.of(tools)));
    }

    private AgentContext context(String message) {
        AgentContext ctx = new AgentContext();
        ctx.setUserId(1L);
        ctx.setUsername("tester");
        ctx.setMessage(message);
        return ctx;
    }

    private static String promptOf(ScriptedLlm llm) {
        return llm.requests.get(0).stream()
                .map(LlmMessage::content)
                .collect(Collectors.joining("\n"));
    }

    @Test
    @DisplayName("白名单内置工具 ocr_invoice 可被模型选为 INVOKE_TOOL")
    void builtinWhitelistedToolIsAccepted() {
        ScriptedLlm llm = new ScriptedLlm(
                "{\"intent\":\"INVOKE_TOOL\",\"target\":\"NONE\",\"tool\":\"ocr_invoice\",\"args\":{}}");

        IntentResult result = intentAgent(llm, new StubTool("ocr_invoice")).detect(context("报销这张发票"), null);

        assertThat(result.getIntent()).isEqualTo(IntentResult.INVOKE_TOOL);
        assertThat(result.getToolName()).isEqualTo("ocr_invoice");
    }

    @Test
    @DisplayName("未注册或不在白名单的工具名交回规则引擎（不执行模型编造的工具）")
    void nonWhitelistedToolsFallBackToRules() {
        String ghost = "{\"intent\":\"INVOKE_TOOL\",\"target\":\"NONE\",\"tool\":\"random_tool\",\"args\":{}}";
        assertThat(intentAgent(new ScriptedLlm(ghost), new StubTool("ocr_invoice"))
                .detect(context("报销这张发票"), null).getIntent())
                .as("未注册的工具名不得进入 INVOKE_TOOL")
                .isEqualTo(IntentResult.RECORD_INVOICE);

        // 注册了但不在内置白名单（规划期取证工具）同样不许模型直接调
        String notWhitelisted = "{\"intent\":\"INVOKE_TOOL\",\"target\":\"NONE\",\"tool\":\"query_weather\",\"args\":{}}";
        IntentResult registered = intentAgent(new ScriptedLlm(notWhitelisted), new StubTool("query_weather"))
                .detect(context("报销这张发票"), null);
        assertThat(registered.getIntent()).isEqualTo(IntentResult.RECORD_INVOICE);
        assertThat(registered.getToolName()).isNull();
    }

    @Test
    @DisplayName("提示词目录只列可调用工具：内置白名单在内，普通内置工具与同名兜底文案按注册情况切换")
    void promptListsOnlyInvokableTools() {
        ScriptedLlm withTools = new ScriptedLlm("{\"intent\":\"CHITCHAT\",\"target\":\"NONE\"}");
        intentAgent(withTools, new StubTool("ocr_invoice"), new StubTool("query_weather"))
                .detect(context("你好"), null);
        String prompt = promptOf(withTools);
        assertThat(prompt).contains("ocr_invoice");
        assertThat(prompt).contains(STUB_DESCRIPTION);
        assertThat(prompt).contains("只读");
        // 内置发票工具进目录后，「报销/入账」仍必须留在 RECORD_INVOICE（生成待确认方案），不能被 INVOKE_TOOL 抢走
        assertThat(prompt).contains("仍归 RECORD_INVOICE");
        assertThat(prompt).doesNotContain("query_weather");

        ScriptedLlm withoutTools = new ScriptedLlm("{\"intent\":\"CHITCHAT\",\"target\":\"NONE\"}");
        intentAgent(withoutTools).detect(context("你好"), null);
        String fallback = promptOf(withoutTools);
        assertThat(fallback).doesNotContain("ocr_invoice");
        assertThat(fallback).contains("未接入");
        assertThat(fallback).contains("不要使用 INVOKE_TOOL");
    }
}