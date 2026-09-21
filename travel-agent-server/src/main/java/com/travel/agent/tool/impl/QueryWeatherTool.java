package com.travel.agent.tool.impl;

import com.travel.agent.model.WeatherInfo;
import com.travel.agent.service.WeatherService;
import com.travel.agent.tool.AgentTool;
import com.travel.agent.tool.RiskLevel;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.Map;

/** query_weather：查询天气 */
@Component
@RequiredArgsConstructor
public class QueryWeatherTool implements AgentTool {

    private final WeatherService weatherService;

    @Override
    public String getName() {
        return "query_weather";
    }

    @Override
    public String getDescription() {
        return "查询指定城市某天的天气";
    }

    @Override
    public RiskLevel getRiskLevel() {
        return RiskLevel.LOW;
    }

    @Override
    public Object execute(Map<String, Object> args) {
        String city = (String) args.getOrDefault("city", "上海");
        LocalDate date = args.get("date") == null ? LocalDate.now().plusDays(1)
                : LocalDate.parse(String.valueOf(args.get("date")));
        boolean mentionRain = Boolean.TRUE.equals(args.get("mentionRain"));
        WeatherInfo info = weatherService.forecast(city, date, mentionRain);
        return info;
    }
}
