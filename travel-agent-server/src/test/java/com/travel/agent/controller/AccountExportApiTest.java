package com.travel.agent.controller;

import com.travel.agent.dto.TripDtos;
import com.travel.agent.service.TripService;
import com.travel.agent.support.IntegrationTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MvcResult;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code GET /api/auth/me/export} 的 HTTP 契约（数据可携带）：
 * 未登录 401、本人数据齐全且结构稳定、他人数据不出现、响应里永不出现密码哈希；
 * 另外覆盖"导出是只读操作"——导出后数据仍在，且可以重复导出。
 */
class AccountExportApiTest extends IntegrationTestSupport {

    @Autowired
    private TripService tripService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private long createTrip(long userId, String name) {
        TripDtos.TripRequest request = new TripDtos.TripRequest();
        request.setName(name);
        request.setDestination("杭州");
        request.setStartDate("2026-12-01");
        request.setEndDate("2026-12-03");
        request.setBudget(BigDecimal.valueOf(3000));
        request.setPartySize(2);
        return tripService.create(userId, request).getId();
    }

    /** 给账号铺一份"每类各一条"的数据，键名与 Not-Null 约束见各表迁移。 */
    private void seedAllKinds(long userId, long tripId) {
        jdbcTemplate.update(
                "INSERT INTO itinerary_item (trip_id, day_index, item_type, title) VALUES (?, ?, ?, ?)",
                tripId, 1, "ATTRACTION", "西湖漫步");
        jdbcTemplate.update(
                "INSERT INTO expense (trip_id, user_id, category, amount, spent_at) VALUES (?, ?, ?, ?, ?)",
                tripId, userId, "FOOD", BigDecimal.valueOf(128.50), java.sql.Date.valueOf("2026-12-01"));
        jdbcTemplate.update(
                "INSERT INTO memo (user_id, content, done) VALUES (?, ?, ?)",
                userId, "带伞", false);
        jdbcTemplate.update(
                "INSERT INTO schedule_item (user_id, title, sched_date, done) VALUES (?, ?, ?, ?)",
                userId, "高铁出发", java.sql.Date.valueOf("2026-12-01"), false);
        jdbcTemplate.update(
                "INSERT INTO conversation_meta (user_id, conversation_id, title) VALUES (?, ?, ?)",
                userId, "conv-export-1", "杭州三天");
        jdbcTemplate.update(
                "INSERT INTO conversation_message (user_id, conversation_id, role, content) VALUES (?, ?, ?, ?)",
                userId, "conv-export-1", "user", "帮我规划杭州三天");
        jdbcTemplate.update(
                "INSERT INTO conversation_message (user_id, conversation_id, role, content) VALUES (?, ?, ?, ?)",
                userId, "conv-export-1", "assistant", "已生成草案");
        jdbcTemplate.update(
                "INSERT INTO rag_document (user_id, title, doc_key) VALUES (?, ?, ?)",
                userId, "我的杭州笔记", "doc-export-1-" + userId);
        jdbcTemplate.update(
                "INSERT INTO trip_photo (trip_id, user_id, stored_name, original_name, content_type, size_bytes) "
                        + "VALUES (?, ?, ?, ?, ?, ?)",
                tripId, userId, "stored-1.jpg", "断桥.jpg", "image/jpeg", 2048L);
    }

    @Test
    @DisplayName("未登录导出返回 401，且不产生任何数据")
    void anonymousIsRejected() throws Exception {
        mockMvc.perform(get("/api/auth/me/export"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(401));
    }

    @Test
    @DisplayName("导出本人数据：结构齐全、计数正确、照片只给 URL、响应不含密码哈希")
    void exportContainsOwnData() throws Exception {
        Account account = registerNew();
        long tripId = createTrip(account.id(), "杭州三日");
        seedAllKinds(account.id(), tripId);

        MvcResult result = mockMvc.perform(get("/api/auth/me/export")
                        .header("Authorization", account.bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.formatVersion").value("1"))
                .andExpect(jsonPath("$.data.exportedAt").isNotEmpty())
                .andExpect(jsonPath("$.data.account.id").value(account.id()))
                .andExpect(jsonPath("$.data.account.username").value(account.username()))
                // 内部字段一律不出现：密码哈希、token 版本
                .andExpect(jsonPath("$.data.account.password").doesNotExist())
                .andExpect(jsonPath("$.data.account.tokenVersion").doesNotExist())
                .andExpect(jsonPath("$.data.counts.trips").value(1))
                .andExpect(jsonPath("$.data.counts.itineraryItems").value(1))
                .andExpect(jsonPath("$.data.counts.expenses").value(1))
                .andExpect(jsonPath("$.data.counts.memos").value(1))
                .andExpect(jsonPath("$.data.counts.schedules").value(1))
                .andExpect(jsonPath("$.data.counts.conversations").value(1))
                .andExpect(jsonPath("$.data.counts.messages").value(2))
                .andExpect(jsonPath("$.data.counts.knowledgeDocuments").value(1))
                .andExpect(jsonPath("$.data.counts.photos").value(1))
                // 行程与行程项：字段名是 camelCase，日期是 ISO 字符串
                .andExpect(jsonPath("$.data.trips[0].name").value("杭州三日"))
                .andExpect(jsonPath("$.data.trips[0].startDate").value("2026-12-01"))
                .andExpect(jsonPath("$.data.trips[0].partySize").value(2))
                .andExpect(jsonPath("$.data.itineraryItems[0].title").value("西湖漫步"))
                .andExpect(jsonPath("$.data.itineraryItems[0].dayIndex").value(1))
                // 记账金额仍是数字，不是字符串
                .andExpect(jsonPath("$.data.expenses[0].amount").value(128.50))
                // 会话把消息聚在同一个会话对象下，按发生顺序
                .andExpect(jsonPath("$.data.conversations[0].conversationId").value("conv-export-1"))
                .andExpect(jsonPath("$.data.conversations[0].messages[0].role").value("user"))
                .andExpect(jsonPath("$.data.conversations[0].messages[1].role").value("assistant"))
                // 照片只给清单与访问地址，不含二进制内容
                .andExpect(jsonPath("$.data.photos[0].originalName").value("断桥.jpg"))
                .andExpect(jsonPath("$.data.photos[0].sizeBytes").value(2048))
                .andExpect(jsonPath("$.data.photos[0].url").isNotEmpty())
                .andReturn();

        String body = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(body)
                .as("导出响应不得包含 BCrypt 密码哈希")
                .doesNotContain("$2a$")
                .doesNotContain("$2b$")
                .doesNotContain(PASSWORD);
    }

    @Test
    @DisplayName("只导出本人数据：另一个账号看不到别人的行程、消息与照片")
    void exportIsScopedToCurrentUser() throws Exception {
        Account owner = registerNew();
        long ownerTripId = createTrip(owner.id(), "只属于主人的行程");
        seedAllKinds(owner.id(), ownerTripId);

        Account bystander = registerNew();
        long bystanderTripId = createTrip(bystander.id(), "旁观者自己的行程");

        MvcResult result = mockMvc.perform(get("/api/auth/me/export")
                        .header("Authorization", bystander.bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.account.username").value(bystander.username()))
                .andExpect(jsonPath("$.data.counts.trips").value(1))
                .andExpect(jsonPath("$.data.counts.conversations").value(0))
                .andExpect(jsonPath("$.data.counts.photos").value(0))
                .andExpect(jsonPath("$.data.trips[0].id").value((int) bystanderTripId))
                .andExpect(jsonPath("$.data.trips[0].name").value("旁观者自己的行程"))
                .andReturn();

        String body = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(body)
                .as("他人数据（行程名 / 会话 / 照片 / 备忘）不能出现在导出里")
                .doesNotContain("只属于主人的行程")
                .doesNotContain("conv-export-1")
                .doesNotContain("stored-1.jpg")
                .doesNotContain("带伞");
    }

    @Test
    @DisplayName("导出是只读操作：连续两次导出行数不变，会话消息不叠加")
    void exportIsReadOnlyAndRepeatable() throws Exception {
        Account account = registerNew();
        long tripId = createTrip(account.id(), "重复导出");
        seedAllKinds(account.id(), tripId);

        for (int i = 0; i < 2; i++) {
            mockMvc.perform(get("/api/auth/me/export").header("Authorization", account.bearer()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.counts.messages").value(2))
                    .andExpect(jsonPath("$.data.conversations[0].messages.length()").value(2));
        }
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM conversation_message WHERE user_id = ?",
                Long.class, account.id()))
                .as("导出不得写入或重复插入消息")
                .isEqualTo(2L);
    }
}