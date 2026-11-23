package com.travel.agent.tool;

import com.travel.agent.agent.AgentContext;
import com.travel.agent.service.AgentTraceService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 工具调用门面：Agent 各环节通过它调用工具，
 * 统一经过 SafetyInterceptor 并向 SSE 流发送 tool_call / tool_result 事件。
 *
 * <p>V2.2 追踪（FR-10）：每次调用同时落一条 {@code tool_call_trace}
 * （参数/结果摘要 + 耗时 + 成败）。工具抛出的异常照常向上传播——
 * 落库只发生在成功或已捕获异常之后，追踪失败本身绝不影响调用链。
 *
 * <p>P0-B：只有声明了结构化参数（{@link AgentTool#getParameters()}）的工具才做严格校验——
 * 未知参数与缺失必填参数在 execute 之前就被拒绝，并同样落一条失败追踪；
 * MCP 工具保持宽松（上游 schema 未声明的字段可能仍可用，不能误伤）。
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class ToolInvoker {

    private final ToolRegistry toolRegistry;
    private final SafetyInterceptor safetyInterceptor;
    private final AgentTraceService traceService;

    public Object invoke(AgentContext ctx, String toolName, Map<String, Object> args) {
        AgentTool tool = toolRegistry.get(toolName);
        if (tool == null) {
            log.warn("工具不存在: {}", toolName);
            return null;
        }
        ctx.emit("tool_call", Map.of("tool", toolName, "args", args == null ? Map.of() : args));
        safetyInterceptor.check(ctx.getUsername(), tool, args);
        long startedAt = System.currentTimeMillis();
        try {
            validateArgs(tool, args);
            Object result = tool.execute(args);
            ctx.emit("tool_result", Map.of("tool", toolName, "result", result == null ? "null" : result));
            traceService.recordToolCall(ctx.getRunId(), ctx.getCurrentTaskId(), ctx.getUserId(),
                    toolName, args, result, true, null, System.currentTimeMillis() - startedAt);
            return result;
        } catch (RuntimeException e) {
            // 失败同样要留痕：errorCode 取异常类名+消息摘要，与文档 6.2 的 ToolResult 契约对齐
            String errorCode = e.getClass().getSimpleName()
                    + (e.getMessage() == null ? "" : ": " + e.getMessage());
            traceService.recordToolCall(ctx.getRunId(), ctx.getCurrentTaskId(), ctx.getUserId(),
                    toolName, args, null, false, errorCode, System.currentTimeMillis() - startedAt);
            throw e;
        }
    }

    /**
     * 按工具自己的声明校验参数（P0-B）。未声明参数的工具有效跳过（MCP 工具保持宽松）。
     *
     * <p>错误文案要能让模型在下一步自纠：未知参数时列出「可用参数」与「必填」，
     * 缺失必填时直接点名缺哪个——不能只给一句 ClassCastException/NPE。
     */
    static void validateArgs(AgentTool tool, Map<String, Object> args) {
        List<AgentTool.ToolParameter> declared = tool.getParameters();
        if (declared == null || declared.isEmpty()) {
            return;
        }
        Map<String, Object> actual = args == null ? Map.of() : args;
        List<String> names = declared.stream().map(AgentTool.ToolParameter::name).toList();
        List<String> unknown = actual.keySet().stream()
                .filter(key -> key != null && !names.contains(key))
                .sorted()
                .toList();
        List<String> required = declared.stream()
                .filter(AgentTool.ToolParameter::required)
                .map(AgentTool.ToolParameter::name)
                .toList();
        if (!unknown.isEmpty()) {
            throw new IllegalArgumentException("未知参数 " + String.join("、", unknown)
                    + "；可用参数：" + String.join("、", names)
                    + "；必填：" + (required.isEmpty() ? "无" : String.join("、", required)));
        }
        for (AgentTool.ToolParameter parameter : declared) {
            if (!parameter.required()) {
                continue;
            }
            Object value = actual.get(parameter.name());
            if (value == null || (value instanceof String text && text.isBlank())) {
                throw new IllegalArgumentException("缺少必填参数 " + parameter.name());
            }
        }
    }
}
