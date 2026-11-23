package com.travel.agent.agent;

import com.travel.agent.dto.AgentDtos;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 多意图提示（P0-B）：一条消息里包含多个独立请求时，当前只执行最重要的一个，
 * 其余请求必须在最终回复里明确告知「这次没有执行」，不能静默只做一半。
 *
 * <p>提示同时挂到 REST 返回值与 SSE 的 {@code complete} 事件（两者都经
 * {@link AgentEngine} 的 complete/finish），靠前缀幂等保证一次运行只出现一次；
 * 没有 alsoRequested 时完全不改动已有回复。
 */
final class MultiIntentNotices {

    /** 提示固定前缀：既用于幂等判断，也便于前端/单测识别这一行 */
    static final String PREFIX = "另外你提到的";

    private MultiIntentNotices() {
    }

    /** 生成提示行；没有未执行请求时返回 null（调用方原样跳过） */
    static String line(List<String> alsoRequested) {
        if (alsoRequested == null || alsoRequested.isEmpty()) {
            return null;
        }
        String quoted = alsoRequested.stream()
                .filter(item -> item != null && !item.isBlank())
                .map(item -> "「" + item.trim() + "」")
                .collect(Collectors.joining());
        if (quoted.isEmpty()) {
            return null;
        }
        return PREFIX + quoted + "这次没有执行：为免一次改太多，请单独发一条，我再继续处理。";
    }

    /**
     * 把提示追加到最终回复末尾（幂等）：已有提示时不再追加，缺失时追加一行；
     * 没有未执行请求时不动已有回复。
     */
    static void attach(List<String> alsoRequested, AgentDtos.AgentResult result) {
        if (result == null) {
            return;
        }
        String message = result.getMessage();
        if (message != null && message.contains(PREFIX)) {
            return;
        }
        String line = line(alsoRequested);
        if (line == null) {
            return;
        }
        result.appendMessage("\n" + line);
    }
}
