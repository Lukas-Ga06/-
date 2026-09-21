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
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AgentPlan {

    private String planId;
    private String intent;
    private String target;
    private Long tripId;
    private CityCatalog city;
    private TripDraft tripDraft;
    @Builder.Default
    private List<PlanOperation> operations = new ArrayList<>();
    private String reasoning;
    private AgentDtos.AgentFocus focus;
    @Builder.Default
    private Map<String, Object> extras = new HashMap<>();
}
