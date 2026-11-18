package com.travel.agent.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.travel.agent.entity.AgentRun;
import com.travel.agent.entity.AgentTask;
import com.travel.agent.entity.ToolCallTrace;
import com.travel.agent.exception.TaskDependencyException;
import com.travel.agent.repository.AgentRunRepository;
import com.travel.agent.repository.AgentTaskRepository;
import com.travel.agent.repository.ToolCallTraceRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Agent 运行 / 任务 / 工具调用的持久化追踪（V2.2 FR-02 / FR-04 / FR-10）。
 *
 * <p>设计约束：
 * <ul>
 *   <li><b>追踪绝不反噬主流程</b>：所有写入都包在 {@code try { inNewTx(...) } catch}
 *       里——try/catch 必须在<b>事务边界之外</b>（用 {@link TransactionTemplate} 而不是
 *       方法级 @Transactional 的原因：方法内部 catch 住内层事务异常时，外层代理提交
 *       一个已被标记 rollback-only 的事务会抛 UnexpectedRollbackException，反而把
 *       追踪失败放大成业务故障）；</li>
 *   <li><b>独立事务</b>：REQUIRES_NEW 让「已经开始的 run」「已经结束的任务」立即提交，
 *       主流程半途失败（异常 / 超时 / 断连取消）时已产生的痕迹仍然可查；</li>
 *   <li><b>只存摘要</b>：消息、参数、结果都截断存储（含省略号总长不超过列宽）；
 *       工具参数本身只有业务字段（城市/日期），密钥从不进入工具参数，摘要里也不会有。</li>
 * </ul>
 */
@Service
@Slf4j
public class AgentTraceService {

    /** 追踪保留天数（默认 14 天，每天 04:40 清理） */
    public static final int RETENTION_DAYS = 14;

    private static final int MESSAGE_MAX = 500;
    private static final int DIGEST_MAX = 900;
    private static final int ERROR_MAX = 500;
    private static final int SUMMARY_MAX = 1000;
    private static final int ERROR_CODE_MAX = 200;

    private final AgentRunRepository runRepository;
    private final AgentTaskRepository taskRepository;
    private final ToolCallTraceRepository traceRepository;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate newTx;

    public AgentTraceService(AgentRunRepository runRepository, AgentTaskRepository taskRepository,
                             ToolCallTraceRepository traceRepository, ObjectMapper objectMapper,
                             PlatformTransactionManager transactionManager) {
        this.runRepository = runRepository;
        this.taskRepository = taskRepository;
        this.traceRepository = traceRepository;
        this.objectMapper = objectMapper;
        this.newTx = new TransactionTemplate(transactionManager);
        this.newTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    // ==================== run 生命周期 ====================

    /** 开始一次运行：失败返回 null（调用方拿到 null 时后续追踪静默跳过） */
    public AgentRun startRun(Long userId, Long tripId, String message) {
        try {
            return inNewTx(() -> runRepository.save(AgentRun.builder()
                    .runId(UUID.randomUUID().toString())
                    .userId(userId)
                    .tripId(tripId)
                    .message(truncate(message, MESSAGE_MAX))
                    .status(AgentRun.STATUS_RUNNING)
                    .startedAt(LocalDateTime.now())
                    .build()));
        } catch (RuntimeException e) {
            log.debug("记录 Agent 运行失败: {}", e.getMessage());
            return null;
        }
    }

    /** 结束一次运行（补记意图与最终状态；失败静默） */
    public void finishRun(String runId, String intent, String status, String error) {
        if (runId == null) {
            return;
        }
        try {
            inNewTx(() -> {
                runRepository.findByRunId(runId).ifPresent(run -> {
                    run.setIntent(intent);
                    run.setStatus(status);
                    run.setError(truncate(error, ERROR_MAX));
                    run.setEndedAt(LocalDateTime.now());
                    runRepository.save(run);
                });
                return null;
            });
        } catch (RuntimeException e) {
            log.debug("结束 Agent 运行记录失败: runId={} err={}", runId, e.getMessage());
        }
    }

    // ==================== 阶段任务 ====================

    /**
     * 以独立任务包裹一段 Agent 阶段逻辑：开始时落一条 RUNNING 任务，正常结束置
     * SUCCEEDED 并写入摘要，异常置 FAILED 并记录原因后原样抛出。
     *
     * <p>runId 为空（run 记录失败 / 不需要追踪）时直接执行 body，行为不受影响。
     * body 本身<b>不在</b>事务里运行（只有任务起止两笔写入各自独立提交），
     * 调用方自身的 @Transactional 语义（如 ExecutorAgent.commit）不受影响。
     */
    public <T> T task(String runId, Long userId, String receiver, String taskType,
                      Supplier<T> body, Function<T, String> summarizer) {
        if (runId == null) {
            return body.get();
        }
        String taskId = startTask(runId, userId, receiver, taskType);
        try {
            T result = body.get();
            finishTask(taskId, AgentTask.STATUS_SUCCEEDED, summarizer.apply(result), null);
            return result;
        } catch (RuntimeException e) {
            finishTask(taskId, AgentTask.STATUS_FAILED, null, e.getMessage());
            throw e;
        }
    }

    /** 开始一个任务，返回 taskId（失败返回 null，后续 finish 静默跳过） */
    public String startTask(String runId, Long userId, String receiver, String taskType) {
        return startTask(runId, userId, receiver, taskType, List.of(), null, 0);
    }

    /**
     * 开始一个任务图节点（第十五批 FR-02/FR-04）：
     * 前置任务必须全部 SUCCEEDED 才允许启动，否则抛 {@link TaskDependencyException} 拒绝启动。
     *
     * <p>依赖边 / attempt / replacesTaskId 随任务一起落库，构成「任务图可查询」的数据面；
     * 拒绝启动时不写 RUNNING 行（只有一条由 Coordinator 记的 CONFLICT 行）。
     * runId 为空（追踪不可用）时退化为 null，调用方直接执行 body，行为与升级前一致。
     */
    public String startTask(String runId, Long userId, String receiver, String taskType,
                            List<String> dependsOn, String replacesTaskId, int attempt) {
        if (runId == null) {
            return null;
        }
        List<String> missing = unsatisfiedDependencies(dependsOn);
        if (!missing.isEmpty()) {
            throw new TaskDependencyException(receiver, taskType, missing);
        }
        try {
            return inNewTx(() -> taskRepository.save(AgentTask.builder()
                    .taskId(UUID.randomUUID().toString())
                    .runId(runId)
                    .userId(userId)
                    .receiver(receiver)
                    .taskType(taskType)
                    .status(AgentTask.STATUS_RUNNING)
                    .dependsOn(AgentTask.joinDependencies(dependsOn))
                    .attempt(attempt)
                    .replacesTaskId(replacesTaskId)
                    .startedAt(LocalDateTime.now())
                    .build()).getTaskId());
        } catch (RuntimeException e) {
            log.debug("记录 Agent 任务失败: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 记录一个冲突任务（第十五批）：前置未完成 / 任务被拒绝启动。
     * 该行只作证据，没有执行体；结束后即可查询，不需要 finish。
     */
    public String recordConflict(String runId, Long userId, String receiver, String taskType,
                                 List<String> dependsOn, String reason, int attempt, String replacesTaskId) {
        if (runId == null) {
            return null;
        }
        try {
            LocalDateTime now = LocalDateTime.now();
            return inNewTx(() -> taskRepository.save(AgentTask.builder()
                    .taskId(UUID.randomUUID().toString())
                    .runId(runId)
                    .userId(userId)
                    .receiver(receiver)
                    .taskType(taskType)
                    .status(AgentTask.STATUS_CONFLICT)
                    .dependsOn(AgentTask.joinDependencies(dependsOn))
                    .attempt(attempt)
                    .replacesTaskId(replacesTaskId)
                    .summary(truncate(reason, SUMMARY_MAX))
                    .startedAt(now)
                    .endedAt(now)
                    .build()).getTaskId());
        } catch (RuntimeException e) {
            log.debug("记录任务冲突失败: receiver={} type={} err={}", receiver, taskType, e.getMessage());
            return null;
        }
    }

    /** 依赖校验：返回不存在或未成功的前置任务 id（顺序与入参一致） */
    private List<String> unsatisfiedDependencies(List<String> dependsOn) {
        if (dependsOn == null || dependsOn.isEmpty()) {
            return List.of();
        }
        List<String> missing = new java.util.ArrayList<>();
        for (String taskId : dependsOn) {
            boolean ok = taskId != null && taskRepository.findByTaskId(taskId)
                    .map(task -> AgentTask.STATUS_SUCCEEDED.equals(task.getStatus()))
                    .orElse(false);
            if (!ok) {
                missing.add(taskId);
            }
        }
        return missing;
    }

    /** 结束任务（成功/失败；失败静默） */
    public void finishTask(String taskId, String status, String summary, String error) {
        if (taskId == null) {
            return;
        }
        try {
            inNewTx(() -> {
                taskRepository.findByTaskId(taskId).ifPresent(task -> {
                    task.setStatus(status);
                    task.setSummary(truncate(summary, SUMMARY_MAX));
                    task.setError(truncate(error, ERROR_MAX));
                    task.setEndedAt(LocalDateTime.now());
                    taskRepository.save(task);
                });
                return null;
            });
        } catch (RuntimeException e) {
            log.debug("结束 Agent 任务记录失败: taskId={} err={}", taskId, e.getMessage());
        }
    }

    // ==================== 工具调用追踪 ====================

    /** 记录一次工具调用（参数/结果摘要 + 耗时 + 成败；失败静默） */
    public void recordToolCall(String runId, String taskId, Long userId, String toolName,
                               Map<String, Object> args, Object result, boolean success,
                               String errorCode, long latencyMs) {
        try {
            inNewTx(() -> traceRepository.save(ToolCallTrace.builder()
                    .runId(runId)
                    .taskId(taskId)
                    .userId(userId)
                    .toolName(toolName)
                    .argsDigest(digest(args))
                    .resultDigest(success ? digest(result) : null)
                    .success(success)
                    .errorCode(truncate(errorCode, ERROR_CODE_MAX))
                    .latencyMs(latencyMs)
                    .startedAt(LocalDateTime.now())
                    .build()));
        } catch (RuntimeException e) {
            log.debug("记录工具调用失败: tool={} err={}", toolName, e.getMessage());
        }
    }

    // ==================== 查询（API 用） ====================

    /** 用户最近的运行列表（新→旧） */
    public List<AgentRun> recentRuns(Long userId, int limit) {
        int capped = Math.min(Math.max(limit, 1), 50);
        return runRepository.findByUserIdOrderByIdDesc(userId, PageRequest.of(0, capped));
    }

    /** run 明细（含任务链与工具调用），归属校验由调用方完成 */
    public Optional<AgentRun> findRun(String runId, Long userId) {
        if (runId == null || userId == null) {
            return Optional.empty();
        }
        return runRepository.findByRunIdAndUserId(runId, userId);
    }

    public List<AgentTask> tasksOf(String runId) {
        return taskRepository.findByRunIdOrderByIdAsc(runId);
    }

    public List<ToolCallTrace> toolCallsOf(String runId) {
        return traceRepository.findByRunIdOrderByIdAsc(runId);
    }

    // ==================== 保留期清理 ====================

    /**
     * 清理超过保留期的运行 / 任务 / 工具追踪。
     * 整批在一个独立事务里执行；失败向上抛（由 {@code AgentTraceRetentionJob} 记账，
     * 下一轮重试，清理本身幂等）。
     */
    public void purgeExpired() {
        inNewTx(() -> {
            LocalDateTime threshold = LocalDateTime.now().minusDays(RETENTION_DAYS);
            long runs = runRepository.deleteByStartedAtBefore(threshold);
            long tasks = taskRepository.deleteByStartedAtBefore(threshold);
            long traces = traceRepository.deleteByStartedAtBefore(threshold);
            if (runs + tasks + traces > 0) {
                log.debug("清理过期 Agent 追踪: runs={} tasks={} traces={}", runs, tasks, traces);
            }
            return null;
        });
    }

    // ==================== 内部辅助 ====================

    /** 在独立新事务里执行写入；异常直接向上抛（由各公开方法的 catch 统一吞掉） */
    private <T> T inNewTx(Supplier<T> body) {
        return newTx.execute(status -> body.get());
    }

    private String digest(Object value) {
        if (value == null) {
            return null;
        }
        String text;
        if (value instanceof String s && !s.isBlank()) {
            text = s;
        } else {
            try {
                text = objectMapper.writeValueAsString(value);
            } catch (Exception e) {
                text = String.valueOf(value);
            }
        }
        return truncate(text, DIGEST_MAX);
    }

    /** 截断到不超过 max（含省略号占位），保证任何截断结果都能放进对应宽度的列 */
    private static String truncate(String text, int max) {
        if (text == null) {
            return null;
        }
        if (text.length() <= max) {
            return text;
        }
        return text.substring(0, Math.max(max - 1, 1)) + "…";
    }
}
