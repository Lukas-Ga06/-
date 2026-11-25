package com.travel.agent;

import com.travel.agent.entity.AgentRun;
import com.travel.agent.entity.AgentRunStage;
import com.travel.agent.repository.AgentRunRepository;
import com.travel.agent.service.AgentRunRecoveryJob;
import com.travel.agent.service.AgentTraceService;
import com.travel.agent.support.IntegrationTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * P0：崩溃恢复（AgentRunRecoveryJob）。
 *
 * <p>进程崩溃/重启后内存中的执行体已不存在，RUNNING 行会永远挂着。恢复任务按心跳阈值
 * （心跳为空用开始时间）把它们标记为 ABANDONED，补终态迁移历史并让续传端点合成 complete；
 * 心跳新鲜的运行必须原样保留。
 */
class AgentRunRecoveryJobTest extends IntegrationTestSupport {

    @Autowired
    private AgentRunRecoveryJob recoveryJob;

    @Autowired
    private AgentTraceService traceService;

    @Autowired
    private AgentRunRepository runRepository;

    private AgentRun start(Account account, String label) {
        return traceService.startRun(account.id(), null, label, "req-" + UUID.randomUUID());
    }

    private AgentRun reload(AgentRun run) {
        return runRepository.findByRunId(run.getRunId()).orElseThrow();
    }

    @Test
    @DisplayName("心跳超时的 RUNNING 被判为 ABANDONED（endedAt/error/终态历史），新鲜运行不动")
    void staleRunningRunIsAbandonedWhileFreshRunIsUntouched() throws Exception {
        Account account = registerNew();
        AgentRun stale = start(account, "陈旧运行");
        stale.setStartedAt(LocalDateTime.now().minusMinutes(30));
        stale.setHeartbeatAt(LocalDateTime.now().minusMinutes(11));
        runRepository.save(stale);
        AgentRun fresh = start(account, "新鲜运行");

        int recovered = recoveryJob.recoverOnce();

        assertThat(recovered).isGreaterThanOrEqualTo(1);

        AgentRun abandoned = reload(stale);
        assertThat(abandoned.getStatus()).isEqualTo(AgentRun.STATUS_ABANDONED);
        assertThat(abandoned.getStage()).isEqualTo(AgentRun.STAGE_ABANDONED);
        assertThat(abandoned.getEndedAt()).isNotNull();
        assertThat(abandoned.getError()).contains("心跳超时").contains("进程重启中断");
        List<AgentRunStage> histories = traceService.stagesOf(stale.getRunId());
        assertThat(histories).isNotEmpty();
        AgentRunStage last = histories.get(histories.size() - 1);
        assertThat(last.getStage()).isEqualTo(AgentRun.STAGE_ABANDONED);
        assertThat(histories).extracting(AgentRunStage::getSeqNo).doesNotHaveDuplicates().isSorted();

        AgentRun untouched = reload(fresh);
        assertThat(untouched.getStatus()).isEqualTo(AgentRun.STATUS_RUNNING);
        assertThat(untouched.getStage()).isEqualTo("RECEIVE");
        assertThat(untouched.getEndedAt()).isNull();

        // 幂等：已处理的行不会再次命中（不重复补终态历史）
        int historiesBefore = traceService.stagesOf(stale.getRunId()).size();
        recoveryJob.recoverOnce();
        assertThat(traceService.stagesOf(stale.getRunId())).hasSize(historiesBefore);
        assertThat(reload(stale).getStatus()).isEqualTo(AgentRun.STATUS_ABANDONED);
    }

    @Test
    @DisplayName("从未写过心跳的运行按 started_at 判断超时")
    void runWithoutHeartbeatFallsBackToStartedAt() throws Exception {
        Account account = registerNew();
        AgentRun noHeartbeat = start(account, "没有心跳的运行");
        noHeartbeat.setStartedAt(LocalDateTime.now().minusMinutes(20));
        noHeartbeat.setHeartbeatAt(null);
        runRepository.save(noHeartbeat);

        recoveryJob.recoverOnce();

        AgentRun abandoned = reload(noHeartbeat);
        assertThat(abandoned.getStatus()).isEqualTo(AgentRun.STATUS_ABANDONED);
        assertThat(abandoned.getEndedAt()).isNotNull();
    }

    @Test
    @DisplayName("自定义阈值：只回收早于阈值的运行")
    void customThresholdOnlyRecoversOlderRuns() throws Exception {
        Account account = registerNew();
        AgentRun twoMinutesOld = start(account, "两分钟前");
        twoMinutesOld.setStartedAt(LocalDateTime.now().minusMinutes(2));
        twoMinutesOld.setHeartbeatAt(LocalDateTime.now().minusMinutes(2));
        runRepository.save(twoMinutesOld);
        AgentRun thirtySecondsOld = start(account, "三十秒前");
        thirtySecondsOld.setStartedAt(LocalDateTime.now().minusSeconds(30));
        thirtySecondsOld.setHeartbeatAt(LocalDateTime.now().minusSeconds(30));
        runRepository.save(thirtySecondsOld);

        // 阈值 60 秒：两分钟前的中断，三十秒前的仍在阈值内
        recoveryJob.recoverOnce(60_000L);

        assertThat(reload(twoMinutesOld).getStatus()).isEqualTo(AgentRun.STATUS_ABANDONED);
        assertThat(reload(thirtySecondsOld).getStatus()).isEqualTo(AgentRun.STATUS_RUNNING);
    }
}
