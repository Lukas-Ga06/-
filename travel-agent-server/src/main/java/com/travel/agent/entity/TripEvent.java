package com.travel.agent.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * 旅行事件（P1）：行程中发生的、需要可审计的事实。
 *
 * <p>为什么要单独建表：行程项只表达「计划」，天气突变、拥堵、闭馆、用户打卡、
 * Agent 局部调整都是「发生了什么」。把它们混进 itinerary_item 会污染计划数据，
 * 也无法在回顾里还原修改历史。本表只追加、不参与规划器的事实视图。
 *
 * <p>来源口径（{@code source}）与工具结果一致：{@code weather} / {@code amap} /
 * {@code user} / {@code agent} / {@code system}，前端据此标注证据来源。
 */
@Entity
@Table(name = "trip_event", indexes = {
        @Index(name = "idx_trip_event_trip", columnList = "trip_id"),
        @Index(name = "idx_trip_event_item", columnList = "itinerary_item_id"),
        @Index(name = "idx_trip_event_occurred", columnList = "trip_id, occurred_at")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TripEvent {

    /** 天气突变（降雨/高温导致局部重规划） */
    public static final String TYPE_WEATHER = "WEATHER";
    /** 拥堵/交通异常 */
    public static final String TYPE_CONGESTION = "CONGESTION";
    /** 闭馆/临时不可用 */
    public static final String TYPE_CLOSURE = "CLOSURE";
    /** 用户打卡（实际到访/完成） */
    public static final String TYPE_CHECK_IN = "CHECK_IN";
    /** Agent 局部调整（天气替换、改期等） */
    public static final String TYPE_AGENT_ADJUST = "AGENT_ADJUST";
    /** 手工修改行程项 */
    public static final String TYPE_MANUAL_EDIT = "MANUAL_EDIT";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "trip_id", nullable = false)
    private Long tripId;

    /** 关联行程项（可空：天气突变这类事件可能只作用于某一天） */
    @Column(name = "itinerary_item_id")
    private Long itineraryItemId;

    @Column(name = "event_type", length = 40, nullable = false)
    private String eventType;

    /** 事件来源：weather / amap / user / agent / system */
    @Column(length = 30)
    private String source;

    /** 单行摘要（回顾页直接展示） */
    @Column(length = 500)
    private String summary;

    /** 结构化细节（JSON 字符串；不参与检索，仅供审计） */
    @Column(columnDefinition = "TEXT")
    private String detail;

    @Column(name = "occurred_at", nullable = false)
    private LocalDateTime occurredAt;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @PrePersist
    public void prePersist() {
        if (occurredAt == null) {
            occurredAt = LocalDateTime.now();
        }
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
    }
}
