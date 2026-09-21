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
    private final ObjectMapper objectMapper;

    @Transactional
    public void recordCommit(Trip trip, Long userId, List<ItineraryItem> before, List<ItineraryItem> after, String reason) {
        try {
            repository.save(OperationHistory.builder()
                    .tripId(trip.getId())
                    .userId(userId)
                    .operationType(OperationHistory.OP_AGENT_COMMIT)
                    .beforeState(objectMapper.writeValueAsString(toDtoList(before)))
                    .afterState(objectMapper.writeValueAsString(toDtoList(after)))
                    .agentReason(reason)
                    .build());
        } catch (Exception e) {
            log.error("记录操作历史失败", e);
        }
    }

    /** 还原上一步：用 before 快照替换当前行程 */
    @Transactional
    public String undoLatest(Trip trip, Long userId) {
        OperationHistory history = repository.findFirstByTripIdAndStatusOrderByIdDesc(trip.getId(), OperationHistory.STATUS_ACTIVE)
                .orElseThrow(() -> new BusinessException("没有可撤销的操作"));
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
