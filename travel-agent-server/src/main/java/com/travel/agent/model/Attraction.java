package com.travel.agent.model;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/** 景点（来自本地目录数据，可替换为真实 POI 服务） */
public record Attraction(
        String id,
        String name,
        String venue,          // indoor / outdoor
        List<String> tags,
        double lat,
        double lng,
        double ticket,
        double durationHours,
        double rating,
        String description
) {
    /** 标签追加 室内/室外 标记并去重 */
    public List<String> tagsWithVenue() {
        LinkedHashSet<String> merged = new LinkedHashSet<>(tags);
        merged.add("indoor".equals(venue) ? "室内" : "室外");
        return new ArrayList<>(merged);
    }
}
