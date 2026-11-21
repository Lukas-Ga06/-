package com.travel.agent.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

public class ChatDtos {

    /**
     * 最近会话摘要（体验方案 §8.6）：只给「继续哪一段对话」所需的最少信息。
     * title 取该会话首条用户消息的前若干字；没有用户消息时回落为「新会话」。
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ConversationSummary {
        private String conversationId;
        private String title;
        /** 最后一条消息时间（ISO-8601 本地时间）；前端按它排序/显示「刚刚 / 昨天」 */
        private String lastMessageAt;
        private long messageCount;
    }

    /** 会话内的一条历史消息（只读，用于「继续对话」时回填聊天记录） */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ConversationMessageView {
        private String role;
        private String content;
        private String createdAt;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ChatRequest {
        @NotBlank(message = "不能为空")
        @Size(max = 500, message = "长度不能超过 500 个字符")
        private String message;
        private Long tripId;
        private Integer currentDay;
        private SelectedElement selectedElement;
        private MapLocation mapCenter;
        /**
         * 会话标识：前端生成（UUID），为空表示单轮对话。
         * 只允许 UUID 安全字符，且服务端所有查询都以 userId 隔离，不信任该值。
         */
        @Size(max = 64, message = "长度不能超过 64 个字符")
        @Pattern(regexp = "[A-Za-z0-9_-]*", message = "格式不正确")
        private String conversationId;
        /**
         * 幂等标识（P0-01）：客户端为每次命令生成，重试时复用。
         * 服务端按 (userId, requestId) 去重，保证刷新 / 重连 / 重放只写入一次；
         * 为空表示不做幂等（旧客户端行为不变）。
         */
        @Size(max = 64, message = "长度不能超过 64 个字符")
        @Pattern(regexp = "[A-Za-z0-9_-]*", message = "格式不正确")
        private String requestId;
    }

    /**
     * 结构化规划表单的 POST 流式请求：与 {@link ChatRequest} 字段一致，
     * 但 message 放宽到 2 万字符 —— 表单会把参考攻略正文（最多 3 篇共 1.5 万字）
     * 编译进需求文本，URL 查询参数（GET /stream 的 EventSource 限制）放不下这个量级。
     */
    @Data
    @NoArgsConstructor
    public static class PlanStreamRequest {
        @NotBlank(message = "不能为空")
        @Size(max = 20000, message = "长度不能超过 20000 个字符")
        private String message;
        private Long tripId;
        private Integer currentDay;
        private SelectedElement selectedElement;
        private MapLocation mapCenter;
        @Size(max = 64, message = "长度不能超过 64 个字符")
        @Pattern(regexp = "[A-Za-z0-9_-]*", message = "格式不正确")
        private String conversationId;
        /** 幂等标识（P0-01），语义与 {@link ChatRequest#getRequestId()} 一致 */
        @Size(max = 64, message = "长度不能超过 64 个字符")
        @Pattern(regexp = "[A-Za-z0-9_-]*", message = "格式不正确")
        private String requestId;
    }

    /** 多模态上下文：用户点击选中的元素，解决「这个换成类似的」中的指代问题 */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SelectedElement {
        private String type;   // attraction / hotel / restaurant / itinerary_item
        private Long id;
        private String title;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class MapLocation {
        private Double lat;
        private Double lng;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ConfirmRequest {
        @NotBlank(message = "不能为空")
        private String planId;
        /**
         * 方案内容指纹（V2.2 FR-08，可选）：确认弹窗回传的 SHA-256。
         * 携带时后端校验「用户确认的就是看到的那份内容」；不传走既有校验（向后兼容）。
         */
        @Size(max = 64, message = "长度不能超过 64 个字符")
        private String contentHash;
        /**
         * 是否已明确接受未解决的高风险问题（V2.2 FR-06，可选）。
         * 只有审核结论 blocked=true 的方案才需要它；缺省/false 时行为与旧前端完全一致。
         */
        private Boolean acknowledgeIssues;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class UndoRequest {
        @NotNull(message = "不能为空")
        private Long tripId;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ClearConversationRequest {
        @NotBlank(message = "不能为空")
        @Size(max = 64, message = "长度不能超过 64 个字符")
        @Pattern(regexp = "[A-Za-z0-9_-]+", message = "格式不正确")
        private String conversationId;
    }
}
