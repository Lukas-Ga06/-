package com.travel.agent.agent;

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

    private String type;
    private Long targetItemId;
    private ItineraryItem draft;      // ADD/MODIFY 的新值（尚未入库）
    private ItineraryItem original;   // REMOVE/MODIFY 的原值（用于 Diff）
    private String detail;

    /** REMOVE_SCHEDULE：要删除的日程 id */
    private Long targetScheduleId;
    /** REMOVE_SCHEDULE：日程原值（用于 Diff 与撤销历史快照） */
    private ScheduleSnapshot scheduleOriginal;
}