package com.travel.agent.dto;

import com.travel.agent.entity.ItineraryItem;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
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

    /**
     * 每日相邻行程点之间的路线段（V2.2 FR-07）。
     *
     * <p>{@code source} 标注距离来源：{@code amap} = 真实路径规划，{@code estimated} = 本地估算；
     * {@code taxiCost} 始终为估算（未接计价 Provider），前端不得显示成实时价格。
     *
     * <p>{@code polyline}（V2.2 验收后补）：真实道路几何，JSON 形态 {@code [[lat, lng], ...]}，
     * **WGS-84、纬度在前**（与全仓坐标契约一致，前端按 amap 类型渲染时自行转 GCJ-02）。
     * 无几何时为空数组 {@code []}，前端回退「两点直连 + 虚线」；**后端绝不生成假点**（ADR-006/ADR-009）。
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class RouteLegDTO {
        private Integer dayIndex;
        /** 当天内的段序号（从 1 开始） */
        private Integer sequence;
        private Long fromItemId;
        private Long toItemId;
        private String fromTitle;
        private String toTitle;
        private Double fromLatitude;
        private Double fromLongitude;
        private Double toLatitude;
        private Double toLongitude;
        private Double distanceKm;
        private Integer walkMinutes;
        private Integer driveMinutes;
        private Double taxiCost;
        private String source;   // amap / estimated
        /** 真实道路几何：WGS-84 [lat, lng] 点序列（≤200 点）；无几何时为空数组 */
        private List<double[]> polyline;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class AddItemRequest {
        private Integer dayIndex;
        private String itemType;
        @NotBlank(message = "不能为空")
        @Size(max = 200, message = "长度不能超过 200 个字符")
        private String title;
        private String startTime;   // HH:mm
        private String endTime;     // HH:mm
        @DecimalMin(value = "0", message = "不能为负数")
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
        @Size(max = 200, message = "长度不能超过 200 个字符")
        private String title;
        private String startTime;
        private String endTime;
        @DecimalMin(value = "0", message = "不能为负数")
        private BigDecimal cost;
        private Double latitude;
        private Double longitude;
        private List<String> tags;
        private String description;
    }
}
