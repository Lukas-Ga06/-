package com.travel.agent.model;

/** 酒店 */
public record Hotel(
        String id,
        String name,
        boolean nearSubway,
        double pricePerNight,
        double rating,
        double lat,
        double lng,
        String description
) {
}
