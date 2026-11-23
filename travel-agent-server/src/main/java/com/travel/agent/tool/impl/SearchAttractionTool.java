package com.travel.agent.tool.impl;

import com.travel.agent.model.Attraction;
import com.travel.agent.service.TravelCatalogService;
import com.travel.agent.tool.AgentTool;
import com.travel.agent.tool.RiskLevel;
import com.travel.agent.tool.ToolArgs;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

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
    public List<ToolParameter> getParameters() {
        return List.of(
                new ToolParameter("city", true, "string", "城市"),
                new ToolParameter("venue", false, "string", "室内/室外"),
                new ToolParameter("nearLat", false, "number", "数字"),
                new ToolParameter("nearLng", false, "number", "数字"),
                new ToolParameter("exclude", false, "string-list", "逗号分隔"));
    }

    @Override
    public Object execute(Map<String, Object> args) {
        var city = catalogService.requireCity(ToolArgs.string(args, "city", ""));
        String venue = normalizeVenue(ToolArgs.string(args, "venue"));
        Double nearLat = ToolArgs.doubleValue(args, "nearLat");
        Double nearLng = ToolArgs.doubleValue(args, "nearLng");
        List<String> exclude = ToolArgs.stringList(args, "exclude");
        List<Attraction> result = catalogService.searchAttractions(city, venue, nearLat, nearLng, exclude, 6);
        return result;
    }

    /** 目录层只认 indoor/outdoor；提示词里写的是「室内/室外」，模型可能照抄中文，这里做一次归一化 */
    private static String normalizeVenue(String venue) {
        if ("室内".equals(venue)) {
            return "indoor";
        }
        if ("室外".equals(venue)) {
            return "outdoor";
        }
        return venue;
    }
}
