package com.travel.agent.agent;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Agent 阶段计时（指标 {@code agent.stage{stage=intent|plan|tool|reflect|commit}}）。
 *
 * <p>从 {@link AgentEngine} 抽出：SSE 主链路、确认提交链路（{@link PlanConfirmationService}）
 * 都要记同一组指标，集中在一处保证 tag 与失败处理一致。
 *
 * <p>{@code reflect} 是 V2.2 FR-06 新增的阶段：审核现在会查路线与天气，并在必要时重规划，
 * 耗时不再可以忽略；不单独计时就看不见「审核/重规划」对总时长的影响。
 *
 * <p>约定：计时<b>必须</b>放 finally，异常路径同样要落到指标上，否则「超时/报错阶段」在指标里完全消失。
 */
@Component
@Slf4j
public class AgentStageTimer {

    /** 阶段计时指标名（对外契约，改动需同步监控看板） */
    public static final String METRIC = "agent.stage";

    public static final String STAGE_INTENT = "intent";
    public static final String STAGE_PLAN = "plan";
    public static final String STAGE_TOOL = "tool";
    /** V2.2 FR-06：审核 + 有限重规划 */
    public static final String STAGE_REFLECT = "reflect";
    public static final String STAGE_COMMIT = "commit";

    private final MeterRegistry meterRegistry;

    public AgentStageTimer(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    public Timer.Sample start() {
        return Timer.start(meterRegistry);
    }

    /** 结束阶段计时；指标故障绝不能反噬主流程 */
    public void stop(String stage, Timer.Sample sample) {
        if (sample == null) {
            return;
        }
        try {
            sample.stop(Timer.builder(METRIC).tag("stage", stage).register(meterRegistry));
        } catch (RuntimeException e) {
            log.debug("记录阶段指标失败: stage={} err={}", stage, e.getMessage());
        }
    }
}