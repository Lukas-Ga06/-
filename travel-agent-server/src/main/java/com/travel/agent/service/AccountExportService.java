package com.travel.agent.service;

import com.travel.agent.dto.ExportDtos;
import com.travel.agent.dto.PhotoDtos;
import com.travel.agent.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 账号级数据导出（合规：数据可携带 / 数据访问权）。
 *
 * <p>只读、无副作用，不产生文件：整份数据在内存里拼好后随 HTTP 响应返回，
 * 调用方自己决定存哪。范围与 {@link AccountDeletionService} 对齐——注销会删掉什么，
 * 导出就让用户看到什么，避免"能导出但注销不删"或反过来的口径差。
 *
 * <p>用 {@link JdbcTemplate} 而不是实体级联，原因同 {@code AccountDeletionService}：
 * 多数表没有外键，裸 id 关联，显式 SQL 才能把"谁拥有这行数据"写清楚。查询统一显式列名 +
 * 固定键名（{@code *_KEYS}），不依赖数据库列名大小写或方言，H2 与 MySQL 结果一致。
 *
 * <p>照片只导出清单与访问地址，不导出二进制：否则一份导出就是几十 MB 且要处理流式打包、
 * 临时文件与清理，对"用户拿回自己的数据"这个目的没有必要。
 *
 * <p>日志只写 userId 与各集合条数，不写用户名、消息正文或照片名。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AccountExportService {

    private final JdbcTemplate jdbcTemplate;

    private static final String TRIPS_SQL =
            "SELECT id, name, destination, start_date, end_date, budget, party_size, status, created_at "
                    + "FROM trip WHERE user_id = ? ORDER BY id";
    private static final String[] TRIPS_KEYS =
            {"id", "name", "destination", "startDate", "endDate", "budget", "partySize", "status", "createdAt"};

    private static final String ITINERARY_ITEMS_SQL =
            "SELECT i.id, i.trip_id, i.day_index, i.item_type, i.title, i.start_time, i.end_time, "
                    + "i.latitude, i.longitude, i.cost, i.tags, i.description, i.status, "
                    + "i.started_at, i.completed_at, i.visited_at "
                    + "FROM itinerary_item i JOIN trip t ON t.id = i.trip_id "
                    + "WHERE t.user_id = ? ORDER BY i.trip_id, i.day_index, i.id";
    private static final String[] ITINERARY_ITEMS_KEYS =
            {"id", "tripId", "dayIndex", "itemType", "title", "startTime", "endTime", "latitude", "longitude",
                    "cost", "tags", "description", "status", "startedAt", "completedAt", "visitedAt"};

    /**
     * 记账行可能没有 user_id（历史数据只挂 trip_id），所以按 trip 归属兜底，
     * 与 {@link AccountDeletionService} 的删除口径保持一致。
     */
    private static final String EXPENSES_SQL =
            "SELECT id, trip_id, category, amount, note, spent_at, payment_method, created_at FROM expense "
                    + "WHERE user_id = ? OR trip_id IN (SELECT id FROM trip WHERE user_id = ?) ORDER BY id";
    private static final String[] EXPENSES_KEYS =
            {"id", "tripId", "category", "amount", "note", "spentAt", "paymentMethod", "createdAt"};

    private static final String MEMOS_SQL =
            "SELECT id, content, done, created_at, trip_id, itinerary_item_id, source, travel_day "
                    + "FROM memo WHERE user_id = ? ORDER BY id";
    private static final String[] MEMOS_KEYS =
            {"id", "content", "done", "createdAt", "tripId", "itineraryItemId", "source", "travelDay"};

    /**
     * 注意：{@code nextOccurrence} 在实体里是 {@code @Transient} 派生字段（列表展示用），
     * 库里没有 {@code next_occurrence} 列——V9 那句 {@code CREATE TABLE IF NOT EXISTS} 在 V1 之后是空转的。
     * 因此这里只选真实落库的列。
     */
    private static final String SCHEDULES_SQL =
            "SELECT id, title, sched_date, sched_time, note, done, recurrence, repeat_until, "
                    + "reminder_dismissed_at, created_at, trip_id, itinerary_item_id, source, travel_day "
                    + "FROM schedule_item WHERE user_id = ? ORDER BY sched_date, sched_time, id";
    private static final String[] SCHEDULES_KEYS =
            {"id", "title", "date", "time", "note", "done", "recurrence", "repeatUntil",
                    "reminderDismissedAt", "createdAt", "tripId", "itineraryItemId", "source", "travelDay"};

    /**
     * 个人知识库只导出本人上传的文档（{@code user_id} 非空）；
     * 城市公共知识（{@code user_id} 为空）不是个人数据，不导出。
     */
    private static final String KNOWLEDGE_SQL =
            "SELECT id, title, city, category, content, source, updated_at "
                    + "FROM rag_document WHERE user_id = ? ORDER BY id";
    private static final String[] KNOWLEDGE_KEYS =
            {"id", "title", "city", "category", "content", "source", "updatedAt"};

    private static final String PHOTOS_SQL =
            "SELECT id, trip_id, original_name, content_type, size_bytes, kind, created_at "
                    + "FROM trip_photo WHERE user_id = ? ORDER BY id";

    private static final String CONVERSATIONS_SQL =
            "SELECT conversation_id, title, created_at, updated_at "
                    + "FROM conversation_meta WHERE user_id = ? ORDER BY updated_at DESC, id";

    private static final String MESSAGES_SQL =
            "SELECT conversation_id, role, content, intent, scope, turn_seq, created_at "
                    + "FROM conversation_message WHERE user_id = ? ORDER BY id";

    /**
     * 导出指定账号的全部可见数据。
     *
     * @param userId 用户主键（由控制器从已认证身份解析，不接受请求参数，避免越权导出他人数据）
     * @return 结构化导出对象
     * @throws BusinessException 401 用户不存在（与 {@code /api/auth/me} 的语义一致）
     */
    @Transactional(readOnly = true)
    public ExportDtos.AccountExport export(Long userId) {
        ExportDtos.Account account = loadAccount(userId);

        List<Map<String, Object>> trips = rows(TRIPS_SQL, TRIPS_KEYS, userId);
        List<Map<String, Object>> itineraryItems = rows(ITINERARY_ITEMS_SQL, ITINERARY_ITEMS_KEYS, userId);
        List<Map<String, Object>> expenses = rows(EXPENSES_SQL, EXPENSES_KEYS, userId, userId);
        List<Map<String, Object>> memos = rows(MEMOS_SQL, MEMOS_KEYS, userId);
        List<Map<String, Object>> schedules = rows(SCHEDULES_SQL, SCHEDULES_KEYS, userId);
        List<ExportDtos.Conversation> conversations = loadConversations(userId);
        List<Map<String, Object>> knowledgeDocuments = rows(KNOWLEDGE_SQL, KNOWLEDGE_KEYS, userId);
        List<PhotoDtos.Photo> photos = loadPhotos(userId);

        int messages = conversations.stream().mapToInt(c -> c.getMessages().size()).sum();
        ExportDtos.Counts counts = ExportDtos.Counts.builder()
                .trips(trips.size())
                .itineraryItems(itineraryItems.size())
                .expenses(expenses.size())
                .memos(memos.size())
                .schedules(schedules.size())
                .conversations(conversations.size())
                .messages(messages)
                .knowledgeDocuments(knowledgeDocuments.size())
                .photos(photos.size())
                .build();

        ExportDtos.AccountExport result = ExportDtos.AccountExport.builder()
                .formatVersion(ExportDtos.FORMAT_VERSION)
                .exportedAt(OffsetDateTime.now().toString())
                .account(account)
                .counts(counts)
                .trips(trips)
                .itineraryItems(itineraryItems)
                .expenses(expenses)
                .memos(memos)
                .schedules(schedules)
                .conversations(conversations)
                .knowledgeDocuments(knowledgeDocuments)
                .photos(photos)
                .build();

        log.info("账号数据导出完成: userId={}, 行程={}, 行程项={}, 记账={}, 备忘={}, 日程={}, 会话={}, 消息={}, 知识库={}, 照片={}",
                userId, counts.getTrips(), counts.getItineraryItems(), counts.getExpenses(), counts.getMemos(),
                counts.getSchedules(), counts.getConversations(), counts.getMessages(),
                counts.getKnowledgeDocuments(), counts.getPhotos());
        return result;
    }

    /** 账号资料：显式列名，永不 SELECT password / token_version。 */
    private ExportDtos.Account loadAccount(Long userId) {
        if (userId == null) {
            throw new BusinessException(401, "用户不存在");
        }
        List<ExportDtos.Account> found = jdbcTemplate.query(
                "SELECT id, username, nickname, created_at FROM users WHERE id = ?",
                (rs, rowNum) -> ExportDtos.Account.builder()
                        .id(rs.getLong("id"))
                        .username(rs.getString("username"))
                        .nickname(rs.getString("nickname"))
                        .createdAt(text(rs.getObject("created_at")))
                        .build(),
                userId);
        if (found.isEmpty()) {
            throw new BusinessException(401, "用户不存在");
        }
        return found.get(0);
    }

    /**
     * 会话按"元数据 → 消息"两段查：先建 {@code conversationId → Conversation} 映射，
     * 再把消息塞进对应会话，避免 N+1，也保证没有元数据的孤儿消息不会凭空出现。
     */
    private List<ExportDtos.Conversation> loadConversations(Long userId) {
        List<ExportDtos.Conversation> conversations = jdbcTemplate.query(
                CONVERSATIONS_SQL,
                (rs, rowNum) -> ExportDtos.Conversation.builder()
                        .conversationId(rs.getString("conversation_id"))
                        .title(rs.getString("title"))
                        .createdAt(text(rs.getObject("created_at")))
                        .updatedAt(text(rs.getObject("updated_at")))
                        .messages(new ArrayList<>())
                        .build(),
                userId);

        Map<String, ExportDtos.Conversation> byId = new LinkedHashMap<>();
        for (ExportDtos.Conversation conversation : conversations) {
            byId.put(conversation.getConversationId(), conversation);
        }
        jdbcTemplate.query(MESSAGES_SQL, rs -> {
            ExportDtos.Conversation conversation = byId.get(rs.getString("conversation_id"));
            if (conversation == null) {
                return;
            }
            conversation.getMessages().add(ExportDtos.Message.builder()
                    .role(rs.getString("role"))
                    .content(rs.getString("content"))
                    .intent(rs.getString("intent"))
                    .scope(rs.getString("scope"))
                    .turnSeq(nullableLong(rs.getObject("turn_seq")))
                    .createdAt(text(rs.getObject("created_at")))
                    .build());
        }, userId);
        return conversations;
    }

    /** 照片清单：只给元数据与访问地址（原图仍需本人凭证访问），不含二进制。 */
    private List<PhotoDtos.Photo> loadPhotos(Long userId) {
        return jdbcTemplate.query(PHOTOS_SQL,
                (rs, rowNum) -> {
                    long id = rs.getLong("id");
                    return PhotoDtos.Photo.builder()
                            .id(id)
                            .tripId(nullableLong(rs.getObject("trip_id")))
                            .originalName(rs.getString("original_name"))
                            .contentType(rs.getString("content_type"))
                            .sizeBytes(nullableLong(rs.getObject("size_bytes")))
                            .createdAt(text(rs.getObject("created_at")))
                            // V32：用途随导出一起给；存量行为 null 时按 PHOTO 回落，与列表接口口径一致
                            .kind(rs.getString("kind") == null
                                    ? com.travel.agent.entity.TripPhoto.KIND_PHOTO : rs.getString("kind"))
                            .url("/api/photos/" + id + "/raw")
                            .build();
                },
                userId);
    }

    /** 按固定键名把结果集转成有序 Map（键顺序 = SQL 列顺序 = {@code *_KEYS} 顺序）。 */
    private List<Map<String, Object>> rows(String sql, String[] keys, Object... args) {
        return jdbcTemplate.query(sql, (rs, rowNum) -> {
            Map<String, Object> row = new LinkedHashMap<>();
            for (int i = 0; i < keys.length; i++) {
                row.put(keys[i], scalar(rs.getObject(i + 1)));
            }
            return row;
        }, args);
    }

    /** 时间统一成 ISO-8601 字符串，跨 H2/MySQL（Timestamp / LocalDateTime / Date / Time）表现一致。 */
    private static Object scalar(Object value) {
        if (value instanceof LocalDateTime || value instanceof Timestamp
                || value instanceof LocalDate || value instanceof java.sql.Date
                || value instanceof LocalTime || value instanceof java.sql.Time) {
            return text(value);
        }
        return value;
    }

    private static String text(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof LocalDateTime localDateTime) {
            return localDateTime.toString();
        }
        if (value instanceof Timestamp timestamp) {
            return timestamp.toLocalDateTime().toString();
        }
        if (value instanceof LocalDate localDate) {
            return localDate.toString();
        }
        if (value instanceof java.sql.Date date) {
            return date.toLocalDate().toString();
        }
        if (value instanceof LocalTime localTime) {
            return localTime.toString();
        }
        if (value instanceof java.sql.Time time) {
            return time.toLocalTime().toString();
        }
        return value.toString();
    }

    /** {@code getLong} 对 NULL 返回 0，会把"没有值"说成 0；这里保留 null。 */
    private static Long nullableLong(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            return number.longValue();
        }
        return Long.valueOf(value.toString());
    }
}