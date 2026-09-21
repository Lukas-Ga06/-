package com.travel.agent.tool.impl;

import com.travel.agent.model.CityCatalog;
import com.travel.agent.service.OnlineCityService;
import com.travel.agent.tool.AgentTool;
import com.travel.agent.tool.RiskLevel;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Map;

/** search_city_online：内置目录之外的城市，联网定位并在线生成旅行目录 */
@Component
@RequiredArgsConstructor
public class OnlineCitySearchTool implements AgentTool {

    private final OnlineCityService onlineCityService;

    @Override
    public String getName() {
        return "search_city_online";
    }

    @Override
    public String getDescription() {
        return "联网查询城市（内置目录之外）的景点/酒店/餐厅数据并生成旅行目录";
    }

    @Override
    public RiskLevel getRiskLevel() {
        return RiskLevel.LOW;
    }

    @Override
    public Object execute(Map<String, Object> args) {
        String city = String.valueOf(args.getOrDefault("city", "")).trim();
        CityCatalog catalog = onlineCityService.getOrLoad(city);
        return catalog == null ? Map.of("found", false, "city", city) : catalog;
    }
}
