package com.travel.agent.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** 天气数据源配置：默认模拟数据（开箱即用、演示确定），显式开启后走 Open-Meteo 真实预报 */
@Data
@Component
@ConfigurationProperties(prefix = "travel.weather")
public class WeatherProperties {

    /** true 时接 Open-Meteo 真实天气（免费无需 Key）；失败自动降级模拟 */
    private boolean real = false;
}
