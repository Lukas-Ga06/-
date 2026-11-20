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
@Table(name = "itinerary_item", indexes = {
        @Index(name = "idx_itinerary_trip", columnList = "trip_id")
})
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

    /** 行程项执行状态（P1）：未开始 / 进行中 / 已完成 / 已跳过 */
    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_IN_PROGRESS = "IN_PROGRESS";
    public static final String STATUS_COMPLETED = "COMPLETED";
    public static final String STATUS_SKIPPED = "SKIPPED";

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

    /**
     * 执行状态（P1）：默认 PENDING。
     * 行中重规划只允许自动调整 {@link #STATUS_PENDING} 的条目；COMPLETED / SKIPPED 是不可覆盖的既成事实。
     */
    @Column(length = 20, nullable = false, columnDefinition = "varchar(20) default 'PENDING'")
    @Builder.Default
    private String status = STATUS_PENDING;

    /** 实际开始时间（用户点击「开始」或到达时写） */
    @Column(name = "started_at")
    private LocalDateTime startedAt;

    /** 实际完成时间 */
    @Column(name = "completed_at")
    private LocalDateTime completedAt;

    /** 实际到访时间（打卡）：足迹与回顾以它为准，计划时间不算「去过」 */
    @Column(name = "visited_at")
    private LocalDateTime visitedAt;

    public boolean hasTag(String tag) {
        return tags != null && List.of(tags.split(",")).contains(tag);
    }

    public boolean isOutdoor() {
        return hasTag("室外");
    }

    public boolean isIndoor() {
        return hasTag("室内");
    }

    /** 未开始：唯一允许被自动重规划改动的状态 */
    public boolean isPending() {
        return status == null || STATUS_PENDING.equals(status);
    }

    /** 已开始或已结束：重规划不得自动覆盖（用户仍需显式确认手工修改） */
    public boolean isStarted() {
        return STATUS_IN_PROGRESS.equals(status);
    }

    /** 既成事实：已完成或已跳过 */
    public boolean isFinished() {
        return STATUS_COMPLETED.equals(status) || STATUS_SKIPPED.equals(status);
    }
}
