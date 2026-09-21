package com.travel.agent.tool;

import com.travel.agent.agent.AgentContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 工具调用门面：Agent 各环节通过它调用工具，
 * 统一经过 SafetyInterceptor 并向 SSE 流发送 tool_call / tool_result 事件。
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class ToolInvoker {

    private final ToolRegistry toolRegistry;
    private final SafetyInterceptor safetyInterceptor;

    public Object invoke(AgentContext ctx, String toolName, Map<String, Object> args) {
        AgentTool tool = toolRegistry.get(toolName);
        if (tool == null) {
            log.warn("工具不存在: {}", toolName);
            return null;
        }
        ctx.emit("tool_call", java.util.Map.of("tool", toolName, "args", args == null ? Map.of() : args));
        safetyInterceptor.check(ctx.getUsername(), tool, args);
        Object result = tool.execute(args);
        ctx.emit("tool_result", java.util.Map.of("tool", toolName, "result", result == null ? "null" : result));
        return result;
    }
}
