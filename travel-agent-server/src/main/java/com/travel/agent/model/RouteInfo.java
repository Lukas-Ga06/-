package com.travel.agent.model;

/** 路线计算结果 */
public record RouteInfo(
        double distanceKm,
        int walkMinutes,
        int driveMinutes,
        double taxiCost
) {
}
