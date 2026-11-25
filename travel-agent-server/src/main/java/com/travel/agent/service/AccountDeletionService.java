package com.travel.agent.service;

import com.travel.agent.exception.BusinessException;
import com.travel.agent.rag.VectorStore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 账号注销（P0-D）：删除用户本人及其全部旅行、照片、会话与派生数据，不残留可访问对象。
 *
 * <p>为什么不用 JPA 实体级联：库里大多数表<b>没有外键</b>，JPA 的 cascade/orphanRemoval
 * 只能覆盖映射了关联的实体，而 trip/itinerary_item/trip_event、conversation_*、agent_*
 * 之间都是裸 id 列。这里改用显式 SQL 把「谁拥有这行数据」写清楚，并按子表 → 父表顺序删除，
 * 让 H2(MODE=MySQL) 与 MySQL 8 在任何外键策略下都能执行。
 *
 * <p>照片文件不能进数据库事务：文件删除不可回滚。因此先在事务内收集
 * {@code trip_photo.stored_name}，等事务真正提交后再调 {@link PhotoStore#deleteQuietly(String)}；
 * 文件删失败只记 WARN，不影响已提交的账号删除（残留对象不可再通过接口访问）。
 *
 * <p>日志只写 userId 与删除行数，不写用户名、密码或照片文件名。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AccountDeletionService {

    private final JdbcTemplate jdbcTemplate;
    private final PhotoStore photoStore;
    private final VectorStore vectorStore;

    /**
     * 删除一个账号及其全部归属数据（幂等失败语义：用户已不存在 → 401，与控制器的 requireUser 一致）。
     *
     * @param userId   用户主键
     * @param username 用户名（仅用于 {@code llm_quota_usage.user_name} 这一张按名字计数的表）
     * @return 每张表的删除行数与照片文件数，供调用方/日志核对；不包含任何个人数据
     */
    @Transactional
    public DeletionResult deleteAccount(Long userId, String username) {
        if (userId == null) {
            throw new BusinessException(401, "用户不存在");
        }
        Long existing = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM users WHERE id = ?", Long.class, userId);
        if (existing == null || existing == 0) {
            throw new BusinessException(401, "用户不存在");
        }

        // 文件删除在事务提交后执行，名字必须先收集（随后 trip_photo 行会被删掉）
        List<String> storedNames = new ArrayList<>(jdbcTemplate.queryForList(
                "SELECT stored_name FROM trip_photo WHERE user_id = ?", String.class, userId));

        // V24：先让该用户已签发的 token 全部失效（版本+1），再删行。
        // 提交后旧 token 的 uid 查不到用户、ver 也对不上，双保险；回滚时版本一起回滚，不留半状态。
        jdbcTemplate.update("UPDATE users SET token_version = token_version + 1 WHERE id = ?", userId);

        Map<String, Integer> rows = new LinkedHashMap<>();
        // ---- Agent 追踪/执行派生数据（子表先删） ----
        delete(rows, "tool_call_trace", "DELETE FROM tool_call_trace WHERE user_id = ?", userId);
        delete(rows, "agent_task", "DELETE FROM agent_task WHERE user_id = ?", userId);
        // V28/V27：SSE 事件与状态迁移历史只有 run_id，按用户所属 run 删除
        delete(rows, "agent_run_event",
                "DELETE FROM agent_run_event WHERE run_id IN (SELECT run_id FROM agent_run WHERE user_id = ?)",
                userId);
        delete(rows, "agent_run_stage",
                "DELETE FROM agent_run_stage WHERE run_id IN (SELECT run_id FROM agent_run WHERE user_id = ?)",
                userId);
        delete(rows, "agent_run", "DELETE FROM agent_run WHERE user_id = ?", userId);
        delete(rows, "agent_command_dedupe", "DELETE FROM agent_command_dedupe WHERE user_id = ?", userId);
        delete(rows, "plan_execution", "DELETE FROM plan_execution WHERE user_id = ?", userId);
        // V26：确认同步 Outbox 待办属于该用户的行程派生数据
        delete(rows, "plan_outbox", "DELETE FROM plan_outbox WHERE user_id = ?", userId);
        delete(rows, "pending_plan", "DELETE FROM pending_plan WHERE user_id = ?", userId);

        // ---- 行程关联数据：user_id 为空的旧行也要删，因此 OR trip_id 子查询 ----
        delete(rows, "operation_history",
                "DELETE FROM operation_history WHERE user_id = ? OR trip_id IN (SELECT id FROM trip WHERE user_id = ?)",
                userId, userId);
        delete(rows, "expense",
                "DELETE FROM expense WHERE user_id = ? OR trip_id IN (SELECT id FROM trip WHERE user_id = ?)",
                userId, userId);

        // ---- 会话 ----
        delete(rows, "conversation_message", "DELETE FROM conversation_message WHERE user_id = ?", userId);
        delete(rows, "conversation_meta", "DELETE FROM conversation_meta WHERE user_id = ?", userId);

        // ---- 备忘 / 日程 / LLM 配额 ----
        delete(rows, "memo", "DELETE FROM memo WHERE user_id = ?", userId);
        delete(rows, "schedule_item", "DELETE FROM schedule_item WHERE user_id = ?", userId);
        delete(rows, "llm_quota_usage", "DELETE FROM llm_quota_usage WHERE user_name = ?", username);

        // ---- 个人知识库：chunk → version → document（公共知识 user_id 为空，不受影响） ----
        delete(rows, "rag_chunk",
                "DELETE FROM rag_chunk WHERE document_id IN (SELECT id FROM rag_document WHERE user_id = ?)",
                userId);
        delete(rows, "rag_document_version",
                "DELETE FROM rag_document_version "
                        + "WHERE user_id = ? OR document_id IN (SELECT id FROM rag_document WHERE user_id = ?)",
                userId, userId);
        delete(rows, "rag_document", "DELETE FROM rag_document WHERE user_id = ?", userId);

        // ---- 行程本体：trip_event 还引用 itinerary_item，先删事件再删条目 ----
        delete(rows, "trip_event",
                "DELETE FROM trip_event WHERE trip_id IN (SELECT id FROM trip WHERE user_id = ?)", userId);
        delete(rows, "itinerary_item",
                "DELETE FROM itinerary_item WHERE trip_id IN (SELECT id FROM trip WHERE user_id = ?)", userId);
        delete(rows, "trip_photo", "DELETE FROM trip_photo WHERE user_id = ?", userId);
        delete(rows, "trip", "DELETE FROM trip WHERE user_id = ?", userId);

        int deletedUsers = delete(rows, "users", "DELETE FROM users WHERE id = ?", userId);
        if (deletedUsers == 0) {
            // 并发注销/被别处删除：回滚整笔事务并按 requireUser 语义返回 401，不冒 500
            throw new BusinessException(401, "用户不存在");
        }

        schedulePostCommitCleanup(userId, storedNames);

        DeletionResult result = new DeletionResult(Collections.unmodifiableMap(rows), storedNames.size());
        log.info("账号删除完成: userId={}, 删除行数={}, 照片文件数={}",
                userId, result.totalRows(), storedNames.size());
        return result;
    }

    private int delete(Map<String, Integer> rows, String table, String sql, Object... args) {
        int deleted = jdbcTemplate.update(sql, args);
        rows.put(table, deleted);
        return deleted;
    }

    /**
     * 事务提交后做两件不可回滚的事：删照片文件、重建内存向量索引。
     *
     * <p>向量索引必须等提交后再重建：在事务内重建会读到尚未提交的删除，一旦事务回滚，
     * 内存索引就会缺块（库里有、检索没有）。没有活动事务时（理论上不会发生）直接执行。
     */
    private void schedulePostCommitCleanup(Long userId, List<String> storedNames) {
        List<String> pending = List.copyOf(storedNames);
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            log.warn("账号注销未处于事务中，照片与向量索引在删除后立即清理: userId={}, 文件数={}",
                    userId, pending.size());
            cleanUpPhotoFiles(userId, pending);
            reloadVectorStore(userId);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                cleanUpPhotoFiles(userId, pending);
                reloadVectorStore(userId);
            }
        });
    }

    /**
     * 重建内存向量索引，去掉已删用户的个人知识块。
     * 失败只 WARN：账号删除已经提交，不能让索引故障把成功响应变成 500；重启会全量重建。
     */
    private void reloadVectorStore(Long userId) {
        try {
            vectorStore.reload();
            log.info("账号注销后已重建内存向量索引: userId={}", userId);
        } catch (RuntimeException e) {
            log.warn("账号注销后重建内存向量索引失败（重启后自愈）: userId={}, error={}",
                    userId, e.getClass().getSimpleName());
        }
    }

    /** 逐个删除文件；单个失败只 WARN（日志不写文件名），不影响其余文件与已提交的数据删除 */
    private void cleanUpPhotoFiles(Long userId, List<String> storedNames) {
        int failed = 0;
        for (String storedName : storedNames) {
            try {
                photoStore.deleteQuietly(storedName);
            } catch (RuntimeException e) {
                failed++;
            }
        }
        if (failed > 0) {
            log.warn("账号注销照片文件清理失败: userId={}, 失败数={}", userId, failed);
        }
    }

    /**
     * 删除结果（可读、可日志）：表名 → 删除行数；照片文件数单独统计。
     * 只含聚合数字，不含用户名、密码、照片名等个人数据。
     */
    public record DeletionResult(Map<String, Integer> deletedRows, int photoFileCount) {

        public int totalRows() {
            return deletedRows.values().stream().mapToInt(Integer::intValue).sum();
        }
    }
}