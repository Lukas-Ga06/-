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
        /** 确认提交后的跨模块同步结果（FR-09）：只有 confirm 成功路径才带 */
        private ExecutionSummary execution;

        /** 追加一行消息（null 安全）：确认后同步结果摘要等场景使用 */
        public void appendMessage(String line) {
            if (line == null || line.isBlank()) {
                return;
            }
            this.message = this.message == null || this.message.isBlank() ? line.trim() : this.message + line;
        }
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
        /**
         * 方案内容指纹（V2.2 FR-08）：plan_json 的 SHA-256。
         * 确认请求可携带同一指纹回传，后端校验一致才放行——
         * 用户确认的必须就是他看到的那份内容。旧前端不传时退化为既有校验。
         */
        private String contentHash;
    }

    /**
     * 确认后跨模块同步结果（V2.2 FR-09）：
     * 日程 / 预算 / 足迹逐模块状态，失败模块可单独重试（不重复创建成功项）。
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ExecutionSummary {
        private String executionId;
        private String planId;
        private Long tripId;
        private String status;      // SUCCEEDED / PARTIAL_FAILED / FAILED
        private List<ExecutionModule> modules;

        @Data
        @Builder
        @NoArgsConstructor
        @AllArgsConstructor
        public static class ExecutionModule {
            private String module;          // calendar / budget / footprint
            private String status;          // SUCCEEDED / FAILED
            private String detail;
            private String idempotencyKey;
            private Integer retryCount;
            private String error;
        }
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
