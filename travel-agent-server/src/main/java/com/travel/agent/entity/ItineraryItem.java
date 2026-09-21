package com.travel.agent.entity;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 行程条目。tags 以逗号分隔存储（例如 "室内,文化,亲子"），
 * 其中 "室内"/"室外" 标记用于天气驱动的行程调整。
 */
@Entity
@Table(name = "itinerary_item")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ItineraryItem {

    public static final String TYPE_HOTEL = "hotel";
    public static final String TYPE_ATTRACTION = "attraction";
    public static final String TYPE_RESTAURANT = "restaurant";
    public static final String TYPE_TRANSPORT = "transport";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "trip_id", nullable = false)
    private Long tripId;

    @Column(name = "day_index", nullable = false)
    private Integer dayIndex;

    @Column(name = "item_type", length = 30, nullable = false)
    private String itemType;

    @Column(length = 200, nullable = false)
    private String title;

    @Column(name = "start_time")
    private LocalDateTime startTime;

    @Column(name = "end_time")
    private LocalDateTime endTime;

    @Column(name = "latitude")
    private Double latitude;

    @Column(name = "longitude")
    private Double longitude;

    @Column(precision = 10, scale = 2)
    private BigDecimal cost;

    @Column(length = 200)
    private String tags;

    @Column(columnDefinition = "TEXT")
    private String description;

    public boolean hasTag(String tag) {
        return tags != null && List.of(tags.split(",")).contains(tag);
    }

    public boolean isOutdoor() {
        return hasTag("室外");
    }

    public boolean isIndoor() {
        return hasTag("室内");
    }
}
