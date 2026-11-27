package com.travel.agent.service;

import com.travel.agent.entity.ScheduleItem;
import com.travel.agent.repository.ScheduleItemRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * 批量删除日程的唯一落地实现：只被 {@link com.travel.agent.agent.ExecutorAgent#commit} 调用。
 *
 * <p>刻意不加 {@code @Transactional}：删除必须和同一次的 SCHEDULE 域历史写在<b>同一个</b>
 * 事务里（事务由 commit 提供），自己再开事务会让「日程已删、历史未写」成为可能。
 *
 * <p>按 id 逐条查 + 校验归属：方案里的 id 来自生成时的快照，确认期间别的标签页可能已经删掉
 * 其中几条——跳过而不是报错，最终以真正删除的条数为准；不属于该用户的 id 同样跳过
 * （防越权，虽然 id 只可能来自本用户的查询结果）。
 */
@Service
@RequiredArgsConstructor
public class ScheduleOpsApplier {

    private final ScheduleItemRepository repository;

    /** 删除结果：before=真正删除的原值（撤销历史的 before_state），deleted=实际删除条数 */
    public record DeleteResult(List<ScheduleItem> before, int deleted) {
    }

    public DeleteResult delete(Long userId, List<Long> scheduleIds) {
        List<ScheduleItem> before = new ArrayList<>();
        if (userId == null || scheduleIds == null || scheduleIds.isEmpty()) {
            return new DeleteResult(before, 0);
        }
        for (Long id : new LinkedHashSet<>(scheduleIds)) {
            if (id == null) {
                continue;
            }
            ScheduleItem item = repository.findById(id)
                    .filter(s -> userId.equals(s.getUserId()))
                    .orElse(null);
            if (item == null) {
                continue;
            }
            before.add(item);
            repository.delete(item);
        }
        return new DeleteResult(before, before.size());
    }
}