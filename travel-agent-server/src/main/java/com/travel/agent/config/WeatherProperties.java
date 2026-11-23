package com.travel.agent.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 天气数据源配置。
 *
 * <p>产品路径<b>没有模拟天气</b>：默认只走 Open-Meteo 真实预报（免费、无需 Key），
 * 拿不到就是「不可用」，依赖天气的行程修改会被明确拒绝，而不是用模拟数据把流程演下去。
 * {@code simulation=true} 只用于测试/离线演示，且状态接口会如实自报。
 */
@Data
@Component
@ConfigurationProperties(prefix = "travel.weather")
public class WeatherProperties {

    /** true 时返回确定性模拟天气（仅测试/离线演示；产品路径不得开启，状态接口会标注「模拟天气」） */
    private boolean simulation = false;

    /** 失败负缓存秒数：离线时一次三日游审核会查 3 次天气，不能让每次都等连接超时；0=不缓存失败 */
    private long failureCacheSeconds = 60;

    /** Open-Meteo 地理编码地址（可配置以便测试指向 WireMock） */
    private String geocodeBaseUrl = "https://geocoding-api.open-meteo.com/v1/search";

    /** Open-Meteo 预报地址（可配置以便测试指向 WireMock） */
    private String forecastBaseUrl = "https://api.open-meteo.com/v1/forecast";
}