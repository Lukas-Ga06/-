package com.travel.agent.agent;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

/** Intent Agent 输出：用户到底想干什么 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class IntentResult {

    public static final String PLAN_TRIP = "PLAN_TRIP";
    public static final String MODIFY_ITINERARY = "MODIFY_ITINERARY";
    public static final String QUERY_BUDGET = "QUERY_BUDGET";
    public static final String QUERY_KNOWLEDGE = "QUERY_KNOWLEDGE";
    public static final String RECORD_EXPENSE = "RECORD_EXPENSE";
    public static final String ADD_MEMO = "ADD_MEMO";
    public static final String QUERY_MEMO = "QUERY_MEMO";
    public static final String ADD_SCHEDULE = "ADD_SCHEDULE";
    public static final String QUERY_SCHEDULE = "QUERY_SCHEDULE";
    public static final String GEN_DAILY_REPORT = "GEN_DAILY_REPORT";
    public static final String QUERY_HEALTH = "QUERY_HEALTH";
    /** 调用外部 MCP 工具（travel.mcp.servers 注册的 mcp_* 只读工具）；仅 LLM 路径可产出 */
    public static final String INVOKE_TOOL = "INVOKE_TOOL";
    public static final String CHITCHAT = "CHITCHAT";

    public static final String TARGET_OUTDOOR = "OUTDOOR_ACTIVITY";
    public static final String TARGET_SELECTED = "SELECTED_ITEM";
    public static final String TARGET_NONE = "NONE";

    private String intent;
    private String target;
    private Map<String, Object> constraints;

    /** INVOKE_TOOL：要调用的 MCP 工具名（mcp_*）与参数 */
    private String toolName;
    private Map<String, Object> toolArgs;
}
