package com.travel.agent.agent;

import com.travel.agent.dto.AgentDtos;
import com.travel.agent.entity.AgentRun;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 总状态聚合（P0-07）。
 *
 * <p>回归背景：运行页曾经能在「阶段失败 / 研究步骤预算耗尽」的同时显示一个绿色「成功」，
 * 用户因此信任了一份不完整的方案。判定必须聚合到一处，任一必要模块出问题就是部分完成。
 */
class AgentRunStatusAggregationTest {

    private AgentDtos.AgentResult completed(List<String> warnings) {
        return AgentDtos.AgentResult.builder()
                .intent("PLAN_TRIP")
                .status(AgentDtos.STATUS_COMPLETED)
                .message("已生成方案")
                .warnings(warnings)
                .build();
    }

    @Test
    @DisplayName("完全干净的一轮才是 SUCCEEDED")
    void cleanRunSucceeds() {
        assertThat(AgentEngine.aggregateRunStatus(completed(new ArrayList<>()), false, 0))
                .isEqualTo(AgentRun.STATUS_SUCCEEDED);
        // warnings 为 null 与空列表等价：没有警告可报
        assertThat(AgentEngine.aggregateRunStatus(completed(null), false, 0))
                .isEqualTo(AgentRun.STATUS_SUCCEEDED);
    }

    @Test
    @DisplayName("整体失败优先于一切：不能因为没警告就降级成部分完成")
    void failedResultStaysFailed() {
        AgentDtos.AgentResult failed = AgentDtos.AgentResult.builder()
                .status(AgentDtos.STATUS_FAILED)
                .message("模型不可用")
                .build();
        assertThat(AgentEngine.aggregateRunStatus(failed, false, 0)).isEqualTo(AgentRun.STATUS_FAILED);
        assertThat(AgentEngine.aggregateRunStatus(null, false, 0)).isEqualTo(AgentRun.STATUS_FAILED);
    }

    @Test
    @DisplayName("有过程警告（如研究步骤预算耗尽）就是部分完成")
    void warningsMakePartial() {
        List<String> warnings = List.of("研究步骤预算耗尽，已按现有资料生成");
        assertThat(AgentEngine.aggregateRunStatus(completed(warnings), false, 0))
                .isEqualTo(AgentRun.STATUS_PARTIAL);
    }

    @Test
    @DisplayName("预算降级也是部分完成")
    void budgetDegradationMakesPartial() {
        assertThat(AgentEngine.aggregateRunStatus(completed(new ArrayList<>()), true, 0))
                .isEqualTo(AgentRun.STATUS_PARTIAL);
    }

    @Test
    @DisplayName("有失败/降级任务（含失败后重派成功）也是部分完成")
    void failedTaskMakesPartial() {
        assertThat(AgentEngine.aggregateRunStatus(completed(new ArrayList<>()), false, 2))
                .isEqualTo(AgentRun.STATUS_PARTIAL);
    }

    @Test
    @DisplayName("等确认的方案同样参与聚合：带警告时不能显示无条件成功")
    void needConfirmationWithWarningsIsPartial() {
        AgentDtos.AgentResult pending = AgentDtos.AgentResult.builder()
                .status(AgentDtos.STATUS_NEED_CONFIRMATION)
                .message("方案待确认")
                .warnings(List.of("第 2 天行程紧凑，已建议调整"))
                .build();
        assertThat(AgentEngine.aggregateRunStatus(pending, false, 0))
                .isEqualTo(AgentRun.STATUS_PARTIAL);
    }
}