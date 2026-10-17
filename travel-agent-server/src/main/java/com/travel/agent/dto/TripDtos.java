package com.travel.agent.dto;

import com.travel.agent.entity.Trip;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
public class TripDtos {

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class TripStatusRequest {
        @jakarta.validation.constraints.NotBlank(message = "不能为空")
        private String status;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class TripRequest {
        @Size(max = 200, message = "长度不能超过 200 个字符")
        private String name;
        @Size(max = 200, message = "长度不能超过 200 个字符")
        private String destination;
        @Pattern(regexp = "^\\d{4}-\\d{2}-\\d{2}$", message = "格式需为 yyyy-MM-dd")
        private String startDate;  // yyyy-MM-dd
        @Pattern(regexp = "^\\d{4}-\\d{2}-\\d{2}$", message = "格式需为 yyyy-MM-dd")
        private String endDate;    // yyyy-MM-dd
        @DecimalMin(value = "0", message = "不能为负数")
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
        /** 业务阶段：UPCOMING / ONGOING / PAST / CANCELLED */
        private String phase;
        /** 距出发天数（仅 UPCOMING 有值） */
        private Long daysUntilStart;
        private String createdAt;

        public static TripResponse from(Trip trip) {
            LocalDate today = LocalDate.now();
            String phase = trip.phase(today);
            return TripResponse.builder()
                    .id(trip.getId())
                    .name(trip.getName())
                    .destination(trip.getDestination())
                    .startDate(trip.getStartDate() == null ? null : trip.getStartDate().toString())
                    .endDate(trip.getEndDate() == null ? null : trip.getEndDate().toString())
                    .budget(trip.getBudget())
                    .status(trip.getStatus())
                    .phase(phase)
                    .daysUntilStart("UPCOMING".equals(phase) && trip.getStartDate() != null
                            ? ChronoUnit.DAYS.between(today, trip.getStartDate()) : null)
                    .createdAt(trip.getCreatedAt() == null ? null : trip.getCreatedAt().toLocalDate().toString())
                    .build();
        }
    }
}
