package com.travel.agent.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 高德开放平台配置（{@code travel.amap.*}）。
 *
 * <p>注意区分两个凭据：
 * <ul>
 *   <li>{@code travel.amap.key}（环境变量 {@code AMAP_KEY}）：<b>Web 服务</b> Key，
 *       本后端 REST 调用用（POI / 路径 / 地理编码）；</li>
 *   <li>{@code AMAP_MAPS_API_KEY}：高德 MCP server 专用，只出现在 MCP 子进程 env，不经过本类。</li>
 * </ul>
 *
 * <p>留空即视为未配置：城市目录退回 LLM + OSM Nominatim，POI / 路径退回本地估算，功能仍完整。
 */
@Data
@Component
@ConfigurationProperties(prefix = "travel.amap")
public class AmapProperties {

    /** 高德 Web 服务 Key（lbs.amap.com 申请，类型选「Web 服务」）；空串表示未配置 */
    private String key = "";

    /**
     * 高德 Web 服务根地址（不带结尾斜杠）。
     *
     * <p>生产默认 {@code https://restapi.amap.com}，与历史硬编码一致；可配置的唯一目的是让
     * 降级测试把它指向本地 WireMock（见 {@code resilience/AmapResilienceTest}），
     * 以及私有化代理场景。路径（{@code /v3/geocode/geo}）仍由客户端拼装。
     */
    private String baseUrl = "https://restapi.amap.com";

    /**
     * 建连超时（毫秒）。默认 2000：高德是规划主链路上的同步依赖，宁可快速失败由调用方降级，
     * 也不要让一次建连把请求线程挂住数秒。
     */
    private int connectTimeoutMs = 2000;

    /**
     * 读超时（毫秒）。默认 5000：与历史硬编码一致；批量地理编码单批最多 10 个地址，
     * 正常响应在百毫秒级，5 秒足够，超过即视为不可用并降级。
     */
    private int readTimeoutMs = 5000;

    /** 是否配置了可用的 Key（空/空白一律视为未配置） */
    public boolean usable() {
        return key != null && !key.isBlank();
    }
}