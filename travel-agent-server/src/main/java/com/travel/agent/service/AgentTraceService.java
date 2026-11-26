package com.travel.agent.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.travel.agent.agent.AgentState;
import com.travel.agent.entity.AgentRun;
import com.travel.agent.entity.AgentRunStage;
import com.travel.agent.entity.AgentTask;
import com.travel.agent.entity.ToolCallTrace;
import com.travel.agent.exception.TaskDependencyException;
import com.travel.agent.repository.AgentRunEventRepository;
import com.travel.agent.repository.AgentRunRepository;
import com.travel.agent.repository.AgentRunSpendRepository;
import com.travel.agent.repository.AgentRunStageRepository;
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
    private static final int STAGE_MAX = 32;
    private static final int REQUEST_ID_MAX = 64;
    /** 阶段历史 seq 唯一键冲突时的重试次数（多实例并行推进可能同时算到同一个 max+1） */
    private static final int STAGE_SEQ_RETRIES = 5;
    /** JVM 内按 runId 分段加锁，单实例内把同一 run 的阶段历史追加串行化（减少唯一键冲突重试） */
    private static final int STAGE_LOCK_STRIPES = 64;

    private final AgentRunRepository runRepository;
    private final AgentTaskRepository taskRepository;
    private final ToolCallTraceRepository traceRepository;
    private final AgentRunStageRepository stageRepository;
    private final AgentRunEventRepository eventRepository;
    private final AgentRunSpendRepository spendRepository;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate newTx;
    private final Object[] stageLocks = new Object[STAGE_LOCK_STRIPES];

    public AgentTraceService(AgentRunRepository runRepository, AgentTaskRepository taskRepository,
                             ToolCallTraceRepository traceRepository, AgentRunStageRepository stageRepository,
                             AgentRunEventRepository eventRepository, AgentRunSpendRepository spendRepository,
                             ObjectMapper objectMapper, PlatformTransactionManager transactionManager) {
        this.runRepository = runRepository;
        this.taskRepository = taskRepository;
        this.traceRepository = traceRepository;
        this.stageRepository = stageRepository;
        this.eventRepository = eventRepository;
        this.spendRepository = spendRepository;
        this.objectMapper = objectMapper;
        this.newTx = new TransactionTemplate(transactionManager);
        this.newTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        for (int i = 0; i < STAGE_LOCK_STRIPES; i++) {
            stageLocks[i] = new Object();
        }
    }

    // ==================== run 生命周期 ====================

    /** 开始一次运行（旧签名，不带幂等标识；行为与升级前一致） */
    public AgentRun startRun(Long userId, Long tripId, String message) {
        return startRun(userId, tripId, message, null);
    }

    /**
     * 开始一次运行：失败返回 null（调用方拿到 null 时后续追踪静默跳过）。
     *
     * <p>P0 起 run 一创建就带初始状态快照（stage=RECEIVE + heartbeat 时间）与
     * {@code requestId}：SSE 断线重连可按 (userId, requestId) 找回原 run；
     * 进程崩溃后恢复任务也能按心跳判断这轮是否已经死掉。
     */
    public AgentRun startRun(Long userId, Long tripId, String message, String requestId) {
        try {
            LocalDateTime now = LocalDateTime.now();
            return inNewTx(() -> runRepository.save(AgentRun.builder()
                    .runId(UUID.randomUUID().toString())
                    .userId(userId)
                    .tripId(tripId)
                    .message(truncate(message, MESSAGE_MAX))
                    .requestId(truncate(requestId, REQUEST_ID_MAX))
                    .status(AgentRun.STATUS_RUNNING)
                    .stage(AgentState.RECEIVE.name())
                    .heartbeatAt(now)
                    .startedAt(now)
                    .build()));
        } catch (RuntimeException e) {
            log.debug("记录 Agent 运行失败: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 推进运行阶段（P0）：更新 agent_run.stage / heartbeat_at 快照，并追加一条迁移历史。
     *
     * <p>两个写入各自独立提交：快照先落（恢复任务依赖它），历史写入失败只丢证据。
     * 历史 seq 由「当前 max+1」计算；并行阶段推进撞唯一键时重试，保证同一 run 内 seq 唯一。
     * 失败全部静默 —— 追踪永远不能反噬主流程。
     */
    public void enterStage(String runId, String stage) {
        if (runId == null || stage == null || stage.isBlank()) {
            return;
        }
        String normalized = truncate(stage, STAGE_MAX);
        boolean updated = updateRunStage(runId, normalized);
        if (!updated) {
            return;
        }
        appendStageHistory(runId, normalized, null);
    }

    /** 更新运行阶段快照；run 不存在或写库失败返回 false（由调用方决定是否继续写历史） */
    private boolean updateRunStage(String runId, String stage) {
        try {
            return inNewTx(() -> runRepository.findByRunId(runId)
                    .map(run -> {
                        run.setStage(stage);
                        run.setHeartbeatAt(LocalDateTime.now());
                        runRepository.save(run);
                        return true;
                    })
                    .orElse(false));
        } catch (RuntimeException e) {
            log.debug("更新 Agent 运行阶段失败: runId={} stage={} err={}", runId, stage, e.getMessage());
            return false;
        }
    }

    /**
     * 追加一条阶段迁移历史，seq = 当前 max + 1。
     * 单实例内按 runId 分段加锁、跨实例靠唯一键重试（最多 {@link #STAGE_SEQ_RETRIES} 次），
     * 保证同一 run 内 seq 唯一；失败静默。
     */
    private void appendStageHistory(String runId, String stage, String detail) {
        synchronized (stageLock(runId)) {
            for (int attempt = 1; attempt <= STAGE_SEQ_RETRIES; attempt++) {
                try {
                    inNewTx(() -> {
                        int nextSeq = stageRepository.findTopByRunIdOrderBySeqNoDesc(runId)
                                .map(last -> last.getSeqNo() + 1)
                                .orElse(1);
                        stageRepository.save(AgentRunStage.builder()
                                .runId(runId)
                                .seqNo(nextSeq)
                                .stage(stage)
                                .enteredAt(LocalDateTime.now())
                                .detail(truncate(detail, 500))
                                .build());
                        return null;
                    });
                    return;
                } catch (RuntimeException e) {
                    if (attempt == STAGE_SEQ_RETRIES) {
                        log.debug("记录 Agent 阶段历史失败: runId={} stage={} err={}", runId, stage, e.getMessage());
                    }
                }
            }
        }
    }

    /** 阶段历史锁：按 runId 哈希取一条固定分段锁（不保留 per-run 对象，避免长跑实例的内存增长） */
    private Object stageLock(String runId) {
        return stageLocks[Math.floorMod(runId.hashCode(), STAGE_LOCK_STRIPES)];
    }

    /** 结束一次运行（补记意图与最终状态，并写入终态 stage；失败静默） */
    public void finishRun(String runId, String intent, String status, String error) {
        if (runId == null) {
            return;
        }
        String terminalStage = terminalStage(status);
        boolean updated = false;
        try {
            updated = inNewTx(() -> runRepository.findByRunId(runId)
                    .map(run -> {
                        run.setIntent(intent);
                        run.setStatus(status);
                        run.setError(truncate(error, ERROR_MAX));
                        run.setStage(terminalStage);
                        run.setHeartbeatAt(LocalDateTime.now());
                        run.setEndedAt(LocalDateTime.now());
                        runRepository.save(run);
                        return true;
                    })
                    .orElse(false));
        } catch (RuntimeException e) {
            log.debug("结束 Agent 运行记录失败: runId={} err={}", runId, e.getMessage());
        }
        if (updated) {
            appendStageHistory(runId, terminalStage, null);
        }
    }

    /** run 状态 → 终态 stage：成功/部分完成都算 DONE（是否降级由 status 表达） */
    private static String terminalStage(String status) {
        if (AgentRun.STATUS_SUCCEEDED.equals(status) || AgentRun.STATUS_PARTIAL.equals(status)) {
            return AgentRun.STAGE_DONE;
        }
        if (AgentRun.STATUS_CANCELLED.equals(status)) {
            return AgentRun.STAGE_CANCELLED;
        }
        if (AgentRun.STATUS_ABANDONED.equals(status)) {
            return AgentRun.STAGE_ABANDONED;
        }
        return AgentRun.STAGE_FAILED;
    }

    // ==================== 阶段任务 ====================

    /**
     * 本轮失败/降级的阶段任务数（P0-07 聚合总状态用）。
     *
     * <p>工具/阶段失败后被 {@code recover} 重派成功，任务行仍然是 FAILED：
     * 那属于「已降级完成」，不能与完全成功共用同一个绿色标签。
     * 追踪不可用（runId 为空或查询异常）时返回 0 —— 缺追踪是缺证据，不是失败。
     */
    public int failedTaskCount(String runId) {
        if (runId == null) {
            return 0;
        }
        try {
            return (int) taskRepository.findByRunIdOrderByIdAsc(runId).stream()
                    .filter(task -> com.travel.agent.entity.AgentTask.STATUS_FAILED.equals(task.getStatus()))
                    .count();
        } catch (RuntimeException e) {
            log.debug("统计失败任务数出错: runId={} err={}", runId, e.getMessage());
            return 0;
        }
    }

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

    /**
     * 按 (userId, requestId) 找最近一次运行（P0）：SSE 幂等/重连用。
     * 同一 requestId 只会属于同一用户，归属校验天然成立；没有记录时返回空。
     */
    public Optional<AgentRun> findLatestRun(Long userId, String requestId) {
        if (userId == null || requestId == null || requestId.isBlank()) {
            return Optional.empty();
        }
        return runRepository.findFirstByUserIdAndRequestIdOrderByIdDesc(userId, requestId);
    }

    /** run 的阶段迁移历史（按进入顺序） */
    public List<AgentRunStage> stagesOf(String runId) {
        return runId == null ? List.of() : stageRepository.findByRunIdOrderBySeqNoAsc(runId);
    }

    public List<AgentTask> tasksOf(String runId) {
        return taskRepository.findByRunIdOrderByIdAsc(runId);
    }

    public List<ToolCallTrace> toolCallsOf(String runId) {
        return traceRepository.findByRunIdOrderByIdAsc(runId);
    }

    // ==================== 保留期清理 ====================

    /**
     * 清理超过保留期的运行 / 任务 / 工具追踪 / 事件 / 阶段历史。
     * 整批在一个独立事务里执行；失败向上抛（由 {@code AgentTraceRetentionJob} 记账，
     * 下一轮重试，清理本身幂等）。
     */
    public void purgeExpired() {
        inNewTx(() -> {
            LocalDateTime threshold = LocalDateTime.now().minusDays(RETENTION_DAYS);
            // 子表先删：事件/阶段历史只有 run_id，没有外键级联，必须与 run 同一保留期主动清理
            long events = eventRepository.deleteByCreatedAtBefore(threshold);
            long stages = stageRepository.deleteByEnteredAtBefore(threshold);
            long spends = spendRepository.deleteByCreatedAtBefore(threshold);
            long runs = runRepository.deleteByStartedAtBefore(threshold);
            long tasks = taskRepository.deleteByStartedAtBefore(threshold);
            long traces = traceRepository.deleteByStartedAtBefore(threshold);
            if (runs + tasks + traces + events + stages + spends > 0) {
                log.debug("清理过期 Agent 追踪: runs={} tasks={} traces={} events={} stages={} spends={}",
                        runs, tasks, traces, events, stages, spends);
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
