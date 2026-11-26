package com.travel.agent.observability;

import com.travel.agent.entity.PlanOutbox;
import com.travel.agent.repository.PlanOutboxRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 阶段三可靠性指标：`outbox.status{status}` 的命名、tag 与「抓取时现查」契约。
 *
 * <p>为什么不测「投递器顺手记数」：本指标刻意不依赖投递器存活——投递器停摆时它仍应反映库里的积压。
 * 这里用 SimpleMeterRegistry + 桩仓库验证：Gauge 的值来自当前查询，且查询失败会抛错（序列缺失），
 * 不会被伪造成 0（「查不到」与「没有积压」必须能区分）。
 */
class OutboxStatusMetricsTest {

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final PlanOutboxRepository repository = mock(PlanOutboxRepository.class);

    private double gauge(String status) {
        return registry.get(TravelMetrics.OUTBOX_STATUS).tag("status", status).gauge().value();
    }

    @Test
    @DisplayName("注册四条序列：pending/dispatching/done/dead，值是抓取时的实时计数")
    void registersGaugePerStatusReadingRepository() {
        when(repository.countByStatus(PlanOutbox.STATUS_PENDING)).thenReturn(7L);
        when(repository.countByStatus(PlanOutbox.STATUS_DISPATCHING)).thenReturn(2L);
        when(repository.countByStatus(PlanOutbox.STATUS_DONE)).thenReturn(41L);
        when(repository.countByStatus(PlanOutbox.STATUS_DEAD)).thenReturn(1L);

        new OutboxStatusMetrics(registry, repository);

        assertThat(gauge("pending")).isEqualTo(7.0);
        assertThat(gauge("dispatching")).isEqualTo(2.0);
        assertThat(gauge("done")).isEqualTo(41.0);
        assertThat(gauge("dead")).isEqualTo(1.0);
        assertThat(registry.find(TravelMetrics.OUTBOX_STATUS).gauges()).hasSize(4);
    }

    @Test
    @DisplayName("抓取时现查而非冻结：两次读取走两次查询，反映中间的变化")
    void readsLiveValuesOnEveryScrape() {
        when(repository.countByStatus(PlanOutbox.STATUS_DEAD)).thenReturn(0L, 3L);
        when(repository.countByStatus(PlanOutbox.STATUS_PENDING)).thenReturn(0L, 0L);
        when(repository.countByStatus(PlanOutbox.STATUS_DISPATCHING)).thenReturn(0L, 0L);
        when(repository.countByStatus(PlanOutbox.STATUS_DONE)).thenReturn(0L, 0L);

        new OutboxStatusMetrics(registry, repository);

        assertThat(gauge("dead")).isZero();
        assertThat(gauge("dead")).isEqualTo(3.0);
    }

    @Test
    @DisplayName("数据库查询失败不伪造 0：Gauge 采样为 NaN（不触发 >0 告警，也不会显示成 0 积压）")
    void queryFailureIsNotReportedAsZero() {
        when(repository.countByStatus(PlanOutbox.STATUS_DEAD)).thenThrow(new IllegalStateException("db down"));
        when(repository.countByStatus(PlanOutbox.STATUS_PENDING)).thenReturn(0L);
        when(repository.countByStatus(PlanOutbox.STATUS_DISPATCHING)).thenReturn(0L);
        when(repository.countByStatus(PlanOutbox.STATUS_DONE)).thenReturn(0L);

        new OutboxStatusMetrics(registry, repository);

        assertThat(gauge("dead")).isNaN();
    }
}