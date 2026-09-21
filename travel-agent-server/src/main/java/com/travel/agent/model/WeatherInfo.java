package com.travel.agent.model;

import java.time.LocalDate;

/** 天气查询结果（MVP 为模拟数据，接口与真实天气服务一致） */
public record WeatherInfo(
        String city,
        LocalDate date,
        String condition,
        int rainProbability,
        int tempLow,
        int tempHigh
) {
    public boolean isRainy() {
        return condition != null && condition.contains("雨");
    }
}
