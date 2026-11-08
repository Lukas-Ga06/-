package com.travel.agent.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

public class ChatDtos {

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
