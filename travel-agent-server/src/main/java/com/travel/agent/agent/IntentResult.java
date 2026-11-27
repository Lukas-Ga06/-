package com.travel.agent.agent;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
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
    /** 只读：查询某天/某范围内的空闲时段（08:00–22:00 窗口） */
    public static final String QUERY_FREE_TIME = "QUERY_FREE_TIME";
    /** 调用外部 MCP 工具（travel.mcp.servers 注册的 mcp_* 只读工具）；仅 LLM 路径可产出 */
    public static final String INVOKE_TOOL = "INVOKE_TOOL";
    public static final String CHITCHAT = "CHITCHAT";

    public static final String TARGET_OUTDOOR = "OUTDOOR_ACTIVITY";
    public static final String TARGET_SELECTED = "SELECTED_ITEM";
    public static final String TARGET_NONE = "NONE";

    private String intent;
    private String target;
    private Map<String, Object> constraints;

    /**
     * 规划需求槽位（FR-01）：这一条消息里明确说出的目的地/天数/人数/预算/日期。
     * 只有 PLAN_TRIP 会消费它；跨轮累积与缺槽追问由 AgentEngine 负责（ADR-010）。
     */
    private RequirementSlots slots;

    /**
     * 同一条消息里还包含、但本轮<b>不执行</b>的其他独立请求（P0-B，最多 3 条、每条 ≤40 字）。
     *
     * <p>当前实现仍然只处理最重要的一个意图；这份清单只用于在最终回复里明确告知
     * 「另外你提到的 X 这次没有执行」，避免静默只做一半。仅 LLM 路径产出，规则兜底保持空。
     */
    @Builder.Default
    private List<String> alsoRequested = List.of();

    /** INVOKE_TOOL：要调用的 MCP 工具名（mcp_*）与参数 */
    private String toolName;
    private Map<String, Object> toolArgs;

    /** null 安全的读取：手工构造/反序列化可能把该字段留空，调用方不需要到处判空 */
    public List<String> getAlsoRequested() {
        return alsoRequested == null ? List.of() : alsoRequested;
    }
}
