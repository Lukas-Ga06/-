package com.travel.agent.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/** 旅行备忘/待办：可关联行程（tripId 为空表示个人待办） */
@Entity
@Table(name = "memo", indexes = {
        @Index(name = "idx_memo_user", columnList = "user_id")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Memo {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String content;

    @Column(nullable = false)
    private boolean done;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    /** 关联行程（P1，可空）：旅行待办可按 tripId 聚合 */
    @Column(name = "trip_id")
    private Long tripId;

    /** 关联行程项（可空） */
    @Column(name = "itinerary_item_id")
    private Long itineraryItemId;

    /** 来源：MANUAL / AGENT */
    @Column(length = 30)
    private String source;

    /** 行程第几天（1-based），仅当 tripId 非空时有意义 */
    @Column(name = "travel_day")
    private Integer travelDay;

    @PrePersist
    public void prePersist() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
    }
}
