package com.travel.agent.dto;

import com.travel.agent.entity.ItineraryItem;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class ItineraryDtos {

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ItineraryItemDTO {
        private Long id;
        private Long tripId;
        private Integer dayIndex;
        private String itemType;
        private String title;
        private String startTime;   // ISO: yyyy-MM-ddTHH:mm:ss
        private String endTime;
        private Double latitude;
        private Double longitude;
        private BigDecimal cost;
        private List<String> tags;
        private String description;

        public static ItineraryItemDTO from(ItineraryItem e) {
            return ItineraryItemDTO.builder()
                    .id(e.getId())
                    .tripId(e.getTripId())
                    .dayIndex(e.getDayIndex())
                    .itemType(e.getItemType())
                    .title(e.getTitle())
                    .startTime(e.getStartTime() == null ? null : e.getStartTime().toString())
                    .endTime(e.getEndTime() == null ? null : e.getEndTime().toString())
                    .latitude(e.getLatitude())
                    .longitude(e.getLongitude())
                    .cost(e.getCost())
                    .tags(e.getTags() == null ? new ArrayList<>() : Arrays.asList(e.getTags().split(",")))
                    .description(e.getDescription())
                    .build();
        }
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class AddItemRequest {
        private Integer dayIndex;
        private String itemType;
        private String title;
        private String startTime;   // HH:mm
        private String endTime;     // HH:mm
        private BigDecimal cost;
        private Double latitude;
        private Double longitude;
        private List<String> tags;
        private String description;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class UpdateItemRequest {
        private Integer dayIndex;
        private String itemType;
        private String title;
        private String startTime;
        private String endTime;
        private BigDecimal cost;
        private Double latitude;
        private Double longitude;
        private List<String> tags;
        private String description;
    }
}
