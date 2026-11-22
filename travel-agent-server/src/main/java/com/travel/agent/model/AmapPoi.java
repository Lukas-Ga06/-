package com.travel.agent.model;

/** 高德真实 POI（关键词搜索结果）；location 已解析并统一为 WGS-84 的 [lat, lng] */
public record AmapPoi(
        String id,
        String name,
        double lat,
        double lng,
        String address,
        String type,
        String tel
) {
}