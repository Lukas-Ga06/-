package com.travel.agent.agent;

import com.travel.agent.entity.AgentTask;
import com.travel.agent.exception.TaskDependencyException;
import com.travel.agent.security.UserContext;
import com.travel.agent.service.AgentTraceService;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * 任务图调度器（V2.2 FR-02 / FR-04，第十五批）。
 *
 * <p>把「固定阶段序」升级成可依赖、可委派、可回收重派的任务图。调度规则<b>全部确定性</b>：
 * 依赖关系由调用方（引擎/审核闭环）写死，模型只负责执行任务内容，<b>永远不参与调度</b>
 * （与 ADR-007「确定性自愈」同一原则——同样的输入必须得到同样的任务图）。
 *
 * <pre>
 * dispatch        前置全部 SUCCEEDED → 落 RUNNING 任务（带依赖边/attempt/replaces）→ delegate 事件 → 执行
 *                 前置不满足      → 落 CONFLICT 任务 → conflict 事件 → 抛 TaskConflictException（body 不执行）
 * redispatch      总是产生新任务行（attempt+1、replacesTaskId=旧行）→ recover 事件 → 执行
 * dispatchAll     先按声明顺序创建全部任务行（id 顺序稳定），再把 body 并行跑在 agent-dag 池
 * </pre>
 *
 * <p>失败/冲突时旧任务行保留为证据、新行承载最终结果：调用方只使用新行结果，
 * 同一逻辑工作不会出现两份可用结果。追踪不可用（runId 为空）时全部退化为直接执行。
 */
@Component
@Slf4j
public class Coordinator {

    /** 并行组线程池大小：一次规划最多 2 个并行任务（PREVIEW ‖ REVIEW），留余量给并发请求 */
    private static final int PARALLELISM = 4;

    /** 任务句柄：已落库任务的对外引用（taskId 为空表示追踪不可用） */
    public record TaskHandle(String taskId, String receiver, String taskType) {

        /** 依赖边用：把句柄转成 taskId 列表（null 句柄不构成依赖） */
        public List<String> ids() {
            return taskId == null ? List.of() : List.of(taskId);
        }
    }

    /**
     * 任务规格：receiver（谁做）/ taskType（做什么）/ 前置依赖 / 被替换的旧任务 / 第几次尝试。
     *
     * <p>{@code replacesTaskId} 与 {@code reason} 只在重派时有值；首次派发一律为空/0。
     */
    public record TaskSpec(String receiver, String taskType, List<TaskHandle> dependsOn,
                           String replacesTaskId, int attempt, String reason) {

        public TaskSpec {
            dependsOn = dependsOn == null ? List.of() : List.copyOf(dependsOn);
        }

        /** 无前置的首发任务 */
        public static TaskSpec of(String receiver, String taskType) {
            return new TaskSpec(receiver, taskType, List.of(), null, 0, null);
        }

        /** 依赖若干前置任务的首发任务 */
        public static TaskSpec after(String receiver, String taskType, TaskHandle... dependencies) {
            return new TaskSpec(receiver, taskType,
                    Arrays.stream(dependencies).filter(Objects::nonNull).toList(), null, 0, null);
        }

        /** 重派规格：替换掉 {@code replaced}，attempt 由调用方给（0 表示首次，1 表示第一次重派） */
        public TaskSpec replacing(TaskHandle replaced, int attempt, String reason) {
            return new TaskSpec(receiver, taskType, dependsOn,
                    replaced == null ? null : replaced.taskId(), attempt, reason);
        }
    }

    /** 派发回执：任务句柄 + 执行结果（只有一份） */
    public record Receipt<T>(TaskHandle handle, T value) {
    }

    /** 并行组的一个任务（spec + 执行体 + 摘要器） */
    public record ParallelTask<T>(TaskSpec spec, Supplier<T> body, Function<T, String> summarizer) {
    }

    /** 冲突事件负载（同时也是异常携带的结构） */
    public record Conflict(String receiver, String taskType, List<String> missingDeps, String reason) {
    }

    /** 冲突异常：Coordinator 已记录 CONFLICT 行与 conflict 事件后抛出 */
    public static class TaskConflictException extends RuntimeException {

        private final transient Conflict conflict;

        public TaskConflictException(Conflict conflict) {
            super("任务冲突: " + conflict.receiver() + "/" + conflict.taskType()
                    + " 前置未完成 " + conflict.missingDeps());
            this.conflict = conflict;
        }

        public Conflict conflict() {
            return conflict;
        }
    }

    private final AgentTraceService traceService;
    private final ExecutorService pool;
    private final AtomicInteger threadSeq = new AtomicInteger();

    public Coordinator(AgentTraceService traceService) {
        this.traceService = traceService;
        this.pool = Executors.newFixedThreadPool(PARALLELISM, runnable -> {
            Thread thread = new Thread(runnable, "agent-dag-" + threadSeq.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        });
    }

    // ==================== 顺序派发 ====================

    /**
     * 派发一个任务：前置必须全部 SUCCEEDED，否则记录冲突并抛出 {@link TaskConflictException}。
     *
     * @return 回执（句柄 + 结果）；追踪不可用时句柄的 taskId 为 null，body 仍然执行
     */
    public <T> Receipt<T> dispatch(AgentContext ctx, TaskSpec spec, Supplier<T> body,
                                   Function<T, String> summarizer) {
        return dispatchInternal(ctx, spec, body, summarizer, false);
    }

    /**
     * 回收重派：总是产生新任务行（attempt+1、replacesTaskId=旧任务），旧行状态不动。
     * 先发 recover 事件说明「替换了谁、为什么」，再发 delegate 事件。
     */
    public <T> Receipt<T> redispatch(AgentContext ctx, TaskSpec spec, Supplier<T> body,
                                     Function<T, String> summarizer) {
        return dispatchInternal(ctx, spec, body, summarizer, true);
    }

    private <T> Receipt<T> dispatchInternal(AgentContext ctx, TaskSpec spec, Supplier<T> body,
                                            Function<T, String> summarizer, boolean recovery) {
        if (ctx == null || ctx.getRunId() == null) {
            // 追踪不可用：不落任务、不发事件，直接执行（与升级前行为一致）
            return new Receipt<>(new TaskHandle(null, spec.receiver(), spec.taskType()), body.get());
        }
        String taskId;
        try {
            taskId = traceService.startTask(ctx.getRunId(), ctx.getUserId(), spec.receiver(), spec.taskType(),
                    dependencyIds(spec), spec.replacesTaskId(), spec.attempt());
        } catch (TaskDependencyException e) {
            Conflict conflict = new Conflict(spec.receiver(), spec.taskType(),
                    e.missingTaskIds(), "前置任务未完成，本次派发被拒绝");
            traceService.recordConflict(ctx.getRunId(), ctx.getUserId(), spec.receiver(), spec.taskType(),
                    dependencyIds(spec), conflictMessage(conflict), spec.attempt(), spec.replacesTaskId());
            emit(ctx, "conflict", conflictPayload(conflict));
            throw new TaskConflictException(conflict);
        }
        TaskHandle handle = new TaskHandle(taskId, spec.receiver(), spec.taskType());
        if (taskId == null) {
            return new Receipt<>(handle, body.get());
        }
        if (recovery) {
            emit(ctx, "recover", recoverPayload(handle, spec));
        }
        emit(ctx, "delegate", delegatePayload(handle, spec));
        return execute(ctx, handle, body, summarizer);
    }

    // ==================== 并行派发 ====================

    /**
     * 并行派发一组互不依赖（或依赖组外任务）的任务：
     * <ol>
     *   <li>先按声明顺序把任务行全部建好——id 顺序稳定，回查/展示不随执行顺序抖动；</li>
     *   <li>再把 body 并行提交到 {@code agent-dag} 池，全部结束后按声明顺序返回。</li>
     * </ol>
     *
     * <p>任一分支出错：其余分支取消、失败行落 FAILED，原异常向上抛（绝不吞掉）。
     * 追踪不可用时按声明顺序串行执行（行为与升级前一致）。
     */
    public List<Receipt<?>> dispatchAll(AgentContext ctx, List<ParallelTask<?>> tasks) {
        if (tasks == null || tasks.isEmpty()) {
            return List.of();
        }
        if (ctx == null || ctx.getRunId() == null) {
            List<Receipt<?>> serial = new ArrayList<>();
            for (ParallelTask<?> task : tasks) {
                serial.add(dispatchSerialOnly(ctx, task));
            }
            return serial;
        }

        List<TaskHandle> handles = new ArrayList<>();
        List<String> startedIds = new ArrayList<>();
        try {
            for (ParallelTask<?> task : tasks) {
                TaskSpec spec = task.spec();
                String taskId = traceService.startTask(ctx.getRunId(), ctx.getUserId(),
                        spec.receiver(), spec.taskType(),
                        dependencyIds(spec), spec.replacesTaskId(), spec.attempt());
                TaskHandle handle = new TaskHandle(taskId, spec.receiver(), spec.taskType());
                handles.add(handle);
                startedIds.add(taskId);
            }
        } catch (TaskDependencyException e) {
            // 组内启动失败：已建的行不可能再被执行，标记失败后记录冲突并抛出
            for (String started : startedIds) {
                traceService.finishTask(started, AgentTask.STATUS_FAILED, null,
                        "同组任务启动失败，未执行");
            }
            TaskSpec failedSpec = tasks.get(startedIds.size()).spec();
            Conflict conflict = new Conflict(failedSpec.receiver(), failedSpec.taskType(),
                    e.missingTaskIds(), "并行组启动被拒绝：前置任务未完成");
            traceService.recordConflict(ctx.getRunId(), ctx.getUserId(), failedSpec.receiver(), failedSpec.taskType(),
                    dependencyIds(failedSpec), conflictMessage(conflict), failedSpec.attempt(), failedSpec.replacesTaskId());
            emit(ctx, "conflict", conflictPayload(conflict));
            throw new TaskConflictException(conflict);
        }

        // 任务行全部就绪后再提交执行体：两行都已在 RUNNING，时间区间必然重叠
        List<Future<Receipt<?>>> futures = new ArrayList<>();
        for (int i = 0; i < tasks.size(); i++) {
            ParallelTask<?> task = tasks.get(i);
            TaskHandle handle = handles.get(i);
            if (handle.taskId() == null) {
                futures.add(null);
                continue;
            }
            emit(ctx, "delegate", delegatePayload(handle, task.spec()));
            futures.add(submitWorker(ctx, handle, task));
        }

        List<Receipt<?>> receipts = new ArrayList<>(tasks.size());
        RuntimeException failure = null;
        for (int i = 0; i < futures.size(); i++) {
            Future<Receipt<?>> future = futures.get(i);
            if (future == null) {
                // 该分支追踪不可用：串行执行，保持声明顺序
                receipts.add(dispatchSerialOnly(ctx, tasks.get(i)));
                continue;
            }
            try {
                receipts.add(future.get());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                failure = new IllegalStateException("并行任务被中断", e);
                break;
            } catch (ExecutionException e) {
                Throwable cause = e.getCause();
                failure = cause instanceof RuntimeException runtime
                        ? runtime : new IllegalStateException("并行任务执行失败", cause);
                break;
            }
        }
        if (failure != null) {
            // 取消尚未完成的分支；取消成功的行不可能再收尾，补记 FAILED（已结束的行保持原状态）
            for (int i = 0; i < futures.size(); i++) {
                Future<Receipt<?>> future = futures.get(i);
                if (future == null || future.isDone()) {
                    continue;
                }
                if (future.cancel(true)) {
                    traceService.finishTask(handles.get(i).taskId(), AgentTask.STATUS_FAILED, null,
                            "同组任务失败，已取消");
                }
            }
            throw failure;
        }
        return receipts;
    }

    /** 追踪不可用时的退化路径：直接执行，不落库不发事件 */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private Receipt<?> dispatchSerialOnly(AgentContext ctx, ParallelTask task) {
        Object value = task.body().get();
        return new Receipt<>(new TaskHandle(null, task.spec().receiver(), task.spec().taskType()), value);
    }

    // ==================== 执行与事件 ====================

    /** 在调用线程执行任务体：挂 taskId → 执行 → 落 SUCCEEDED/FAILED → 还原 taskId */
    private <T> Receipt<T> execute(AgentContext ctx, TaskHandle handle, Supplier<T> body,
                                   Function<T, String> summarizer) {
        String previous = ctx.getCurrentTaskId();
        ctx.setCurrentTaskId(handle.taskId());
        try {
            T value = body.get();
            traceService.finishTask(handle.taskId(), AgentTask.STATUS_SUCCEEDED, summarizer.apply(value), null);
            return new Receipt<>(handle, value);
        } catch (RuntimeException e) {
            traceService.finishTask(handle.taskId(), AgentTask.STATUS_FAILED, null, e.getMessage());
            throw e;
        } finally {
            ctx.setCurrentTaskId(previous);
        }
    }

    /** 提交一个并行任务体到 agent-dag 池（泛型擦除处集中一处，其余保持类型安全） */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private Future<Receipt<?>> submitWorker(AgentContext ctx, TaskHandle handle, ParallelTask<?> task) {
        ParallelTask raw = task;
        return pool.submit(() -> executeInWorker(ctx, handle, raw.body(), raw.summarizer()));
    }

    /** 在线程池里执行任务体：除了 taskId，还要按执行线程补 UserContext（LLM 配额依赖它） */
    private <T> Receipt<T> executeInWorker(AgentContext ctx, TaskHandle handle, Supplier<T> body,
                                           Function<T, String> summarizer) {
        String username = ctx.getUsername();
        if (username != null) {
            UserContext.set(username);
        }
        try {
            return execute(ctx, handle, body, summarizer);
        } finally {
            if (username != null) {
                UserContext.clear();
            }
        }
    }

    private void emit(AgentContext ctx, String event, Object payload) {
        try {
            ctx.emit(event, payload);
        } catch (RuntimeException e) {
            // 事件推送失败绝不影响任务执行
            log.debug("任务事件推送失败: event={} err={}", event, e.getMessage());
        }
    }

    private static List<String> dependencyIds(TaskSpec spec) {
        return spec.dependsOn().stream()
                .map(TaskHandle::taskId)
                .filter(Objects::nonNull)
                .toList();
    }

    private static java.util.Map<String, Object> delegatePayload(TaskHandle handle, TaskSpec spec) {
        java.util.Map<String, Object> payload = new java.util.LinkedHashMap<>();
        payload.put("taskId", handle.taskId());
        payload.put("receiver", handle.receiver());
        payload.put("taskType", handle.taskType());
        payload.put("dependsOn", dependencyIds(spec));
        payload.put("attempt", spec.attempt());
        return payload;
    }

    private static java.util.Map<String, Object> recoverPayload(TaskHandle handle, TaskSpec spec) {
        java.util.Map<String, Object> payload = new java.util.LinkedHashMap<>();
        payload.put("taskId", handle.taskId());
        payload.put("receiver", handle.receiver());
        payload.put("taskType", handle.taskType());
        payload.put("replacesTaskId", spec.replacesTaskId());
        payload.put("attempt", spec.attempt());
        payload.put("reason", spec.reason() == null ? "" : spec.reason());
        return payload;
    }

    private static java.util.Map<String, Object> conflictPayload(Conflict conflict) {
        java.util.Map<String, Object> payload = new java.util.LinkedHashMap<>();
        payload.put("receiver", conflict.receiver());
        payload.put("taskType", conflict.taskType());
        payload.put("missingDeps", conflict.missingDeps());
        payload.put("reason", conflict.reason());
        return payload;
    }

    private static String conflictMessage(Conflict conflict) {
        return conflict.reason() + "（缺失前置: " + String.join(",", conflict.missingDeps()) + "）";
    }

    @PreDestroy
    void shutdown() {
        pool.shutdownNow();
    }
}