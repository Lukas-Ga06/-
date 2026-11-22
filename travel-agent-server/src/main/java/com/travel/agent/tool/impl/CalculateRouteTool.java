package com.travel.agent.tool.impl;

import com.travel.agent.model.RouteInfo;
import com.travel.agent.service.RouteService;
import com.travel.agent.tool.AgentTool;
import com.travel.agent.tool.RiskLevel;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

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
    public Object execute(Map<String, Object> args) {
        double fromLat = ((Number) args.get("fromLat")).doubleValue();
        double fromLng = ((Number) args.get("fromLng")).doubleValue();
        double toLat = ((Number) args.get("toLat")).doubleValue();
        double toLng = ((Number) args.get("toLng")).doubleValue();
        RouteInfo info = routeService.route(fromLat, fromLng, toLat, toLng);
        return info;
    }
}
