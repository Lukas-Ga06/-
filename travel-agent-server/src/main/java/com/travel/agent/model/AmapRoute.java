package com.travel.agent.model;

import java.util.List;

/**
 * 高德真实路径规划结果：距离（米）、时长（秒）与路径几何。
 *
 * <p>{@code polyline} 是 **WGS-84** 的 {@code [lat, lng]} 点序列：高德返回的
 * {@code route.paths[0].steps[].polyline}（GCJ-02）已在本层转成 WGS-84 并做过折线抽稀，
 * 前端直接按真实道路画线即可（ADR-009 的「不伪造路径几何」从「只画端点」升级为「画真实几何」）。
 *
 * <p>**无几何时为空列表**：老响应、只有 distance/duration 的响应、MCP 通道不带 polyline、
 * 或几何解析失败都落成空列表，由调用方回退「两点直连」——绝不用假点填充（ADR-006）。
 */
public record AmapRoute(
        double distanceMeters,
        double durationSeconds,
        List<double[]> polyline
) {
    public AmapRoute {
        polyline = polyline == null || polyline.isEmpty() ? List.of() : List.copyOf(polyline);
    }

    /** 兼容构造：只有距离/时长、没有几何（老调用方与老响应） */
    public AmapRoute(double distanceMeters, double durationSeconds) {
        this(distanceMeters, durationSeconds, List.of());
    }
}
