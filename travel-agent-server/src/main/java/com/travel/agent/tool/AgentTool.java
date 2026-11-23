package com.travel.agent.tool;

import java.util.List;
import java.util.Map;

/** Agent 工具统一接口：所有工具经 ToolRegistry 注册、经 SafetyInterceptor 调用 */
public interface AgentTool {

    String getName();

    String getDescription();

    RiskLevel getRiskLevel();

    /**
     * 结构化参数声明（P0-B）：内置工具据此把参数名/必填/类型/说明渲染进研究循环提示词，
     * 并由 {@link ToolInvoker} 在 execute 前拒绝未知参数与缺失必填参数。
     *
     * <p>默认空列表 = 不做严格校验（MCP 工具沿用上游 inputSchema，避免误伤未声明但可用的字段）。
     */
    default List<ToolParameter> getParameters() {
        return List.of();
    }

    /**
     * 一个工具参数的结构化声明。
     *
     * @param name        参数名（必须与 execute 真实读取的键一致）
     * @param required    是否必填
     * @param type        机器类型（string / number / boolean / string-list），供说明与排查使用
     * @param description 给模型看的中文说明（渲染在参数名后的括号里）
     */
    record ToolParameter(String name, boolean required, String type, String description) {
    }

    /**
     * 是否只读。
     *
     * 架构约定：会改变数据的操作**不允许**做成 AgentTool，必须表达为 AgentPlan 的操作项，
     * 经「preview → Diff → 用户确认 → ExecutorAgent.commit」落地，并写操作历史（可撤销）。
     * 因此这里默认 true；若某个工具返回 false，ToolRegistry 会拒绝注册它。
     */
    default boolean readOnly() {
        return true;
    }

    Object execute(Map<String, Object> args);
}
