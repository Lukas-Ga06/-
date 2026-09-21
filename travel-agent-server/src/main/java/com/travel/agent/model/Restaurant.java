package com.travel.agent.model;

/** 餐厅 */
public record Restaurant(
        String id,
        String name,
        String cuisine,
        double avgCost,
        double rating,
        double lat,
        double lng,
        String description
) {
}
