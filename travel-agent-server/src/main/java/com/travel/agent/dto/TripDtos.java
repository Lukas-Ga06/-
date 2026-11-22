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
import java.util.List;

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
        /** 同行人数（FR-01）：1..20，缺省 1；预算的房数/餐费/门票口径随它变化 */
        @jakarta.validation.constraints.Min(value = 1, message = "不能小于 1")
        @jakarta.validation.constraints.Max(value = 20, message = "不能大于 20")
        private Integer partySize;
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
        /**
         * 足迹口径（P1）：phase = UPCOMING / ONGOING / PAST / CANCELLED。
         * 前端据此区分「计划要去」与「真的去过」，绝不把未来行程渲染成已到访。
         */
        private String phase;
        /** 计划地点数（有条目的目的地数量） */
        private Integer plannedPlaces;
        /** 实际到访数（只认 visitedAt） */
        private Integer visitedPlaces;
        /** 是否有实际到访（visitedPlaces &gt; 0） */
        private boolean visited;
        /** 实际到访路线（按到访时间排序；无坐标地点跳过） */
        private List<RouteStop> actualRoute;
    }

    /** 足迹实际路线点（WGS-84，纬度在前，与全仓坐标契约一致） */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class RouteStop {
        private Long itemId;
        private String title;
        private String visitedAt;
        private Double lat;
        private Double lng;
    }

    /** 旅行事件（P1）：天气突变 / 拥堵 / 闭馆 / 打卡 / Agent 调整 */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class TripEventResponse {
        private Long id;
        private Long itemId;
        private String type;
        private String source;
        private String summary;
        private String occurredAt;

        public static TripEventResponse from(com.travel.agent.entity.TripEvent event) {
            return TripEventResponse.builder()
                    .id(event.getId())
                    .itemId(event.getItineraryItemId())
                    .type(event.getEventType())
                    .source(event.getSource())
                    .summary(event.getSummary())
                    .occurredAt(event.getOccurredAt() == null ? null : event.getOccurredAt().toString())
                    .build();
        }
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
        /** 同行人数（FR-01）：存量行程为 1 */
        private Integer partySize;
        /** 业务阶段：UPCOMING / ONGOING / PAST / CANCELLED */
        private String phase;
        /** 距出发天数（仅 UPCOMING 有值） */
        private Long daysUntilStart;
        private String createdAt;
        /**
         * 乐观锁版本（阶段 D 冲突提示）：同一行程被别的标签页/设备改过后版本递增，
         * 前端回到页面时对比它来发现「此行程已在其他位置更新」。
         * 存量数据为 0；不参与任何客户端写请求，只读。
         */
        private Long version;

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
                    .partySize(trip.getPartySize() == null ? 1 : trip.getPartySize())
                    .phase(phase)
                    .daysUntilStart("UPCOMING".equals(phase) && trip.getStartDate() != null
                            ? ChronoUnit.DAYS.between(today, trip.getStartDate()) : null)
                    .createdAt(trip.getCreatedAt() == null ? null : trip.getCreatedAt().toLocalDate().toString())
                    .version(trip.getVersion())
                    .build();
        }
    }
}
