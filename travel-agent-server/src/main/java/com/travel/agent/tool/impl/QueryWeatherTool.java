package com.travel.agent.tool.impl;

import com.travel.agent.model.WeatherLookup;
import com.travel.agent.service.WeatherService;
import com.travel.agent.tool.AgentTool;
import com.travel.agent.tool.RiskLevel;
import com.travel.agent.tool.ToolArgs;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * query_weather：查询天气。
 *
 * <p>只返回真实预报：结果形如 {@code {available, info, reason, message}}，
 * 不可用时 {@code available=false} 并给出原因（如「网络不可达」），
 * <b>不会用模拟数据填空</b>——模型据此要么如实说没查到，要么不拿天气当约束。
 */
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
        return "查询指定城市某天的真实天气预报；不可用时返回 available=false 与原因（不会返回模拟天气）";
    }

    @Override
    public RiskLevel getRiskLevel() {
        return RiskLevel.LOW;
    }

    @Override
    public List<ToolParameter> getParameters() {
        return List.of(
                new ToolParameter("city", true, "string", "城市"),
                new ToolParameter("date", false, "string", "日期 yyyy-MM-dd，默认明天"));
    }

    @Override
    public Object execute(Map<String, Object> args) {
        String city = ToolArgs.string(args, "city", "上海");
        String dateText = ToolArgs.string(args, "date");
        LocalDate date = dateText == null ? LocalDate.now().plusDays(1) : LocalDate.parse(dateText);
        WeatherLookup lookup = weatherService.forecast(city, date);
        return lookup == null ? WeatherLookup.unavailable(WeatherLookup.REASON_BAD_RESPONSE) : lookup;
    }
}
