package com.travel.agent.model;

import java.util.List;

/**
 * 路线计算结果。
 *
 * <p>{@code source}（V2.2 FR-05）如实标注 distance 的来源：
 * {@code amap} = 真实路径规划，{@code estimated} = 本地 haversine 估算。
 * {@code taxiCost} 始终为本地估算（未接入计价 Provider），调用方不得把它当作实时价格展示。
 *
 * <p>{@code polyline} 是只在 {@code source=amap} 时可能非空的真实道路几何：
 * WGS-84 的 {@code [lat, lng]} 点序列（已抽稀，≤200 点）；估算结果一律为空列表，
 * **不给假点**——前端据此决定「按真实几何画线」还是「两点直连 + 虚线」（ADR-006/ADR-009）。
 */
public record RouteInfo(
        double distanceKm,
        int walkMinutes,
        int driveMinutes,
        double taxiCost,
        String source,
        List<double[]> polyline
) {
    public RouteInfo {
        polyline = polyline == null || polyline.isEmpty() ? List.of() : List.copyOf(polyline);
    }

    /** 兼容构造：无几何（估算口径 / 老调用方） */
    public RouteInfo(double distanceKm, int walkMinutes, int driveMinutes, double taxiCost, String source) {
        this(distanceKm, walkMinutes, driveMinutes, taxiCost, source, List.of());
    }

    /** 是否为真实路径数据 */
    public boolean real() {
        return "amap".equals(source);
    }
}
