package com.travel.agent.agent;

import com.travel.agent.service.AgentTraceService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.function.Function;
import java.util.function.Supplier;

/**
 * 阶段任务记录器（V2.2 FR-04 的最小任务形态，各 Agent 共用）。
 *
 * <p>把「开始任务 → 执行 → 结束任务」的落库样板从 {@link AgentEngine} 抽出，
 * 供引擎阶段与规划器内部的研究循环共用。执行期间把 taskId 挂到 {@link AgentContext}，
 * {@code ToolInvoker} 据此把工具调用关联到触发任务。
 *
 * <p>追踪写失败绝不反噬业务（{@link AgentTraceService} 内部吞异常）；
 * runId 为空时退化为直接执行。
 */
@Component
@RequiredArgsConstructor
public class AgentTaskRecorder {

    private final AgentTraceService traceService;

    public <T> T around(AgentContext ctx, String receiver, String taskType,
                        Supplier<T> body, Function<T, String> summarizer) {
        if (ctx == null || ctx.getRunId() == null) {
            return body.get();
        }
        String taskId = traceService.startTask(ctx.getRunId(), ctx.getUserId(), receiver, taskType);
        String previous = ctx.getCurrentTaskId();
        ctx.setCurrentTaskId(taskId);
        try {
            T result = body.get();
            traceService.finishTask(taskId, com.travel.agent.entity.AgentTask.STATUS_SUCCEEDED,
                    summarizer.apply(result), null);
            return result;
        } catch (RuntimeException e) {
            traceService.finishTask(taskId, com.travel.agent.entity.AgentTask.STATUS_FAILED, null, e.getMessage());
            throw e;
        } finally {
            ctx.setCurrentTaskId(previous);
        }
    }
}
