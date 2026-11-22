package com.travel.agent;

import com.travel.agent.entity.ScheduleItem;
import com.travel.agent.repository.ScheduleItemRepository;
import com.travel.agent.service.PlanScheduleSyncWriter;
import com.travel.agent.support.IntegrationTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 同步日程幂等键（{@code schedule_item.source_key}）的数据库级兜底。
 *
 * <p>修的是 V16 的欠账：注释承诺「唯一索引兜底并发」，但索引从未创建，
 * 只剩「先查后插」，并发重试/多实例会写出重复的「🧳 行程 · Day N」时间块，
 * 而原先那个 {@code catch (DataIntegrityViolationException)} 永远不会触发。
 *
 * <p>本测试守住三件事：
 * <ol>
 *   <li>唯一索引真的存在（重复 source_key 只能落一条）；</li>
 *   <li>手工日程（source_key 为 NULL）不受影响，可以有多条；</li>
 *   <li>撞键发生在**独立事务**里：外层事务不会被标成 rollback-only
 *       （否则唯一索引一生效，同步提交就会变成 UnexpectedRollbackException）。</li>
 * </ol>
 */
class PlanScheduleIdempotencyTest extends IntegrationTestSupport {

    @Autowired
    private PlanScheduleSyncWriter scheduleSyncWriter;

    @Autowired
    private ScheduleItemRepository scheduleItemRepository;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private ScheduleItem syncItem(long userId, String sourceKey) {
        return ScheduleItem.builder()
                .userId(userId)
                .title("🧳 幂等测试行程 · Day 1")
                .date(LocalDate.of(2026, 12, 1))
                .time(LocalTime.of(9, 0))
                .note("同步自行程计划（幂等测试）")
                .sourceKey(sourceKey)
                .travelDay(1)
                .source("PLAN_SYNC")
                .build();
    }

    /** 每个用例独立的 source_key：内存库跨用例保留数据，不能复用固定值 */
    private static String uniqueKey() {
        return "plan-sync:" + UUID.randomUUID() + ":day:1";
    }

    @Test
    @DisplayName("同一 source_key 第二次插入被唯一索引拦下：只有一行，且不抛异常")
    void duplicateSourceKeyCreatesOnlyOneRow() {
        long userId = 9_100L;
        String sourceKey = uniqueKey();

        assertThat(scheduleSyncWriter.insertIfAbsent(syncItem(userId, sourceKey))).isTrue();
        // 并发第二插入走同一路径：撞唯一键 → 按已同步处理，不抛给调用方
        assertThat(scheduleSyncWriter.insertIfAbsent(syncItem(userId, sourceKey))).isFalse();

        assertThat(scheduleItemRepository.findBySourceKey(sourceKey)).isPresent();
        List<ScheduleItem> rows = scheduleItemRepository
                .findByUserIdOrderByDateAscTimeAscIdAsc(userId).stream()
                .filter(item -> sourceKey.equals(item.getSourceKey()))
                .toList();
        assertThat(rows).hasSize(1);
    }

    @Test
    @DisplayName("手工日程 source_key 为 NULL：多条并存，不被唯一索引挡住")
    void nullSourceKeysNeverConflict() {
        long userId = 9_200L;

        assertThat(scheduleSyncWriter.insertIfAbsent(syncItem(userId, null))).isTrue();
        assertThat(scheduleSyncWriter.insertIfAbsent(syncItem(userId, null))).isTrue();

        List<ScheduleItem> rows = scheduleItemRepository
                .findByUserIdOrderByDateAscTimeAscIdAsc(userId);
        assertThat(rows).hasSize(2);
    }

    @Test
    @DisplayName("撞键不会污染外层事务：同一事务里继续写别的日程仍能提交")
    void duplicateDoesNotPoisonOuterTransaction() {
        long userId = 9_300L;
        String duplicated = uniqueKey();
        String fresh = uniqueKey();

        // 模拟 PlanExecutionService.syncConfirmedPlan 的外层事务
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            assertThat(scheduleSyncWriter.insertIfAbsent(syncItem(userId, duplicated))).isTrue();
            // 撞唯一键：旧实现（同一事务内 saveAndFlush + catch）会在这里把事务标成 rollback-only
            assertThat(scheduleSyncWriter.insertIfAbsent(syncItem(userId, duplicated))).isFalse();
            // 外层事务仍然健康：继续写另一天的日程必须成功
            assertThat(scheduleSyncWriter.insertIfAbsent(syncItem(userId, fresh))).isTrue();
        });

        assertThat(scheduleItemRepository.findBySourceKey(duplicated)).isPresent();
        assertThat(scheduleItemRepository.findBySourceKey(fresh)).isPresent();
        assertThat(scheduleItemRepository.findByUserIdOrderByDateAscTimeAscIdAsc(userId)).hasSize(2);
    }
}