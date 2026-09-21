package com.travel.agent.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * Agent 统一返回结构：前端无需理解 Agent 内部逻辑，
 * 只需按 focus / diff / confirmation / budget 渲染 GUI。
 */
public class AgentDtos {

    public static final String STATUS_COMPLETED = "COMPLETED";
    public static final String STATUS_NEED_CONFIRMATION = "NEED_CONFIRMATION";
    public static final String STATUS_FAILED = "FAILED";

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class AgentResult {
        private String planId;
        private Long tripId;
        private String message;
        private String intent;
        private String status;
        private AgentFocus focus;
        private PlanDiff diff;
        private BudgetSummary budget;
        private ConfirmationRequest confirmation;
        private List<String> warnings;
    }

    /** GUI Focus 协议：AI 回复后前端自动滚动时间轴 / 地图定位 */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class AgentFocus {
        private String type;      // itinerary / map / budget
        private Long tripId;
        private Integer day;
        private Long itemId;
        private Double latitude;
        private Double longitude;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class PlanDiff {
        private List<DiffChange> changes;
        private BigDecimal budgetBefore;
        private BigDecimal budgetAfter;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class DiffChange {
        private String type;      // ADD / REMOVE / MODIFY
        private Long itemId;
        private String itemType;
        private String title;
        private String detail;
        private BigDecimal cost;
        private Integer dayIndex;
        private String startTime;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ConfirmationRequest {
        private String planId;
        private String summary;
        private String riskLevel;   // LOW / MEDIUM / HIGH
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class BudgetSummary {
        private BigDecimal total;
        private BigDecimal previousTotal;
        private BigDecimal difference;
        private Map<String, BigDecimal> breakdown;
    }
}
