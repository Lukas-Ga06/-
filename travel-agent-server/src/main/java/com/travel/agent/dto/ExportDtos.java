package com.travel.agent.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 账号级数据导出（{@code GET /api/auth/me/export}）的响应契约。
 *
 * <p>只含本人可见的结构化数据：账号资料、行程与行程项、记账、备忘、日程、会话与消息、
 * 个人知识库文档，以及照片清单。照片<b>不导出二进制</b>，只给 {@code /api/photos/{id}/raw}
 * 访问地址（仍需本人凭证），因此导出文件体积与账号数据量无关，可整段放进内存。
 *
 * <p>不导出的内部数据：{@code agent_run} / {@code agent_task} / {@code tool_call_trace}
 * 只存执行状态与参数摘要（哈希），对话正文已在 {@link Conversation#getMessages()} 里；
 * 这些表在账号注销时一并删除，但属性上属于运行日志而非用户创作内容。
 *
 * <p>表格类数据用 {@link Map} 表示，键名固定为 camelCase（见 {@code AccountExportService} 的
 * {@code *_KEYS} 常量），值中的时间一律是 ISO-8601 字符串。这样导出文件的字段名不随数据库
 * 列名（snake_case）或方言变化。
 */
public class ExportDtos {

    /** 导出格式版本：字段增删或语义变化时递增，消费方可据此判断兼容性。 */
    public static final String FORMAT_VERSION = "1";

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class AccountExport {
        /** 见 {@link ExportDtos#FORMAT_VERSION} */
        private String formatVersion;
        /** 导出时刻（带时区偏移，便于跨时区核对） */
        private String exportedAt;
        private Account account;
        /** 各集合的条目数，便于消费方先看规模再用数组 */
        private Counts counts;
        private List<Map<String, Object>> trips;
        private List<Map<String, Object>> itineraryItems;
        private List<Map<String, Object>> expenses;
        private List<Map<String, Object>> memos;
        private List<Map<String, Object>> schedules;
        private List<Conversation> conversations;
        private List<Map<String, Object>> knowledgeDocuments;
        private List<PhotoDtos.Photo> photos;
    }

    /** 账号资料：永远不含密码哈希、token 版本等内部字段。 */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Account {
        private Long id;
        private String username;
        private String nickname;
        private String createdAt;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Counts {
        private int trips;
        private int itineraryItems;
        private int expenses;
        private int memos;
        private int schedules;
        private int conversations;
        private int messages;
        private int knowledgeDocuments;
        private int photos;
    }

    /** 一次会话及其全部消息（按发生顺序），比把两张表平铺开更好还原上下文。 */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Conversation {
        private String conversationId;
        private String title;
        private String createdAt;
        private String updatedAt;
        private List<Message> messages;

        public List<Message> getMessages() {
            return messages == null ? new ArrayList<>() : new ArrayList<>(messages);
        }

        public void setMessages(List<Message> messages) {
            this.messages = messages == null ? new ArrayList<>() : new ArrayList<>(messages);
        }
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Message {
        private String role;
        private String content;
        private String intent;
        private String scope;
        private Long turnSeq;
        private String createdAt;
    }
}