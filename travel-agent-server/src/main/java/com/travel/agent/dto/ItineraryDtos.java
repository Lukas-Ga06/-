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
        /** 执行状态：PENDING / IN_PROGRESS / COMPLETED / SKIPPED（P1） */
        private String status;
        /** 实际开始/完成/到访时间（ISO）；未发生时为 null，绝不拿计划时间冒充实际时间 */
        private String startedAt;
        private String completedAt;
        private String visitedAt;
        /**
         * 参考价（ADR-006：估算必须自报家门）。{@code cost} 为 null 或 0 时该字段为 null；
         * 由 {@code PriceReferenceService} 构建，纯本地推算，绝非实时报价。
         */
        private PriceDTO price;

        /** 不带价格的映射：仅用于内部快照/还原（价格在读取时按当前行程重新推算，不落库） */
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
                    .status(e.getStatus() == null ? ItineraryItem.STATUS_PENDING : e.getStatus())
                    .startedAt(e.getStartedAt() == null ? null : e.getStartedAt().toString())
                    .completedAt(e.getCompletedAt() == null ? null : e.getCompletedAt().toString())
                    .visitedAt(e.getVisitedAt() == null ? null : e.getVisitedAt().toString())
                    .build();
        }

        /** 带参考价的映射：所有对外响应（列表 / 新增 / 修改）都必须走这个入口 */
        public static ItineraryItemDTO from(ItineraryItem e, PriceDTO price) {
            ItineraryItemDTO dto = from(e);
            dto.setPrice(price);
            return dto;
        }
    }

    /**
     * 价格自报结构（前端契约，字段名/取值已锁定，不要改）。
     *
     * <p>{@code kind} 固定 {@code reference}、{@code source} 固定 {@code local-estimate}：
     * 当前没有接入任何实时房价/库存源，估算不能伪装成实时（ADR-006）。
     * {@code links} 无可用项时是空数组，不是 null——前端可无条件遍历。
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class PriceDTO {
        private BigDecimal amount;
        private String currency;    // CNY
        private String kind;        // reference
        private String label;       // 参考价
        private String source;      // local-estimate
        private String sourceLabel; // 本地估算
        private String disclaimer;
        private List<PriceLinkDTO> links;
    }

    /** 价格深链：{@code kind} 取 {@code price}（查价）/ {@code map}（地图查看） */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class PriceLinkDTO {
        private String provider; // ctrip / amap
        private String label;
        private String url;
        private String kind;
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

    /** 行程项状态推进（P1）：PENDING / IN_PROGRESS / COMPLETED / SKIPPED */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class UpdateStatusRequest {
        @NotBlank(message = "不能为空")
        @Size(max = 20, message = "长度不能超过 20 个字符")
        private String status;
    }
}
