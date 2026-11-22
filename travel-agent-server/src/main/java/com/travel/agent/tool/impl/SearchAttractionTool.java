package com.travel.agent.tool.impl;

import com.travel.agent.model.Attraction;
import com.travel.agent.service.TravelCatalogService;
import com.travel.agent.tool.AgentTool;
import com.travel.agent.tool.RiskLevel;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

/** search_attraction：搜索指定城市/位置附近的旅游景点 */
@Component
@RequiredArgsConstructor
public class SearchAttractionTool implements AgentTool {

    private final TravelCatalogService catalogService;

    @Override
    public String getName() {
        return "search_attraction";
    }

    @Override
    public String getDescription() {
        return "搜索指定位置附近的旅游景点，可指定室内/室外";
    }

    @Override
    public RiskLevel getRiskLevel() {
        return RiskLevel.LOW;
    }

    @Override
    public Object execute(Map<String, Object> args) {
        var city = catalogService.requireCity((String) args.getOrDefault("city", ""));
        String venue = (String) args.get("venue");
        Double nearLat = args.get("nearLat") == null ? null : ((Number) args.get("nearLat")).doubleValue();
        Double nearLng = args.get("nearLng") == null ? null : ((Number) args.get("nearLng")).doubleValue();
        String excludeStr = (String) args.get("exclude");
        List<String> exclude = excludeStr == null || excludeStr.isBlank() ? List.of() : Arrays.asList(excludeStr.split(","));
        List<Attraction> result = catalogService.searchAttractions(city, venue, nearLat, nearLng, exclude, 6);
        return result;
    }
}
