package com.travel.agent.mcp;

import com.travel.agent.config.McpProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 逐 server 治理闸门的纯单元测试：白名单 / 配额 / 熔断 / 关闭即零影响。
 * 时钟用 LongSupplier 注入，不 sleep，断言的是语义而不是计时精度。
 */
class McpGovernorTest {

    /** 可手动推进的假时钟（只读原子，测试单线程） */
    private static final class FakeClock {
        private final AtomicLong now = new AtomicLong(1_000_000L);

        long get() {
            return now.get();
        }

        void advance(long millis) {
            now.addAndGet(millis);
        }
    }

    private static McpProperties.Server config() {
        return new McpProperties.Server();
    }

    @Test
    @DisplayName("未配置治理项时行为不变：全部工具放行、永不熔断、不限次数")
    void defaultsKeepLegacyBehaviour() {
        McpGovernor governor = new McpGovernor("amap", config(), () -> 1_000_000L);

        assertTrue(governor.permitsTool("maps_geo"));
        for (int i = 0; i < 50; i++) {
            McpGovernor.Decision decision = governor.beforeCall("maps_geo");
            assertTrue(decision.allowed(), "默认配置不应拒绝调用");
            governor.recordFailure("boom " + i);
        }
        McpGovernor.Snapshot snapshot = governor.snapshot();
        assertEquals(50, snapshot.calls());
        assertEquals(50, snapshot.failures());
        assertEquals(0, snapshot.rejected());
        assertEquals(McpGovernor.CircuitState.CLOSED, snapshot.circuit());
        assertEquals(0, snapshot.callsThisMinute(), "未配置配额时不统计分钟窗口");
    }

    @Test
    @DisplayName("白名单：非空只放行列出的远端工具，拒绝计入 rejected 且不消耗调用数")
    void allowToolsFiltersUnlistedTools() {
        McpProperties.Server cfg = config();
        cfg.setAllowTools(List.of("maps_geo", " maps_text_search "));
        McpGovernor governor = new McpGovernor("amap", cfg, () -> 1_000_000L);

        assertTrue(governor.permitsTool("maps_geo"));
        assertTrue(governor.permitsTool("maps_text_search"), "白名单项应 trim 后匹配");
        assertFalse(governor.permitsTool("maps_weather"));

        McpGovernor.Decision denied = governor.beforeCall("maps_weather");
        assertFalse(denied.allowed());
        assertEquals(403, denied.statusCode());
        assertTrue(denied.reason().contains("allow-tools"));

        McpGovernor.Snapshot snapshot = governor.snapshot();
        assertEquals(0, snapshot.calls(), "被拒绝的调用不消耗调用数");
        assertEquals(1, snapshot.rejected());
    }

    @Test
    @DisplayName("配额：每分钟上限用尽即拒绝，窗口滚动后恢复；上限不因拒绝而累计")
    void quotaResetsAfterOneMinute() {
        FakeClock clock = new FakeClock();
        McpProperties.Server cfg = config();
        cfg.setMaxCallsPerMinute(2);
        McpGovernor governor = new McpGovernor("amap", cfg, clock::get);

        assertTrue(governor.beforeCall("maps_geo").allowed());
        assertTrue(governor.beforeCall("maps_geo").allowed());
        McpGovernor.Decision third = governor.beforeCall("maps_geo");
        assertFalse(third.allowed());
        assertEquals(503, third.statusCode());
        assertTrue(third.reason().contains("每分钟调用上限"));
        assertEquals(2, governor.snapshot().calls());
        assertEquals(1, governor.snapshot().rejected());
        assertEquals(2, governor.snapshot().callsThisMinute());

        clock.advance(McpGovernor.WINDOW_MS);
        assertTrue(governor.beforeCall("maps_geo").allowed(), "窗口滚动后应重新放行");
        assertEquals(1, governor.snapshot().callsThisMinute());
    }

    @Test
    @DisplayName("熔断：连续失败到阈值即打开，冷却期快速失败，冷却后半开探测失败重新打开、成功闭合")
    void circuitBreakerOpensAndRecovers() {
        FakeClock clock = new FakeClock();
        McpProperties.Server cfg = config();
        cfg.setFailureThreshold(2);
        cfg.setCooldownMs(1_000L);
        McpGovernor governor = new McpGovernor("amap", cfg, clock::get);

        assertTrue(governor.beforeCall("maps_geo").allowed());
        governor.recordFailure("timeout-1");
        assertEquals(McpGovernor.CircuitState.CLOSED, governor.snapshot().circuit(),
                "一次失败未到阈值，仍为闭合");
        assertTrue(governor.beforeCall("maps_geo").allowed());
        governor.recordFailure("timeout-2");
        assertEquals(McpGovernor.CircuitState.OPEN, governor.snapshot().circuit());

        McpGovernor.Decision fastFail = governor.beforeCall("maps_geo");
        assertFalse(fastFail.allowed(), "冷却期内应快速失败，不再发起外部请求");
        assertEquals(503, fastFail.statusCode());
        assertTrue(fastFail.reason().contains("熔断中"));

        clock.advance(1_000L);
        McpGovernor.Decision probe = governor.beforeCall("maps_geo");
        assertTrue(probe.allowed(), "冷却结束后应放行一次半开探测");
        assertEquals(McpGovernor.CircuitState.HALF_OPEN, governor.snapshot().circuit());
        McpGovernor.Decision duringProbe = governor.beforeCall("maps_geo");
        assertFalse(duringProbe.allowed(), "半开探测进行中不允许其它并发调用");
        governor.recordFailure("probe-failed");
        assertEquals(McpGovernor.CircuitState.OPEN, governor.snapshot().circuit(),
                "探测失败立即重新打开");

        clock.advance(1_000L);
        assertTrue(governor.beforeCall("maps_geo").allowed());
        governor.recordSuccess();
        assertEquals(McpGovernor.CircuitState.CLOSED, governor.snapshot().circuit());
        assertTrue(governor.beforeCall("maps_geo").allowed(), "熔断闭合后恢复正常放行");
    }

    @Test
    @DisplayName("审计摘要只含低基数计数，不含参数/结果")
    void auditTagContainsCountersOnly() {
        McpGovernor governor = new McpGovernor("amap", config(), () -> 1_000_000L);
        assertTrue(governor.beforeCall("maps_geo").allowed());
        governor.recordSuccess();
        String tag = governor.auditTag();
        assertTrue(tag.contains("state=closed"));
        assertTrue(tag.contains("calls=1"));
        assertTrue(tag.contains("failures=0"));
        assertFalse(tag.contains("maps_geo"), "审计摘要不能带工具参数或工具名以外的调用内容");
    }
}