package com.travel.agent.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Agent 运行追踪查询响应（V2.2 FR-10：GET /api/agent/runs 与 /runs/{runId}）。
 *
 * <p>字段全部来自持久化表，口径与 SSE 事件互补：SSE 只在连接存活期间可见，
 * 这里可随时回查「这轮对话做了什么、调了哪些工具、各阶段耗时与成败」。
 * 摘要字段（argsDigest/resultDigest）为截断 JSON，不含密钥。
 */
public class AgentTraceDtos {

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class RunSummary {
        private String runId;
        private Long tripId;
        private String message;
        private String intent;
        private String status;
        private String error;
        private LocalDateTime startedAt;
        private LocalDateTime endedAt;
        private Long durationMs;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class TaskView {
        private String taskId;
        private String receiver;
        private String taskType;
        private String status;
        /** 前置任务 id 列表（第十五批；无前置为空数组，旧数据同样为空数组） */
        private List<String> dependsOn;
        /** 第几次尝试：0 = 首次；>0 = 回收重派（第十五批） */
        private Integer attempt;
        /** 本次任务替换掉的旧任务 id（第十五批；首次为 null） */
        private String replacesTaskId;
        private String summary;
        private String error;
        private LocalDateTime startedAt;
        private LocalDateTime endedAt;
        private Long durationMs;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ToolCallView {
        private Long id;
        private String taskId;
        private String toolName;
        private boolean success;
        private String errorCode;
        private Long latencyMs;
        private String argsDigest;
        private String resultDigest;
        private LocalDateTime startedAt;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class RunDetail {
        private RunSummary run;
        private List<TaskView> tasks;
        private List<ToolCallView> toolCalls;
    }

    public static RunSummary from(com.travel.agent.entity.AgentRun run) {
        return RunSummary.builder()
                .runId(run.getRunId())
                .tripId(run.getTripId())
                .message(run.getMessage())
                .intent(run.getIntent())
                .status(run.getStatus())
                .error(run.getError())
                .startedAt(run.getStartedAt())
                .endedAt(run.getEndedAt())
                .durationMs(run.durationMs())
                .build();
    }

    public static TaskView from(com.travel.agent.entity.AgentTask task) {
        return TaskView.builder()
                .taskId(task.getTaskId())
                .receiver(task.getReceiver())
                .taskType(task.getTaskType())
                .status(task.getStatus())
                .dependsOn(task.dependencies())
                .attempt(task.getAttempt() == null ? 0 : task.getAttempt())
                .replacesTaskId(task.getReplacesTaskId())
                .summary(task.getSummary())
                .error(task.getError())
                .startedAt(task.getStartedAt())
                .endedAt(task.getEndedAt())
                .durationMs(task.durationMs())
                .build();
    }

    public static ToolCallView from(com.travel.agent.entity.ToolCallTrace trace) {
        return ToolCallView.builder()
                .id(trace.getId())
                .taskId(trace.getTaskId())
                .toolName(trace.getToolName())
                .success(trace.isSuccess())
                .errorCode(trace.getErrorCode())
                .latencyMs(trace.getLatencyMs())
                .argsDigest(trace.getArgsDigest())
                .resultDigest(trace.getResultDigest())
                .startedAt(trace.getStartedAt())
                .build();
    }
}
