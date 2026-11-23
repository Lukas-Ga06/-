package com.travel.agent.service;

import com.travel.agent.entity.User;
import com.travel.agent.exception.BusinessException;
import com.travel.agent.rag.RagDocumentKeys;
import com.travel.agent.repository.UserRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.PreparedStatement;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 账号注销的数据库覆盖测试（P0-D）。
 *
 * <p>用真实 Spring 上下文 + H2 测试库，给两个用户各插入一份「覆盖全部用户数据表」的数据，
 * 再注销用户 A：
 * <ul>
 *   <li>A 在所有表里的行都必须消失（直接按主键/业务键计数，避免 trip 子查询因父行已删而假通过）；</li>
 *   <li>B 的行必须原样保留，证明删除按 user_id 隔离而不是清库；</li>
 *   <li>A 的照片文件在事务提交后被删除，B 的仍在。</li>
 * </ul>
 * 另覆盖「无数据账号可注销」与「重复注销返回 401 而不是 500」。
 */
@SpringBootTest
@ActiveProfiles("test")
class AccountDeletionServiceTest {

    @Autowired
    private AccountDeletionService accountDeletionService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Value("${travel.photos.dir}")
    private String photoDir;

    /** 单行引用：table + 主键/业务键列 + 值，用于删除前后按行计数。 */
    private record RowRef(String table, String column, Object value) {
    }

    /** 一次 seed 产生的所有行引用与照片文件路径（不做实体映射，按真实列名插入）。 */
    private record Seeded(long userId, String username, String storedName, Path photoFile, List<RowRef> rows) {
    }

    @Test
    @DisplayName("注销后 A 的全部数据与照片文件被删除，B 的数据原样保留")
    void deletesAllOwnedRowsAndPhotoFilesButKeepsOtherUserData() throws Exception {
        String usernameA = uniqueUsername("a");
        String usernameB = uniqueUsername("b");
        Seeded userA = seed(usernameA);
        Seeded userB = seed(usernameB);
        // 公共知识库（user_id = NULL）不属于任何用户，注销个人账号不能把它删掉
        String publicTitle = "公共攻略-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        long publicDocId = insert("INSERT INTO rag_document "
                        + "(title, city, category, content, source, user_id, fingerprint, doc_key, updated_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                publicTitle, "成都", "guide", "公共内容", "test", null, "fp-public",
                RagDocumentKeys.of(null, publicTitle, "成都"), LocalDateTime.now().withNano(0));
        assertThat(Files.exists(userA.photoFile())).as("seed 必须真的落一张照片文件").isTrue();
        assertThat(Files.exists(userB.photoFile())).isTrue();

        AccountDeletionService.DeletionResult result =
                accountDeletionService.deleteAccount(userA.userId(), userA.username());

        // 结果只含聚合数字，且覆盖全部目标表
        assertThat(result.deletedRows()).containsKeys(
                "tool_call_trace", "agent_task", "agent_run", "agent_command_dedupe", "plan_execution",
                "pending_plan", "operation_history", "conversation_message", "conversation_meta",
                "expense", "memo", "schedule_item", "llm_quota_usage", "rag_chunk",
                "rag_document_version", "rag_document", "itinerary_item", "trip_event",
                "trip_photo", "trip", "users");
        assertThat(result.deletedRows().get("trip")).isEqualTo(1);
        assertThat(result.deletedRows().get("users")).isEqualTo(1);
        assertThat(result.photoFileCount()).isEqualTo(1);

        // A：逐表按主键计数，必须为 0
        for (RowRef ref : userA.rows()) {
            assertThat(count(ref)).as("注销后 %s 仍残留用户 A 的行", ref.table()).isZero();
        }
        assertThat(Files.exists(userA.photoFile())).as("A 的照片文件应在事务提交后被删除").isFalse();

        // B：同样的表逐行保留，证明按 user_id 隔离
        for (RowRef ref : userB.rows()) {
            assertThat(count(ref)).as("用户 B 的 %s 被误删", ref.table()).isEqualTo(1);
        }
        assertThat(Files.exists(userB.photoFile())).as("B 的照片文件不能受影响").isTrue();

        // B 的关联数据内容未被改写
        assertThat(jdbcTemplate.queryForObject(
                "SELECT name FROM trip WHERE id = ?", String.class,
                userB.rows().stream().filter(r -> "trip".equals(r.table())).findFirst().orElseThrow().value()))
                .isEqualTo("行程-" + usernameB);

        // 公共知识库文档仍然存在
        assertThat(count(new RowRef("rag_document", "id", publicDocId))).isEqualTo(1);
    }

    @Test
    @DisplayName("外层事务回滚时，所有 DELETE 一并回滚，照片文件也不会被删（afterCommit 语义）")
    void rollsBackAllDeletesAndKeepsPhotosWhenTransactionRollsBack() throws Exception {
        Seeded user = seed(uniqueUsername("rollback"));

        TransactionTemplate template = new TransactionTemplate(transactionManager);
        assertThatThrownBy(() -> template.executeWithoutResult(status -> {
            accountDeletionService.deleteAccount(user.userId(), user.username());
            throw new IllegalStateException("模拟提交前失败");
        })).isInstanceOf(IllegalStateException.class);

        for (RowRef ref : user.rows()) {
            assertThat(count(ref)).as("事务回滚后 %s 必须恢复", ref.table()).isEqualTo(1);
        }
        assertThat(Files.exists(user.photoFile())).as("事务未提交时不能删照片文件").isTrue();
    }

    @Test
    @DisplayName("没有任何业务数据的账号也能注销：只删 users 一行")
    void deletesBareUser() {
        User user = createUser(uniqueUsername("bare"));

        AccountDeletionService.DeletionResult result =
                accountDeletionService.deleteAccount(user.getId(), user.getUsername());

        assertThat(result.photoFileCount()).isZero();
        assertThat(result.deletedRows().get("users")).isEqualTo(1);
        assertThat(result.totalRows()).isEqualTo(1);
        assertThat(userRepository.findById(user.getId())).isEmpty();
    }

    @Test
    @DisplayName("重复注销/用户已不存在返回 401（requireUser 语义），不落成 500")
    void deletingMissingUserReturns401() {
        User user = createUser(uniqueUsername("gone"));
        accountDeletionService.deleteAccount(user.getId(), user.getUsername());

        assertThatThrownBy(() -> accountDeletionService.deleteAccount(user.getId(), user.getUsername()))
                .isInstanceOf(BusinessException.class)
                .hasMessage("用户不存在")
                .extracting("code")
                .isEqualTo(401);
    }

    // ==================== seed ====================

    private User createUser(String username) {
        return userRepository.save(User.builder()
                .username(username)
                .password(passwordEncoder.encode("pw123456"))
                .nickname(username)
                .build());
    }

    private Seeded seed(String username) throws Exception {
        User user = createUser(username);
        long userId = user.getId();
        LocalDateTime now = LocalDateTime.now().withNano(0);
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 12);

        long tripId = insert("INSERT INTO trip "
                        + "(user_id, name, destination, start_date, end_date, budget, status, party_size, version, created_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                userId, "行程-" + username, "成都", LocalDate.of(2026, 5, 1), LocalDate.of(2026, 5, 3),
                new BigDecimal("3000.00"), "PLANNING", 1, 0L, now);

        long itineraryItemId = insert("INSERT INTO itinerary_item "
                        + "(trip_id, day_index, item_type, title, status) VALUES (?, ?, ?, ?, ?)",
                tripId, 1, "attraction", "宽窄巷子-" + suffix, "PENDING");

        long tripEventId = insert("INSERT INTO trip_event "
                        + "(trip_id, itinerary_item_id, event_type, source, summary, occurred_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?)",
                tripId, itineraryItemId, "CHECK_IN", "user", "打卡-" + suffix, now);

        String storedName = "collapse-" + suffix + ".png";
        Path photoFile = Paths.get(photoDir).resolve(storedName).toAbsolutePath();
        Files.createDirectories(photoFile.getParent());
        Files.writeString(photoFile, "fake-image-bytes", StandardCharsets.UTF_8);
        long photoId = insert("INSERT INTO trip_photo "
                        + "(trip_id, user_id, stored_name, original_name, content_type, size_bytes, created_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?)",
                tripId, userId, storedName, "照片.png", "image/png", 16L, now);

        long expenseId = insert("INSERT INTO expense "
                        + "(trip_id, user_id, category, amount, note, spent_at, payment_method, created_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                tripId, userId, "餐饮", new BigDecimal("88.00"), "备注-" + suffix,
                LocalDate.of(2026, 5, 1), "现金", now);
        long memoId = insert("INSERT INTO memo (user_id, content, done, created_at) VALUES (?, ?, ?, ?)",
                userId, "备忘-" + suffix, false, now);
        long scheduleId = insert("INSERT INTO schedule_item "
                        + "(user_id, title, sched_date, done, created_at) VALUES (?, ?, ?, ?, ?)",
                userId, "日程-" + suffix, LocalDate.of(2026, 5, 1), false, now);
        long historyId = insert("INSERT INTO operation_history "
                        + "(trip_id, user_id, operation_type, status, created_at) VALUES (?, ?, ?, ?, ?)",
                tripId, userId, "MANUAL_EDIT", "ACTIVE", now);

        String planId = "plan-" + suffix;
        jdbcTemplate.update("INSERT INTO pending_plan "
                        + "(plan_id, user_id, trip_id, plan_json, preview_json, status, version, created_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                planId, userId, tripId, "{}", "{}", "PENDING", 0L, now);
        String executionId = "exec-" + suffix;
        jdbcTemplate.update("INSERT INTO plan_execution "
                        + "(execution_id, plan_id, user_id, trip_id, status, created_at) VALUES (?, ?, ?, ?, ?, ?)",
                executionId, planId, userId, tripId, "SUCCEEDED", now);

        String conversationId = "conv-" + suffix;
        long conversationMessageId = insert("INSERT INTO conversation_message "
                        + "(user_id, conversation_id, role, content, turn_seq, created_at) VALUES (?, ?, ?, ?, ?, ?)",
                userId, conversationId, "user", "你好", 1L, now);
        long conversationMetaId = insert("INSERT INTO conversation_meta "
                        + "(user_id, conversation_id, title, created_at, updated_at) VALUES (?, ?, ?, ?, ?)",
                userId, conversationId, "会话-" + suffix, now, now);

        String runId = "run-" + suffix;
        long agentRunId = insert("INSERT INTO agent_run "
                        + "(run_id, user_id, trip_id, message, intent, status, started_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?)",
                runId, userId, tripId, "测试", "PLAN_TRIP", "SUCCEEDED", now);
        String taskId = "task-" + suffix;
        long agentTaskId = insert("INSERT INTO agent_task "
                        + "(task_id, run_id, user_id, receiver, task_type, status, attempt, started_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                taskId, runId, userId, "intent-agent", "INTENT", "SUCCEEDED", 0, now);
        long toolTraceId = insert("INSERT INTO tool_call_trace "
                        + "(run_id, task_id, user_id, tool_name, success, started_at) VALUES (?, ?, ?, ?, ?, ?)",
                runId, taskId, userId, "query_weather", true, now);
        String requestId = "req-" + suffix;
        jdbcTemplate.update("INSERT INTO agent_command_dedupe "
                        + "(user_id, request_id, intent, status, created_at) VALUES (?, ?, ?, ?, ?)",
                userId, requestId, "ADD_MEMO", "COMPLETED", now);

        String docTitle = "攻略-" + suffix;
        long ragDocumentId = insert("INSERT INTO rag_document "
                        + "(title, city, category, content, source, user_id, fingerprint, doc_key, updated_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                docTitle, "成都", "guide", "私有内容", "test", userId, "fp-" + suffix,
                RagDocumentKeys.of(userId, docTitle, "成都"), now);
        long ragVersionId = insert("INSERT INTO rag_document_version "
                        + "(document_id, version_no, content, fingerprint, status, user_id, created_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?)",
                ragDocumentId, 1, "私有内容", "fp-" + suffix, "ACTIVE", userId, now);
        long ragChunkId = insert("INSERT INTO rag_chunk "
                        + "(document_id, version_id, content, embedding, dim) VALUES (?, ?, ?, ?, ?)",
                ragDocumentId, ragVersionId, "知识块-" + suffix, "[0.1,0.2]", 2);
        jdbcTemplate.update("INSERT INTO llm_quota_usage (user_name, usage_date, used_count) VALUES (?, ?, ?)",
                username, LocalDate.now(), 3);

        List<RowRef> rows = List.of(
                new RowRef("users", "id", userId),
                new RowRef("trip", "id", tripId),
                new RowRef("itinerary_item", "id", itineraryItemId),
                new RowRef("trip_event", "id", tripEventId),
                new RowRef("trip_photo", "id", photoId),
                new RowRef("expense", "id", expenseId),
                new RowRef("memo", "id", memoId),
                new RowRef("schedule_item", "id", scheduleId),
                new RowRef("operation_history", "id", historyId),
                new RowRef("pending_plan", "plan_id", planId),
                new RowRef("plan_execution", "execution_id", executionId),
                new RowRef("conversation_message", "id", conversationMessageId),
                new RowRef("conversation_meta", "id", conversationMetaId),
                new RowRef("agent_run", "id", agentRunId),
                new RowRef("agent_task", "id", agentTaskId),
                new RowRef("tool_call_trace", "id", toolTraceId),
                new RowRef("agent_command_dedupe", "request_id", requestId),
                new RowRef("rag_document", "id", ragDocumentId),
                new RowRef("rag_document_version", "id", ragVersionId),
                new RowRef("rag_chunk", "id", ragChunkId),
                new RowRef("llm_quota_usage", "user_name", username));
        return new Seeded(userId, username, storedName, photoFile, rows);
    }

    private long count(RowRef ref) {
        Long value = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM " + ref.table() + " WHERE " + ref.column() + " = ?",
                Long.class, ref.value());
        return value == null ? 0 : value;
    }

    private long insert(String sql, Object... args) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(sql, new String[]{"id"});
            for (int i = 0; i < args.length; i++) {
                ps.setObject(i + 1, args[i]);
            }
            return ps;
        }, keyHolder);
        Number key = keyHolder.getKey();
        if (key == null) {
            throw new IllegalStateException("插入未返回主键: " + sql);
        }
        return key.longValue();
    }

    private static String uniqueUsername(String prefix) {
        return "del_" + prefix + "_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }
}