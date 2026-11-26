package com.travel.agent.service;

import com.travel.agent.entity.AgentRun;
import com.travel.agent.repository.AgentRunRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 崩溃恢复（P0）：把「进程已经死掉、但状态还挂在 RUNNING」的运行标记为 ABANDONED。
 *
 * <p>为什么需要：SSE 断线可以续传，但进程崩溃/重启后内存里的执行体已不存在，
 * 只有 agent_run 表里的 RUNNING 行还留着。没有恢复任务，这次运行会永远显示「运行中」，
 * 前端会一直等一个不会再有结果的流。按心跳超时判定：
 * {@code heartbeat_at}（为空用 {@code started_at}）早于阈值 → 运行已中断。
 *
 * <p>触发时机：应用启动（{@link ApplicationReadyEvent}）跑一次，之后默认每 5 分钟一次，
 * 间隔与阈值都可用配置覆盖（{@code travel.agent.run-recovery.*}）。恢复只改状态并补一条
 * 终态迁移历史，日志只带数量/runId，不输出用户消息内容。
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class AgentRunRecoveryJob {

    /** 默认心跳阈值：10 分钟没有阶段推进即判为中断（LLM/工具单步通常远短于此） */
    public static final long DEFAULT_STALE_AFTER_MS = 10L * 60L * 1000L;

    /** 单轮最多处理多少条陈旧运行：避免一次启动把巨大积压全部加载进内存 */
    private static final int BATCH_SIZE = 500;

    /** 写进 agent_run.error 的固定说明：同时点明「心跳超时」与「进程重启中断」两种成因 */
    static final String ABANDONED_ERROR =
            "心跳超时/进程重启中断：服务恢复时该运行已超过心跳阈值，执行体已不存在，结果未知，请重试。";

    private final AgentRunRepository runRepository;
    private final AgentTraceService traceService;
    /** 阶段三可靠性指标：ABANDONED 运行数（结果未知的运行必须可计数，不能只留在日志） */
    private final com.travel.agent.observability.TravelMetrics travelMetrics;

    /** 心跳超时阈值（毫秒），可用 {@code travel.agent.run-recovery.stale-after-ms} 覆盖 */
    @Value("${travel.agent.run-recovery.stale-after-ms:600000}")
    private long staleAfterMs = DEFAULT_STALE_AFTER_MS;

    /** 应用启动即恢复一次：重启前遗留的 RUNNING 不应等到下一个调度周期 */
    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        runSafely();
    }

    /**
     * 周期恢复。首次延迟默认与周期相同（启动场景已由 {@link #onApplicationReady()} 覆盖，
     * 无需启动瞬间重复跑）；固定延迟避免上一轮耗时把周期挤成背靠背执行。
     */
    @Scheduled(fixedDelayString = "${travel.agent.run-recovery.interval-ms:300000}",
            initialDelayString = "${travel.agent.run-recovery.initial-delay-ms:300000}")
    public void scheduledRecovery() {
        runSafely();
    }

    /**
     * 恢复失败只记账、不抛出：启动事件里的异常会让整个应用启动失败，
     * 调度线程里的异常也会掩盖下一轮重试。恢复是幂等的，下一轮自然重试。
     */
    private void runSafely() {
        try {
            recoverOnce();
        } catch (RuntimeException e) {
            log.warn("恢复陈旧 Agent 运行失败，等待下一次触发重试: {}", e.toString());
        }
    }

    /** 用配置的阈值恢复一次（测试/运维可直接调用） */
    public synchronized int recoverOnce() {
        return recoverOnce(staleAfterMs);
    }

    /**
     * 用指定阈值恢复一次：把心跳超时的 RUNNING 标记为 ABANDONED，返回实际回收条数。
     *
     * <p>幂等：只查询 RUNNING，已处理的行下一轮不会再次命中；并发触发（启动 + 调度）
     * 由 {@code synchronized} 串行化，最多补出一条终态迁移历史。
     */
    public int recoverOnce(long staleAfterMs) {
        long thresholdMs = Math.max(staleAfterMs, 0L);
        LocalDateTime threshold = LocalDateTime.now().minus(Duration.ofMillis(thresholdMs));
        List<AgentRun> stale = new ArrayList<>();
        stale.addAll(runRepository.findByStatusAndHeartbeatAtBefore(
                AgentRun.STATUS_RUNNING, threshold, PageRequest.of(0, BATCH_SIZE)));
        stale.addAll(runRepository.findByStatusAndHeartbeatAtIsNullAndStartedAtBefore(
                AgentRun.STATUS_RUNNING, threshold, PageRequest.of(0, BATCH_SIZE)));

        int recovered = 0;
        for (AgentRun run : stale) {
            try {
                LocalDateTime now = LocalDateTime.now();
                run.setStatus(AgentRun.STATUS_ABANDONED);
                run.setStage(AgentRun.STAGE_ABANDONED);
                run.setHeartbeatAt(now);
                run.setEndedAt(now);
                run.setError(ABANDONED_ERROR);
                runRepository.save(run);
                // 终态迁移历史：与运行中阶段共用同一个 seq 序列，seq 唯一
                traceService.enterStage(run.getRunId(), AgentRun.STAGE_ABANDONED);
                recovered++;
            } catch (RuntimeException e) {
                log.warn("标记陈旧 Agent 运行失败: runId={} err={}", run.getRunId(), e.getMessage());
            }
        }
        if (recovered > 0) {
            travelMetrics.agentRunAbandoned(recovered);
            log.info("回收心跳超时的 Agent 运行: count={} threshold={}", recovered, threshold);
        }
        return recovered;
    }
}
