package com.travel.agent.agent;

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

    private String type;
    private Long targetItemId;
    private ItineraryItem draft;      // ADD/MODIFY 的新值（尚未入库）
    private ItineraryItem original;   // REMOVE/MODIFY 的原值（用于 Diff）
    private String detail;
}
