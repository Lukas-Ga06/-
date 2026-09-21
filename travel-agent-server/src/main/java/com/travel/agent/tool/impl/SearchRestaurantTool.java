package com.travel.agent.tool.impl;

import com.travel.agent.service.TravelCatalogService;
import com.travel.agent.tool.AgentTool;
import com.travel.agent.tool.RiskLevel;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

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
    public Object execute(Map<String, Object> args) {
        var city = catalogService.requireCity((String) args.getOrDefault("city", ""));
        Double nearLat = args.get("nearLat") == null ? null : ((Number) args.get("nearLat")).doubleValue();
        Double nearLng = args.get("nearLng") == null ? null : ((Number) args.get("nearLng")).doubleValue();
        return catalogService.searchRestaurants(city, nearLat, nearLng, 5);
    }
}
