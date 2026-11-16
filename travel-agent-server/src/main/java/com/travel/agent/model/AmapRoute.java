package com.travel.agent.model;

/** 高德真实路径规划结果：距离（米）与时长（秒），取首条推荐路径 */
public record AmapRoute(
        double distanceMeters,
        double durationSeconds
) {
}