package com.travel.agent;

import com.travel.agent.entity.AgentRun;
import com.travel.agent.entity.AgentRunStage;
import com.travel.agent.service.AgentTraceService;
import com.travel.agent.support.IntegrationTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * P0：运行状态机落库（agent_run.stage / heartbeat_at / agent_run_stage）。
 *
 * <p>覆盖进入阶段时同时写快照与迁移历史、seq 唯一且单调、收尾写终态 stage，
 * 以及 run 不存在时不产生孤儿历史。
 */
class AgentRunStageTest extends IntegrationTestSupport {

    @Autowired
    private AgentTraceService traceService;

    private String newRun(Account account) {
        return traceService.startRun(account.id(), null, "阶段测试", "req-" + UUID.randomUUID()).getRunId();
    }

    @Test
    @DisplayName("enterStage 同时更新 stage/heartbeat 并追加唯一 seq 的迁移历史；finishRun 写终态 stage")
    void enterStageWritesSnapshotAndHistory() throws Exception {
        Account account = registerNew();
        String runId = newRun(account);

        AgentRun initial = traceService.findRun(runId, account.id()).orElseThrow();
        assertThat(initial.getStage()).isEqualTo("RECEIVE");
        assertThat(initial.getHeartbeatAt()).isNotNull();

        traceService.enterStage(runId, "UNDERSTAND");
        traceService.enterStage(runId, "PLAN");
        traceService.enterStage(runId, "PLAN");

        AgentRun afterTransitions = traceService.findRun(runId, account.id()).orElseThrow();
        assertThat(afterTransitions.getStage()).isEqualTo("PLAN");
        assertThat(afterTransitions.getHeartbeatAt()).isNotNull();
        assertThat(afterTransitions.getStatus()).isEqualTo(AgentRun.STATUS_RUNNING);

        List<AgentRunStage> stages = traceService.stagesOf(runId);
        assertThat(stages).extracting(AgentRunStage::getSeqNo).containsExactly(1, 2, 3);
        assertThat(stages).extracting(AgentRunStage::getStage)
                .containsExactly("UNDERSTAND", "PLAN", "PLAN");

        traceService.finishRun(runId, "PLAN_TRIP", AgentRun.STATUS_SUCCEEDED, null);

        AgentRun finished = traceService.findRun(runId, account.id()).orElseThrow();
        assertThat(finished.getStage()).isEqualTo(AgentRun.STAGE_DONE);
        assertThat(finished.getStatus()).isEqualTo(AgentRun.STATUS_SUCCEEDED);
        assertThat(finished.getEndedAt()).isNotNull();
        assertThat(traceService.stagesOf(runId))
                .extracting(AgentRunStage::getSeqNo).containsExactly(1, 2, 3, 4);
        assertThat(traceService.stagesOf(runId).get(3).getStage()).isEqualTo(AgentRun.STAGE_DONE);
    }

    @Test
    @DisplayName("失败/取消的 run 写对应终态 stage（FAILED / CANCELLED）")
    void terminalStageReflectsStatus() throws Exception {
        Account account = registerNew();
        String failed = newRun(account);
        String cancelled = newRun(account);

        traceService.finishRun(failed, "ERROR", AgentRun.STATUS_FAILED, "boom");
        traceService.finishRun(cancelled, null, AgentRun.STATUS_CANCELLED, "客户端断开");

        assertThat(traceService.findRun(failed, account.id()).orElseThrow().getStage())
                .isEqualTo(AgentRun.STAGE_FAILED);
        assertThat(traceService.findRun(cancelled, account.id()).orElseThrow().getStage())
                .isEqualTo(AgentRun.STAGE_CANCELLED);
    }

    @Test
    @DisplayName("run 不存在时 enterStage 不写孤儿历史")
    void enterStageOnMissingRunWritesNothing() {
        traceService.enterStage("run-does-not-exist", "PLAN");
        assertThat(traceService.stagesOf("run-does-not-exist")).isEmpty();
    }

    @Test
    @DisplayName("并发进入阶段时 seq 保持唯一（唯一键 + 重试）")
    void concurrentStageTransitionsKeepSeqUnique() throws Exception {
        Account account = registerNew();
        String runId = newRun(account);
        traceService.enterStage(runId, "UNDERSTAND");

        ExecutorService pool = Executors.newFixedThreadPool(4);
        try {
            List<Future<?>> futures = new java.util.ArrayList<>();
            for (int i = 0; i < 4; i++) {
                futures.add(pool.submit(() -> traceService.enterStage(runId, "REPLAN")));
            }
            for (Future<?> future : futures) {
                future.get(15, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }

        List<Integer> seqs = traceService.stagesOf(runId).stream().map(AgentRunStage::getSeqNo).toList();
        assertThat(seqs).doesNotHaveDuplicates();
        assertThat(seqs).isSorted();
        assertThat(seqs).startsWith(1);
    }
}
