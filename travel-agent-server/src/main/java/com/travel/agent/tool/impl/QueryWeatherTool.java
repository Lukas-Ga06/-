package com.travel.agent.tool.impl;

import com.travel.agent.model.WeatherInfo;
import com.travel.agent.service.WeatherService;
import com.travel.agent.tool.AgentTool;
import com.travel.agent.tool.RiskLevel;
import com.travel.agent.tool.ToolArgs;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.List;
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
    public List<ToolParameter> getParameters() {
        return List.of(
                new ToolParameter("city", true, "string", "城市"),
                new ToolParameter("date", false, "string", "日期 yyyy-MM-dd，默认明天"),
                new ToolParameter("mentionRain", false, "boolean", "是否关注降雨"));
    }

    @Override
    public Object execute(Map<String, Object> args) {
        String city = ToolArgs.string(args, "city", "上海");
        String dateText = ToolArgs.string(args, "date");
        LocalDate date = dateText == null ? LocalDate.now().plusDays(1) : LocalDate.parse(dateText);
        boolean mentionRain = Boolean.TRUE.equals(ToolArgs.booleanValue(args, "mentionRain"));
        WeatherInfo info = weatherService.forecast(city, date, mentionRain);
        return info;
    }
}
