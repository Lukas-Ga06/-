package com.travel.agent.tool;

import com.travel.agent.exception.HumanConfirmationRequiredException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Safety Interceptor：所有 Agent Tool 调用必须经过
 * Agent → SafetyInterceptor → Permission/Risk Check → Audit → Tool。
 */
@Component
@Slf4j
public class SafetyInterceptor {

    /** 工具调用前风险检查（LOW/MEDIUM 直接放行并审计，HIGH 阻断并要求人工确认） */
    public void check(String username, AgentTool tool, java.util.Map<String, Object> args) {
        log.info("[Audit] user={} tool={} risk={} args={}", username, tool.getName(), tool.getRiskLevel(), args);
        if (tool.getRiskLevel() == RiskLevel.HIGH) {
            throw new HumanConfirmationRequiredException("操作「" + tool.getName() + "」风险等级为 HIGH，需要用户确认后执行");
        }
    }

    /** 行程变更计划的风险评估：超预算 → HIGH，普通修改 → MEDIUM，只读 → LOW */
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
