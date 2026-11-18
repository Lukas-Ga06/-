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
        /** 审核结论（V2.2 FR-06）：结构化判定 + 重规划动作；只有规划/修改路径才带 */
        private PlanReview review;
        /**
         * 需求槽位追问（FR-01）：非空表示本轮没有开始规划，而是要用户补齐这些必填槽位
         * （destination / days / partySize）。会话历史据此标记「上一轮是追问」，实现跨轮补槽。
         */
        private List<String> missingSlots;
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

    /**
     * 审核结论（V2.2 FR-06）：让「审核发现问题」从一句警告变成可执行的事实。
     *
     * <p>{@code blocked=true} 表示有限重规划之后仍有 HIGH 级硬约束问题（预算装不下 / 天气换不到室内 /
     * 转场怎么挪都不够），确认门会要求用户显式接受风险（{@code acknowledgeIssues=true}）才提交。
     * {@code attempts/repairs} 说明引擎已经自动改过什么，前端可直接展示，不需要理解内部规则。
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class PlanReview {
        private boolean passed;
        private boolean blocked;
        private int attempts;
        private List<PlanIssue> issues;
        private List<String> repairs;
    }

    /** 单条审核问题：type 见 {@code ReflectAgent.PlanIssue}，severity 为 HIGH / MEDIUM */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class PlanIssue {
        private String type;
        private String severity;
        private String message;
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
         * 确认前是否必须显式接受风险（V2.2 FR-06）：审核仍有未解决 HIGH 问题时为 true。
         * 前端据此要求勾选「我已了解风险」，后端据此校验 acknowledgeIssues。
         */
        private boolean requiresAcknowledgement;
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
