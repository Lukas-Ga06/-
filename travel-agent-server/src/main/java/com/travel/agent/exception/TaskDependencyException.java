package com.travel.agent.exception;

import java.util.List;

/**
 * 任务前置未完成（第十五批 FR-02/FR-04）。
 *
 * <p>{@code AgentTraceService.startTask} 在启动任务前校验依赖边：只要有一个前置任务
 * 不存在或未 SUCCEEDED，就拒绝启动并抛出本异常，由 {@code Coordinator} 记录一条
 * CONFLICT 任务、发 conflict 事件，再决定是否重派。
 *
 * <p>不继承 {@link BusinessException}：它不是用户输入错误，而是编排层信号，
 * 正常路径下会被 Coordinator 捕获，不会冒泡成 HTTP 错误。
 */
public class TaskDependencyException extends RuntimeException {

    /** 未满足的前置任务 id（不存在或未成功） */
    private final List<String> missingTaskIds;

    public TaskDependencyException(String receiver, String taskType, List<String> missingTaskIds) {
        super("任务 " + receiver + "/" + taskType + " 的前置未完成: " + String.join(",", missingTaskIds));
        this.missingTaskIds = List.copyOf(missingTaskIds);
    }

    public List<String> missingTaskIds() {
        return missingTaskIds;
    }
}