package com.travel.agent.tool.impl;

import com.travel.agent.repository.TripRepository;
import com.travel.agent.service.OperationHistoryService;
import com.travel.agent.tool.AgentTool;
import com.travel.agent.tool.RiskLevel;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Map;

/** undo_operation：撤销上一次 Agent 修改 */
@Component
@RequiredArgsConstructor
public class UndoOperationTool implements AgentTool {

    private final TripRepository tripRepository;
    private final OperationHistoryService historyService;

    @Override
    public String getName() {
        return "undo_operation";
    }

    @Override
    public String getDescription() {
        return "撤销行程上一次 Agent 修改（还原操作历史快照）";
    }

    @Override
    public RiskLevel getRiskLevel() {
        return RiskLevel.MEDIUM;
    }

    @Override
    public Object execute(Map<String, Object> args) {
        Long tripId = ((Number) args.get("tripId")).longValue();
        var trip = tripRepository.findById(tripId).orElseThrow();
        String message = historyService.undoLatest(trip, trip.getUserId());
        return Map.of("message", message);
    }
}
