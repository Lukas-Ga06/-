package com.travel.agent.tool;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Safety Interceptor：所有 Agent Tool 调用必须经过
 * Agent → SafetyInterceptor → Permission/Risk Check → Audit → Tool。
 *
 * 关于「HIGH 风险」的唯一出口：
 * - 工具层只允许只读操作（由 {@link ToolRegistry} 强制），所以这里不再需要"阻断执行"；
 * - 真正的风险判定发生在**方案层**：{@link #evaluatePlanChange} 判断「本次变更是只读 / 普通修改 / 超预算」，
 *   结果由 ExecutorAgent.preview 写成 `confirmation.riskLevel`，前端据此弹出带警告的确认框。
 *   也就是说 HIGH = 需要用户显式确认的方案，而不再是一种"抛异常报错"的行为。
 */
@Component
@Slf4j
public class SafetyInterceptor {

    /** 工具调用审计（工具层已保证只读，此处只记录调用链，便于排查） */
    public void check(String username, AgentTool tool, java.util.Map<String, Object> args) {
        log.info("[Audit] user={} tool={} risk={} readOnly={} args={}",
                username, tool.getName(), tool.getRiskLevel(), tool.readOnly(), args);
        if (tool.getRiskLevel() == RiskLevel.HIGH) {
            // 只读工具理论上不该是 HIGH；出现即说明风险等级标注有误，留下显式警告
            log.warn("只读工具 {} 被标注为 HIGH 风险，请确认其风险等级是否合理", tool.getName());
        }
    }

    /** 行程变更计划的风险评估：超预算 → HIGH（需显式确认），普通修改 → MEDIUM，只读 → LOW */
    public RiskLevel evaluatePlanChange(boolean readOnly, java.math.BigDecimal budgetBefore, java.math.BigDecimal budgetAfter, java.math.BigDecimal tripBudget) {
        if (readOnly) {
            return RiskLevel.LOW;
        }
        if (tripBudget != null && budgetAfter != null && budgetAfter.compareTo(tripBudget) > 0) {
            return RiskLevel.HIGH;
        }
        return RiskLevel.MEDIUM;
    }
}
