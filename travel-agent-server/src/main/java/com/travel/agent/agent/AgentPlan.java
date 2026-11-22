package com.travel.agent.agent;

import com.travel.agent.dto.AgentDtos;
import com.travel.agent.model.CityCatalog;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Planner Agent 输出的完整计划 */
@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
public class AgentPlan {

    private String planId;
    private String intent;
    private String target;
    private Long tripId;
    /**
     * 产生本计划的 Agent 运行标识（V2.2 追踪）：确认提交发生在 REST 线程、
     * 与规划运行分离，靠它把 COMMIT 任务与执行记录挂回同一 run。
     * 历史序列化的旧计划该字段为 null（兼容）。
     */
    private String runId;
    private CityCatalog city;
    private TripDraft tripDraft;
    @Builder.Default
    private List<PlanOperation> operations = new ArrayList<>();
    private String reasoning;
    private AgentDtos.AgentFocus focus;
    @Builder.Default
    private Map<String, Object> extras = new HashMap<>();
}
