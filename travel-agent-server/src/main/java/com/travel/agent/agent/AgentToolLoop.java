package com.travel.agent.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.travel.agent.llm.LlmClient;
import com.travel.agent.llm.LlmMessage;
import com.travel.agent.tool.ToolInvoker;
import com.travel.agent.tool.ToolRegistry;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Agent 自主工具调用循环（V2.2 FR-03）。
 *
 * <p>与「协调器预先固定调用工具」的旧模式不同：循环把目标、可用工具清单和已有观察交给
 * LLM，由模型自己决定下一步——调用哪个工具、构造什么参数、还是宣布完成。
 * 每次工具调用都经 {@link ToolInvoker}（沿用只读门禁、审计、SSE 事件与落库追踪），
 * 结果作为观察追加进下一轮决策，直到模型输出 FINAL 或预算耗尽。
 *
 * <p>防护（对齐设计文档 5.3）：
 * <ul>
 *   <li><b>步骤预算</b>：MAX_STEPS 上限，超限即停止并按未完成返回（调用方走既有降级路径）；</li>
 *   <li><b>重复调用去重</b>：同工具+同参数的第二次调用直接跳过，观察里说明原因；</li>
 *   <li><b>工具白名单</b>：循环只允许传入的只读工具，白名单外的名字记为观察让模型自纠；</li>
 *   <li><b>协作式取消</b>：每步之间检查 {@code ctx.isCancelled()}，绝不 interrupt 池线程；</li>
 *   <li><b>失败不伪造</b>：LLM 不可用 / 输出非法 / 工具抛错都如实反映在结果里，不编造事实。</li>
 * </ul>
 *
 * <p>降级约定：{@code run} 返回的 {@code completed=false} 表示循环没有拿到可用结论
 * （LLM 不可用、预算耗尽或格式错误），调用方必须继续走既有固定调用路径——
 * 无 Key / 无 LLM 时行为与从前完全一致。
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class AgentToolLoop {

    /** 循环指标名：agent.tool.loop{result=completed|budget_exhausted|llm_unavailable|error} */
    static final String LOOP_METRIC = "agent.tool.loop";

    /** 单条观察的截断长度（防 prompt 无限膨胀） */
    private static final int OBSERVATION_MAX = 220;
    /** 参与决策的最大观察条数（更早的观察丢弃） */
    private static final int OBSERVATION_WINDOW = 8;

    private final LlmClient llmClient;
    private final ToolRegistry toolRegistry;
    private final ToolInvoker toolInvoker;
    private final ObjectMapper objectMapper;
    private final MeterRegistry meterRegistry;

    /** 一步执行：工具名 + 参数 + 结果摘要（或失败原因） */
    public record ToolStep(String toolName, Map<String, Object> args, boolean success, String observation) { }

    /**
     * 循环结果：completed=true 表示模型宣布完成并给出 summary；
     * steps 是全部执行过的工具步（含被跳过的重复调用），notes 是给下游的观察摘要。
     */
    public record LoopResult(boolean completed, String summary, List<ToolStep> steps) {
        /** 下游可直接拼进规划 Prompt 的事实清单（工具名 + 结果摘要） */
        public List<String> notes() {
            return steps.stream().filter(ToolStep::success)
                    .map(s -> s.toolName() + ": " + s.observation())
                    .toList();
        }
    }

    /**
     * 运行循环。goal 是研究目标（含城市/天数/预算/日期上下文），
     * allowedTools 是本次允许的只读工具名集合。
     */
    public LoopResult run(AgentContext ctx, String goal, Set<String> allowedTools, int maxSteps) {
        if (!llmClient.available()) {
            count("llm_unavailable");
            return new LoopResult(false, null, List.of());
        }
        AgentBudget budget = ctx.getBudget();
        List<ToolStep> steps = new ArrayList<>();
        Set<String> called = new java.util.HashSet<>();
        List<String> observations = new ArrayList<>();

        for (int step = 1; step <= maxSteps; step++) {
            if (ctx.isCancelled()) {
                count("cancelled");
                return new LoopResult(false, "客户端已断开", steps);
            }
            // 预算门禁放在每步开始前：一步一次 LLM 决策 + 至多一次工具调用，
            // 剩余预算连一次调用都不够时就停手，已收集的观察按 ADR-002 补充条款保留给下游。
            if (!budget.allowsLlmCall(AgentBudget.MIN_RESEARCH_STEP_MS)) {
                count("time_budget_exhausted");
                ctx.recordBudgetDegradation(AgentBudget.STAGE_RESEARCH,
                        "预算耗尽，研究循环提前结束（已收集的观察仍然保留）");
                return new LoopResult(false, "时间预算耗尽", steps);
            }
            String decisionText = budget.chat(llmClient, decisionMessages(goal, allowedTools, observations), 0.1);
            if (decisionText == null || decisionText.isBlank()) {
                count("llm_unavailable");
                return new LoopResult(false, "模型不可用", steps);
            }
            JsonNode decision = parseDecision(decisionText);
            if (decision == null) {
                observations.add("上一步输出不是合法 JSON 动作，请只输出一个 JSON 对象");
                continue;
            }
            String action = decision.path("action").asText("");
            if ("FINAL".equals(action)) {
                count("completed");
                return new LoopResult(true, decision.path("summary").asText(""), steps);
            }
            if (!"CALL_TOOL".equals(action)) {
                observations.add("未知动作 '" + action + "'，只能输出 CALL_TOOL 或 FINAL");
                continue;
            }
            String toolName = decision.path("tool").asText("");
            Map<String, Object> args = readArgs(decision.path("args"));
            String dedupKey = toolName + "|" + args;
            if (!allowedTools.contains(toolName) || toolRegistry.get(toolName) == null) {
                observations.add("工具 '" + toolName + "' 不在本次允许清单内，请从清单中选择或输出 FINAL");
                continue;
            }
            if (called.contains(dedupKey)) {
                observations.add("工具 '" + toolName + "' 的相同参数已调用过，不要重复，请换参数或输出 FINAL");
                steps.add(new ToolStep(toolName, args, false, "重复调用被跳过"));
                continue;
            }
            called.add(dedupKey);
            String observation = executeAndObserve(ctx, toolName, args, steps);
            observations.add(observation);
        }
        count("budget_exhausted");
        return new LoopResult(false, "步骤预算耗尽（" + maxSteps + " 步）", steps);
    }

    /** 执行一次工具调用并把它变成观察；工具异常不中断循环（记为失败观察，模型可自纠） */
    private String executeAndObserve(AgentContext ctx, String toolName, Map<String, Object> args,
                                     List<ToolStep> steps) {
        try {
            Object result = toolInvoker.invoke(ctx, toolName, args);
            String observation = truncate(render(result), OBSERVATION_MAX);
            steps.add(new ToolStep(toolName, args, true, observation));
            return toolName + " → " + observation;
        } catch (RuntimeException e) {
            String failure = toolName + " 调用失败: " + truncate(e.getMessage() == null
                    ? e.getClass().getSimpleName() : e.getMessage(), OBSERVATION_MAX);
            steps.add(new ToolStep(toolName, args, false, failure));
            return failure;
        }
    }

    private List<LlmMessage> decisionMessages(String goal, Set<String> allowedTools, List<String> observations) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("目标：").append(goal).append("\n\n");
        prompt.append("可用工具（只读）：\n");
        for (String name : allowedTools) {
            var tool = toolRegistry.get(name);
            String desc = tool == null ? "" : truncate(tool.getDescription(), 80);
            prompt.append("- ").append(name).append(": ").append(desc).append('\n');
        }
        prompt.append("""

                动作协议（只能输出一个 JSON 对象，不要输出任何其它文字）：
                调用工具：{"action":"CALL_TOOL","tool":"工具名","args":{...},"reason":"简短理由"}
                完成研究：{"action":"FINAL","summary":"已收集事实的一句话总结"}

                规则：
                1. 信息足够支撑目标时立即 FINAL，不要为了用工具而用工具。
                2. 相同工具与参数不要重复调用。
                3. 工具失败时换参数重试一次或放弃，绝不编造结果。
                """);
        prompt.append("\n已收集的观察（时间顺序）：");
        if (observations.isEmpty()) {
            prompt.append("（暂无）");
        } else {
            int from = Math.max(0, observations.size() - OBSERVATION_WINDOW);
            for (int i = from; i < observations.size(); i++) {
                prompt.append("\n").append(i - from + 1).append(". ").append(observations.get(i));
            }
        }
        prompt.append("\n\n下一步动作：");
        return List.of(LlmMessage.system("你是严格按协议工作的旅行研究 Agent，只输出 JSON 动作。"),
                LlmMessage.user(prompt.toString()));
    }

    /** 宽容解析：剥掉 ```json 围栏后取第一个平衡的 JSON 对象 */
    private JsonNode parseDecision(String text) {
        String cleaned = text.trim();
        if (cleaned.startsWith("```")) {
            cleaned = cleaned.replaceFirst("^```[a-zA-Z]*\\s*", "").replaceFirst("```\\s*$", "");
        }
        int start = cleaned.indexOf('{');
        if (start < 0) {
            return null;
        }
        int depth = 0;
        for (int i = start; i < cleaned.length(); i++) {
            char c = cleaned.charAt(i);
            if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) {
                    try {
                        return objectMapper.readTree(cleaned.substring(start, i + 1));
                    } catch (Exception e) {
                        return null;
                    }
                }
            }
        }
        return null;
    }

    /** 决策 JSON 的 args 节点转参数 Map；非法结构按空参数处理（后续校验/执行会给出观察） */
    @SuppressWarnings("unchecked")
    private Map<String, Object> readArgs(JsonNode argsNode) {
        if (argsNode == null || !argsNode.isObject()) {
            return Map.of();
        }
        try {
            return objectMapper.convertValue(argsNode, Map.class);
        } catch (RuntimeException e) {
            return Map.of();
        }
    }

    private String render(Object result) {
        if (result == null) {
            return "（无结果）";
        }
        if (result instanceof String s && !s.isBlank()) {
            return s;
        }
        try {
            return objectMapper.writeValueAsString(result);
        } catch (Exception e) {
            return String.valueOf(result);
        }
    }

    private void count(String result) {
        try {
            meterRegistry.counter(LOOP_METRIC, "result", result).increment();
        } catch (RuntimeException ignored) {
            // 指标故障不影响主流程
        }
    }

    private static String truncate(String text, int max) {
        if (text == null) {
            return "";
        }
        String normalized = text.replaceAll("\\s+", " ").trim();
        return normalized.length() > max ? normalized.substring(0, max) + "…" : normalized;
    }
}
