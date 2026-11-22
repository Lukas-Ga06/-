package com.travel.agent.model;

import java.util.List;

/** 城市目录 */
public record CityCatalog(
        String name,
        List<Attraction> attractions,
        List<Hotel> hotels,
        List<Restaurant> restaurants
) {
}
