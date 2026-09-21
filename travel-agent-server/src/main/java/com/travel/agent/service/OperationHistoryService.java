package com.travel.agent.service;

import com.travel.agent.dto.ItineraryDtos;
import com.travel.agent.entity.ItineraryItem;
import com.travel.agent.entity.OperationHistory;
import com.travel.agent.entity.Trip;
import com.travel.agent.exception.BusinessException;
import com.travel.agent.repository.ItineraryItemRepository;
import com.travel.agent.repository.OperationHistoryRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 操作历史：实现 AI「撤销」的核心。
 * 修改酒店 → 保存 Before → 执行修改 → 保存 After；用户点击还原上一步 → After 还原为 Before。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class OperationHistoryService {

    private final OperationHistoryRepository repository;
    private final ItineraryItemRepository itemRepository;
    private final ItineraryService itineraryService;
    private final TripService tripService;
    private final ObjectMapper objectMapper;

    @Transactional
    public void recordCommit(Trip trip, Long userId, List<ItineraryItem> before, List<ItineraryItem> after, String reason) {
        save(trip, userId, OperationHistory.OP_AGENT_COMMIT, before, after, reason);
    }

    /** Agent 新建行程（撤销时整体删除行程） */
    @Transactional
    public void recordTripCreate(Trip trip, Long userId, List<ItineraryItem> after, String reason) {
        save(trip, userId, OperationHistory.OP_TRIP_CREATE, List.of(), after, reason);
    }

    /**
     * 在「同一个事务」里执行一次行程变更并记录前后快照。
     *
     * 用于界面上手动的增/删/改：否则手工改动不进历史，用户点「撤销」会回滚到上一次 Agent 提交前的快照，
     * 把手工改动一并冲掉；同时保证「改动成功但历史写失败」时整体回滚。
     */
    @Transactional
    public <T> T withHistory(Trip trip, Long userId, String reason, java.util.function.Supplier<T> change) {
        List<ItineraryItem> before = itineraryService.listEntities(trip.getId());
        T result = change.get();
        save(trip, userId, OperationHistory.OP_MANUAL_EDIT, before, itineraryService.listEntities(trip.getId()), reason);
        return result;
    }

    private void save(Trip trip, Long userId, String operationType, List<ItineraryItem> before,
                      List<ItineraryItem> after, String reason) {
        try {
            repository.save(OperationHistory.builder()
                    .tripId(trip.getId())
                    .userId(userId)
                    .operationType(operationType)
                    .beforeState(objectMapper.writeValueAsString(toDtoList(before)))
                    .afterState(objectMapper.writeValueAsString(toDtoList(after)))
                    .agentReason(reason)
                    .build());
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            // 不能吞掉：历史写不进去就必须回滚行程变更，否则用户会失去撤销能力
            throw new IllegalStateException("操作历史序列化失败，已回滚本次行程修改", e);
        }
    }

    /** 还原上一步：用 before 快照替换当前行程 */
    @Transactional
    public String undoLatest(Trip trip, Long userId) {
        OperationHistory history = repository.findFirstByTripIdAndStatusOrderByIdDesc(trip.getId(), OperationHistory.STATUS_ACTIVE)
                .orElseThrow(() -> new BusinessException("没有可撤销的操作"));
        if (history.getUserId() != null && userId != null && !history.getUserId().equals(userId)) {
            throw new BusinessException(404, "没有可撤销的操作");
        }
        // 撤销「新建行程」= 删除行程本体，而不是留下一个没有任何安排的空壳
        if (OperationHistory.OP_TRIP_CREATE.equals(history.getOperationType())) {
            tripService.delete(trip.getId(), history.getUserId() == null ? userId : history.getUserId());
            return "已撤销新建行程：该行程及其安排、账单已一并删除";
        }
        try {
            List<ItineraryDtos.ItineraryItemDTO> before = objectMapper.readValue(
                    history.getBeforeState(), new TypeReference<List<ItineraryDtos.ItineraryItemDTO>>() {});
            List<ItineraryItem> restored = before.stream()
                    .map(dto -> toEntity(trip.getId(), dto))
                    .toList();
            itineraryService.replaceAll(trip.getId(), restored);
            history.setStatus(OperationHistory.STATUS_UNDONE);
            repository.save(history);
            return "已撤销上一步操作（还原 " + restored.size() + " 项安排）";
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.error("撤销失败", e);
            throw new BusinessException("撤销失败: " + e.getMessage());
        }
    }

    private List<ItineraryDtos.ItineraryItemDTO> toDtoList(List<ItineraryItem> items) {
        return items.stream().map(ItineraryDtos.ItineraryItemDTO::from).toList();
    }

    public static ItineraryItem toEntity(Long tripId, ItineraryDtos.ItineraryItemDTO dto) {
        return ItineraryItem.builder()
                .tripId(tripId)
                .dayIndex(dto.getDayIndex() == null ? 1 : dto.getDayIndex())
                .itemType(dto.getItemType() == null ? "attraction" : dto.getItemType())
                .title(dto.getTitle())
                .startTime(dto.getStartTime() == null ? null : java.time.LocalDateTime.parse(dto.getStartTime()))
                .endTime(dto.getEndTime() == null ? null : java.time.LocalDateTime.parse(dto.getEndTime()))
                .latitude(dto.getLatitude())
                .longitude(dto.getLongitude())
                .cost(dto.getCost())
                .tags(dto.getTags() == null ? null : String.join(",", dto.getTags()))
                .description(dto.getDescription())
                .build();
    }
}
