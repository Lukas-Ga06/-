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

    /** 是否配置了可用的 Key（空/空白一律视为未配置） */
    public boolean usable() {
        return key != null && !key.isBlank();
    }
}