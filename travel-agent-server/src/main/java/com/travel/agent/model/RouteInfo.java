package com.travel.agent.model;

/**
 * 路线计算结果。
 *
 * <p>{@code source}（V2.2 FR-05）如实标注 distance 的来源：
 * {@code amap} = 真实路径规划，{@code estimated} = 本地 haversine 估算。
 * {@code taxiCost} 始终为本地估算（未接入计价 Provider），调用方不得把它当作实时价格展示。
 */
public record RouteInfo(
        double distanceKm,
        int walkMinutes,
        int driveMinutes,
        double taxiCost,
        String source
) {
    /** 是否为真实路径数据 */
    public boolean real() {
        return "amap".equals(source);
    }
}