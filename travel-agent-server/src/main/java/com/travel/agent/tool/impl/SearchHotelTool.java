package com.travel.agent.tool.impl;

import com.travel.agent.service.TravelCatalogService;
import com.travel.agent.tool.AgentTool;
import com.travel.agent.tool.RiskLevel;
import com.travel.agent.tool.ToolArgs;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/** search_hotel：搜索酒店 */
@Component
@RequiredArgsConstructor
public class SearchHotelTool implements AgentTool {

    private final TravelCatalogService catalogService;

    @Override
    public String getName() {
        return "search_hotel";
    }

    @Override
    public String getDescription() {
        return "搜索城市酒店，可指定靠近地铁与价格上限";
    }

    @Override
    public RiskLevel getRiskLevel() {
        return RiskLevel.LOW;
    }

    @Override
    public List<ToolParameter> getParameters() {
        return List.of(
                new ToolParameter("city", true, "string", "城市"),
                new ToolParameter("preferSubway", false, "boolean", "是否优先近地铁"),
                new ToolParameter("maxPrice", false, "number", "每晚价格上限"));
    }

    @Override
    public Object execute(Map<String, Object> args) {
        var city = catalogService.requireCity(ToolArgs.string(args, "city", ""));
        boolean preferSubway = Boolean.TRUE.equals(ToolArgs.booleanValue(args, "preferSubway"));
        Double maxPriceValue = ToolArgs.doubleValue(args, "maxPrice");
        BigDecimal maxPrice = maxPriceValue == null ? null : BigDecimal.valueOf(maxPriceValue);
        List<?> hotels = catalogService.searchHotels(city, preferSubway, maxPrice, 5);
        return hotels;
    }
}
