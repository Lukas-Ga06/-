package com.travel.agent.tool.impl;

import com.travel.agent.model.RouteInfo;
import com.travel.agent.service.RouteService;
import com.travel.agent.tool.AgentTool;
import com.travel.agent.tool.RiskLevel;
import com.travel.agent.tool.ToolArgs;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/** calculate_route：路线计算 */
@Component
@RequiredArgsConstructor
public class CalculateRouteTool implements AgentTool {

    private final RouteService routeService;

    @Override
    public String getName() {
        return "calculate_route";
    }

    @Override
    public String getDescription() {
        return "计算两点间距离与出行时间（步行/驾车/打车费用）";
    }

    @Override
    public RiskLevel getRiskLevel() {
        return RiskLevel.LOW;
    }

    @Override
    public List<ToolParameter> getParameters() {
        return List.of(
                new ToolParameter("fromLat", true, "number", "起点纬度"),
                new ToolParameter("fromLng", true, "number", "起点经度"),
                new ToolParameter("toLat", true, "number", "终点纬度"),
                new ToolParameter("toLng", true, "number", "终点经度"));
    }

    @Override
    public Object execute(Map<String, Object> args) {
        double fromLat = ToolArgs.requireDouble(args, "fromLat");
        double fromLng = ToolArgs.requireDouble(args, "fromLng");
        double toLat = ToolArgs.requireDouble(args, "toLat");
        double toLng = ToolArgs.requireDouble(args, "toLng");
        RouteInfo info = routeService.route(fromLat, fromLng, toLat, toLng);
        return info;
    }
}
