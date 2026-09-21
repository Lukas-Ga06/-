package com.travel.agent.service;

import com.travel.agent.model.WeatherInfo;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.List;

/**
 * 天气查询（MVP 为确定性模拟数据：同一城市同一天结果稳定）。
 * 用户消息中明确提到「雨」时模拟降雨场景，便于端到端演示「下雨换景点」。
 * 生产环境替换为和风天气/彩云天气 API 即可，接口不变。
 */
@Service
public class WeatherService {

    private static final List<String> CONDITIONS = List.of("晴", "多云", "阴", "多云", "晴", "阴", "小雨");

    public WeatherInfo forecast(String city, LocalDate date, boolean mentionRain) {
        int seed = Math.abs((city + date).hashCode());
        String condition = CONDITIONS.get(seed % CONDITIONS.size());
        int rainProbability = switch (condition) {
            case "小雨" -> 70 + seed % 20;
            case "阴" -> 20 + seed % 20;
            default -> seed % 15;
        };
        if (mentionRain) {
            condition = "小雨";
            rainProbability = 80;
        }
        int tempLow = 14 + seed % 8;
        int tempHigh = tempLow + 7 + seed % 6;
        return new WeatherInfo(city, date, condition, rainProbability, tempLow, tempHigh);
    }
}
