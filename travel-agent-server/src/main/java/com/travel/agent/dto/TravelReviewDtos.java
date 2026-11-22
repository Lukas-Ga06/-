package com.travel.agent.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * 旅行回顾（P1）：行后聚合视图。
 *
 * <p>口径分三层，绝不混用：
 * <ul>
 *   <li>{@code plannedPlaces}：计划里的地点（来自 itinerary_item 全部条目）；</li>
 *   <li>{@code visitedPlaces}：实际到访（只认 visitedAt，不把计划时间当「去过」）；</li>
 *   <li>{@code actualRoute}：按到访时间排序的实际路线点（仅到访地点，无坐标则跳过）。</li>
 * </ul>
 *
 * <p>{@code completionRate} = 已完成条目 / 总条目（0..1，跳过不计入完成）；
 * {@code plannedCost} 为计划成本、{@code actualCost} 为实际记账，两者不互相覆盖。
 */
public class TravelReviewDtos {

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class TripReview {
        private Long tripId;
        private String name;
        private String destination;
        private String startDate;
        private String endDate;
        private Integer totalDays;
        /** UPCOMING / ONGOING / PAST / CANCELLED（由日期派生，与足迹口径一致） */
        private String phase;
        private int totalItems;
        private int completedItems;
        private int skippedItems;
        private int inProgressItems;
        private int pendingItems;
        private double completionRate;
        private BigDecimal plannedCost;
        private BigDecimal actualCost;
        private BigDecimal budget;
        /** actualCost - plannedCost */
        private BigDecimal variance;
        private boolean overBudget;
        private List<Place> plannedPlaces;
        private List<Place> visitedPlaces;
        /** 实际路线：按 visitedAt 排序的到访点（只含坐标齐全的景点/餐厅/酒店） */
        private List<RouteStop> actualRoute;
        private int photoCount;
        private List<PhotoDtos.Photo> photos;
        private List<EventView> events;
        private List<HistoryView> modifications;
        private int memoCount;
        private int scheduleCount;
        /** 旅行总结：LLM 可用时由真实数据生成，否则为确定性规则文案（不编造事实） */
        private String aiSummary;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Place {
        private Long itemId;
        private Integer dayIndex;
        private String itemType;
        private String title;
        private String status;
        /** 计划开始时间（ISO） */
        private String plannedAt;
        /** 实际到访时间（ISO）；未到访为 null */
        private String visitedAt;
        private Double latitude;
        private Double longitude;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class RouteStop {
        private Long itemId;
        private String title;
        private String visitedAt;
        private Double latitude;
        private Double longitude;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class EventView {
        private Long id;
        private Long itemId;
        private String type;
        private String source;
        private String summary;
        private String occurredAt;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class HistoryView {
        private Long id;
        private String operationType;
        private String reason;
        private String at;
    }

    /** 空列表兜底：前端可无条件遍历 */
    public static <T> List<T> orEmpty(List<T> list) {
        return list == null ? new ArrayList<>() : list;
    }
}
