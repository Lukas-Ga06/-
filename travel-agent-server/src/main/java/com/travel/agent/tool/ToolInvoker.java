package com.travel.agent.tool;

import com.travel.agent.agent.AgentContext;
import com.travel.agent.service.AgentTraceService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 工具调用门面：Agent 各环节通过它调用工具，
 * 统一经过 SafetyInterceptor 并向 SSE 流发送 tool_call / tool_result 事件。
 *
 * <p>V2.2 追踪（FR-10）：每次调用同时落一条 {@code tool_call_trace}
 * （参数/结果摘要 + 耗时 + 成败）。工具抛出的异常照常向上传播——
 * 落库只发生在成功或已捕获异常之后，追踪失败本身绝不影响调用链。
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
}
