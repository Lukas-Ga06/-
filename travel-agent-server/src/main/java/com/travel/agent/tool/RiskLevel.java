package com.travel.agent.tool;

/** 工具风险等级：决定 Human-in-the-loop 策略 */
public enum RiskLevel {
    /** 查询、推荐 —— 自动执行 */
    LOW,
    /** 修改行程 —— 执行后确认（生成 Diff 供用户确认） */
    MEDIUM,
    /** 大额消费、取消重要安排 —— 执行前确认 */
    HIGH
}
