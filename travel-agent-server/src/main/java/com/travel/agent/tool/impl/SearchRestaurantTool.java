package com.travel.agent.tool.impl;

import com.travel.agent.service.TravelCatalogService;
import com.travel.agent.tool.AgentTool;
import com.travel.agent.tool.RiskLevel;
import com.travel.agent.tool.ToolArgs;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/** search_restaurant：搜索餐厅 */
@Component
@RequiredArgsConstructor
public class SearchRestaurantTool implements AgentTool {

    private final TravelCatalogService catalogService;

    @Override
    public String getName() {
        return "search_restaurant";
    }

    @Override
    public String getDescription() {
        return "搜索指定位置附近的餐厅";
    }

    @Override
    public RiskLevel getRiskLevel() {
        return RiskLevel.LOW;
    }

    @Override
    public List<ToolParameter> getParameters() {
        return List.of(
                new ToolParameter("city", true, "string", "城市"),
                new ToolParameter("nearLat", false, "number", "数字"),
                new ToolParameter("nearLng", false, "number", "数字"));
    }

    @Override
    public Object execute(Map<String, Object> args) {
        var city = catalogService.requireCity(ToolArgs.string(args, "city", ""));
        Double nearLat = ToolArgs.doubleValue(args, "nearLat");
        Double nearLng = ToolArgs.doubleValue(args, "nearLng");
        return catalogService.searchRestaurants(city, nearLat, nearLng, 5);
    }
}
