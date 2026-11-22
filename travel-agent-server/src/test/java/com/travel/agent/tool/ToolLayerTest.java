package com.travel.agent.tool;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * 工具层契约：只允许只读工具注册，写操作必须走 AgentPlan → 确认 → commit。
 * 回归背景：此前 add/modify/undo 三个写工具既绕过确认与撤销历史，又缺少归属校验。
 */
class ToolLayerTest {

    private static AgentTool tool(String name, boolean readOnly) {
        return new AgentTool() {
            @Override
            public String getName() {
                return name;
            }

            @Override
            public String getDescription() {
                return name;
            }

            @Override
            public RiskLevel getRiskLevel() {
                return RiskLevel.LOW;
            }

            @Override
            public boolean readOnly() {
                return readOnly;
            }

            @Override
            public Object execute(Map<String, Object> args) {
                return null;
            }
        };
    }

    @Test
    @DisplayName("只读工具正常注册")
    void registersReadOnlyTools() {
        ToolRegistry registry = new ToolRegistry(List.of(tool("search_attraction", true)));

        assertThat(registry.all()).containsKey("search_attraction");
    }

    @Test
    @DisplayName("会写数据的工具被拒绝注册")
    void rejectsMutatingTools() {
        ToolRegistry registry = new ToolRegistry(List.of(
                tool("search_attraction", true),
                tool("add_itinerary_item", false)));

        assertThat(registry.all()).containsOnlyKeys("search_attraction");
    }

    @Test
    @DisplayName("工具审计不再抛异常（HIGH 由方案层表达，不再伪装成系统错误）")
    void safetyCheckOnlyAudits() {
        SafetyInterceptor interceptor = new SafetyInterceptor();

        assertThatCode(() -> interceptor.check("demo", tool("search_hotel", true), Map.of("city", "上海")))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("风险评估：只读 LOW / 普通修改 MEDIUM / 超预算 HIGH")
    void evaluatePlanChangeLevels() {
        SafetyInterceptor interceptor = new SafetyInterceptor();
        java.math.BigDecimal before = java.math.BigDecimal.valueOf(1000);
        java.math.BigDecimal over = java.math.BigDecimal.valueOf(4000);
        java.math.BigDecimal budget = java.math.BigDecimal.valueOf(3000);

        assertThat(interceptor.evaluatePlanChange(true, before, before, budget)).isEqualTo(RiskLevel.LOW);
        assertThat(interceptor.evaluatePlanChange(false, before, java.math.BigDecimal.valueOf(2000), budget))
                .isEqualTo(RiskLevel.MEDIUM);
        assertThat(interceptor.evaluatePlanChange(false, before, over, budget)).isEqualTo(RiskLevel.HIGH);
    }
}
