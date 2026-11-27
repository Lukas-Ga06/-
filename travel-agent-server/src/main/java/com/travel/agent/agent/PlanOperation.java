package com.travel.agent.agent;

import com.travel.agent.dto.ExpenseDraft;
import com.travel.agent.dto.ScheduleSnapshot;
import com.travel.agent.entity.ItineraryItem;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** 计划中的单项操作：Executor 确认后才真正落库 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PlanOperation {

    public static final String ADD = "ADD_ITEM";
    public static final String REMOVE = "REMOVE_ITEM";
    public static final String MODIFY = "MODIFY_ITEM";
    /** 删除一条日程（批量清空日程的每一项；日程不属于任何行程） */
    public static final String REMOVE_SCHEDULE = "REMOVE_SCHEDULE";
    /**
     * 记一笔账单（发票报销）：不带 itinerary_item 草稿，也不改行程预算。
     * 这类方案没有行程（{@code plan.tripId} 为空），确认后由 ExecutorAgent 单事务落 expense。
     */
    public static final String ADD_EXPENSE = "ADD_EXPENSE";

    private String type;
    private Long targetItemId;
    private ItineraryItem draft;      // ADD/MODIFY 的新值（尚未入库）
    private ItineraryItem original;   // REMOVE/MODIFY 的原值（用于 Diff）
    private String detail;

    /** REMOVE_SCHEDULE：要删除的日程 id */
    private Long targetScheduleId;
    /** REMOVE_SCHEDULE：日程原值（用于 Diff 与撤销历史快照） */
    private ScheduleSnapshot scheduleOriginal;

    /** ADD_EXPENSE：待落库的账单草稿（确认后转 ExpenseService.add） */
    private ExpenseDraft expenseDraft;
}