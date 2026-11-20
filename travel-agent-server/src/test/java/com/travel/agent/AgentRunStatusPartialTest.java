package com.travel.agent;

import com.travel.agent.entity.AgentTask;
import com.travel.agent.service.AgentTraceService;
import com.travel.agent.support.IntegrationTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 「失败任务」这一路聚合信号（P0-07）。
 *
 * <p>总状态判定本身由 {@code AgentRunStatusAggregationTest} 覆盖；这里补它拿到的输入：
 * 任务行里的 FAILED 必须真的能被统计出来，成功任务不能误计。工具失败后重派成功的运行，
 * 失败任务行仍然保留，因此会判为「部分完成」而不是无条件成功。
 */
class AgentRunStatusPartialTest extends IntegrationTestSupport {

    @Autowired
    private AgentTraceService traceService;

    @Test
    @DisplayName("失败任务计入聚合信号，成功的任务不计入")
    void failedTaskCountFeedsAggregation() throws Exception {
        Account account = registerNew();
        String runId = traceService.startRun(account.id(), null, "帮我规划杭州两日游").getRunId();

        String okTask = traceService.startTask(runId, account.id(), "intent-agent", "INTENT");
        traceService.finishTask(okTask, AgentTask.STATUS_SUCCEEDED, "识别意图: PLAN_TRIP", null);
        assertThat(traceService.failedTaskCount(runId)).isZero();

        String failedTask = traceService.startTask(runId, account.id(), "research-agent", "RESEARCH");
        traceService.finishTask(failedTask, AgentTask.STATUS_FAILED, null, "工具超时");

        assertThat(traceService.failedTaskCount(runId)).isEqualTo(1);

        // 重派后新任务成功，但失败行仍在：这条运行属于「已降级完成」，不能被当成完全成功
        String retried = traceService.startTask(runId, account.id(), "research-agent", "RESEARCH");
        traceService.finishTask(retried, AgentTask.STATUS_SUCCEEDED, "重派后成功", null);
        assertThat(traceService.failedTaskCount(runId)).isEqualTo(1);
    }

    @Test
    @DisplayName("没有 run 记录时统计为 0（缺追踪是缺证据，不是失败）")
    void missingRunCountsZero() {
        assertThat(traceService.failedTaskCount(null)).isZero();
        assertThat(traceService.failedTaskCount("run-does-not-exist")).isZero();
    }
}