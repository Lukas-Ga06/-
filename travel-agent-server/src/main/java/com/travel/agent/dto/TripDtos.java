package com.travel.agent.dto;

import com.travel.agent.entity.Trip;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
public class TripDtos {

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class TripRequest {
        private String name;
        private String destination;
        private String startDate;  // yyyy-MM-dd
        private String endDate;    // yyyy-MM-dd
        private BigDecimal budget;
    }

    /** 首页足迹地图点（坐标可能为 null：图集/联网均未命中） */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class TripMapPoint {
        private Long tripId;
        private String name;
        private String destination;
        private String status;
        private String startDate;
        private String endDate;
        private BigDecimal budget;
        private Double lat;
        private Double lng;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class TripResponse {
        private Long id;
        private String name;
        private String destination;
        private String startDate;
        private String endDate;
        private BigDecimal budget;
        private String status;
        private String createdAt;

        public static TripResponse from(Trip trip) {
            return TripResponse.builder()
                    .id(trip.getId())
                    .name(trip.getName())
                    .destination(trip.getDestination())
                    .startDate(trip.getStartDate() == null ? null : trip.getStartDate().toString())
                    .endDate(trip.getEndDate() == null ? null : trip.getEndDate().toString())
                    .budget(trip.getBudget())
                    .status(trip.getStatus())
                    .createdAt(trip.getCreatedAt() == null ? null : trip.getCreatedAt().toLocalDate().toString())
                    .build();
        }
    }
}
