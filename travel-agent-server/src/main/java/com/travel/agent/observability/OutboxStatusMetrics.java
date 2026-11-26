package com.travel.agent.observability;

import com.travel.agent.entity.PlanOutbox;
import com.travel.agent.repository.PlanOutboxRepository;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;

/**
 * 阶段三可靠性指标：`outbox.status{status}`（P0 事务性 Outbox 的按状态行数）。
 *
 * <p>为什么是 Gauge 而不是「投递器每轮顺手记一个数」：投递器停摆时，后者会冻结在最后一个值，
 * 面板看起来一切正常；Gauge 在每次抓取时现查库，积压与 DEAD 不会因为投递器不跑而消失。
 *
 * <p>查询代价：`countByStatus` 只走 `(status, next_attempt_at)` 索引的 status 前缀，四类状态各一条；
 * 抓取间隔（15–30s）远低于任何有意义的积压变化速度。DB 不可用时 Micrometer 把该次采样记为
 * NaN（不是伪造 0——「查不到」和「没有积压」必须能区分；NaN 不会触发 `> 0` 告警，曲线上是断点）。
 *
 * <p>基数：状态是枚举，四条序列封顶；runId/planId 一律不进 tag。
 */
@Component
@Slf4j
public class OutboxStatusMetrics {

    /** 与 {@link PlanOutbox} 的状态常量一一对应；顺序固定，护住面板/告警的 tag 稳定性 */
    static final List<String> STATUSES = List.of(
            PlanOutbox.STATUS_PENDING,
            PlanOutbox.STATUS_DISPATCHING,
            PlanOutbox.STATUS_DONE,
            PlanOutbox.STATUS_DEAD);

    private final PlanOutboxRepository repository;

    public OutboxStatusMetrics(MeterRegistry meterRegistry, PlanOutboxRepository repository) {
        this.repository = repository;
        for (String status : STATUSES) {
            String tag = status.toLowerCase(Locale.ROOT);
            Gauge.builder(TravelMetrics.OUTBOX_STATUS, () -> count(status))
                    .description("plan_outbox 行数（按状态；状态为 PENDING/DISPATCHING/DONE/DEAD）")
                    .tag("status", tag)
                    .register(meterRegistry);
        }
        log.info("已注册可靠性指标: {}{{status=pending|dispatching|done|dead}}", TravelMetrics.OUTBOX_STATUS);
    }

    /** 抓取时现查；失败不吞（Micrometer 记为 NaN，避免用 0 掩盖「查不到」） */
    private double count(String status) {
        return repository.countByStatus(status);
    }
}