package com.travel.agent.service;

import com.travel.agent.dto.ItineraryDtos;
import com.travel.agent.dto.ScheduleSnapshot;
import com.travel.agent.entity.ItineraryItem;
import com.travel.agent.entity.OperationHistory;
import com.travel.agent.entity.ScheduleItem;
import com.travel.agent.entity.Trip;
import com.travel.agent.exception.BusinessException;
import com.travel.agent.repository.ItineraryItemRepository;
import com.travel.agent.repository.OperationHistoryRepository;
import com.travel.agent.repository.ScheduleItemRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
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
    private final ScheduleItemRepository scheduleItemRepository;
    private final ItineraryService itineraryService;
    private final TripService tripService;
    private final ObjectMapper objectMapper;

    @Transactional
    public void recordCommit(Trip trip, Long userId, List<ItineraryDtos.ItineraryItemDTO> before,
                             List<ItineraryItem> after, String reason) {
        save(trip, userId, OperationHistory.OP_AGENT_COMMIT, before, toDtoList(after), reason);
    }

    /** Agent 新建行程（撤销时整体删除行程） */
    @Transactional
    public void recordTripCreate(Trip trip, Long userId, List<ItineraryItem> after, String reason) {
        save(trip, userId, OperationHistory.OP_TRIP_CREATE, List.of(), toDtoList(after), reason);
    }

    /**
     * 在变更发生<b>之前</b>调用：把当前行程转成 DTO 快照。
     *
     * <p>不能等变更后再序列化实体列表——JPA 持久化上下文里 before/after 是同一批受管对象，
     * 字段被就地修改后「before 快照」会跟着变成新值，撤销就会还原出错误的旧状态。
     */
    public List<ItineraryDtos.ItineraryItemDTO> snapshot(List<ItineraryItem> items) {
        return toDtoList(items);
    }

    /**
     * 在「同一个事务」里执行一次行程变更并记录前后快照。
     *
     * <p>用于界面上手动的增/删/改与状态打卡：否则手工改动不进历史，用户点「撤销」会回滚到
     * 上一次 Agent 提交前的快照，把手工改动一并冲掉；同时保证「改动成功但历史写失败」时整体回滚。
     */
    @Transactional
    public <T> T withHistory(Trip trip, Long userId, String reason, java.util.function.Supplier<T> change) {
        // 变更前立刻快照（见 snapshot 的说明：受管实体在变更后会被就地修改）
        List<ItineraryDtos.ItineraryItemDTO> before = toDtoList(itineraryService.listEntities(trip.getId()));
        T result = change.get();
        save(trip, userId, OperationHistory.OP_MANUAL_EDIT, before,
                toDtoList(itineraryService.listEntities(trip.getId())), reason);
        return result;
    }

    private void save(Trip trip, Long userId, String operationType, List<ItineraryDtos.ItineraryItemDTO> before,
                      List<ItineraryDtos.ItineraryItemDTO> after, String reason) {
        try {
            repository.save(OperationHistory.builder()
                    .tripId(trip.getId())
                    .userId(userId)
                    .domain(OperationHistory.DOMAIN_ITINERARY)
                    .operationType(operationType)
                    .beforeState(objectMapper.writeValueAsString(before))
                    .afterState(objectMapper.writeValueAsString(after))
                    .agentReason(reason)
                    .build());
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            // 不能吞掉：历史写不进去就必须回滚行程变更，否则用户会失去撤销能力
            throw new IllegalStateException("操作历史序列化失败，已回滚本次行程修改", e);
        }
    }

    /**
     * 批量清空日程的 before 快照 → 一条 SCHEDULE 域历史（tripId 为空）。
     * 与删除动作同事务：序列化失败必须抛出，让本次删除整体回滚。
     */
    @Transactional
    public void recordScheduleClear(Long userId, List<ScheduleItem> before, String reason) {
        try {
            List<ScheduleSnapshot> snapshot = before == null ? List.of()
                    : before.stream().map(ScheduleSnapshot::of).toList();
            repository.save(OperationHistory.builder()
                    .userId(userId)
                    .domain(OperationHistory.DOMAIN_SCHEDULE)
                    .operationType(OperationHistory.OP_SCHEDULE_CLEAR)
                    .beforeState(objectMapper.writeValueAsString(snapshot))
                    .afterState("[]")
                    .agentReason(reason)
                    .build());
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            // 不能吞掉：历史写不进去就必须回滚这次删除，否则用户既丢数据又无法撤销
            throw new IllegalStateException("日程清空历史序列化失败，已回滚本次删除", e);
        }
    }

    /**
     * 撤销最近一次日程清空：按快照重新插入（id 置空），历史置 UNDONE。
     *
     * <p>只认 SCHEDULE 域：行程系的撤销仍走 {@link #undoLatest(Trip, Long)}，
     * 两条撤销链按 domain 隔离，互不覆盖。没有可撤销记录时 404。
     */
    @Transactional
    public String undoLatestSchedule(Long userId) {
        OperationHistory history = repository
                .findFirstByUserIdAndDomainAndStatusOrderByIdDesc(userId,
                        OperationHistory.DOMAIN_SCHEDULE, OperationHistory.STATUS_ACTIVE)
                .orElseThrow(() -> new BusinessException(404, "没有可撤销的日程清空"));
        try {
            List<ScheduleSnapshot> before = objectMapper.readValue(
                    history.getBeforeState(), new TypeReference<List<ScheduleSnapshot>>() {});
            List<ScheduleItem> restored = new java.util.ArrayList<>();
            for (ScheduleSnapshot snapshot : before) {
                restored.add(scheduleItemRepository.save(snapshot.toEntity(userId)));
            }
            history.setStatus(OperationHistory.STATUS_UNDONE);
            repository.save(history);
            return "已撤销上一步日程清空（还原 " + restored.size() + " 项日程）";
        } catch (Exception e) {
            log.error("撤销日程清空失败", e);
            throw new BusinessException("撤销失败: " + e.getMessage());
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
                    .map(dto -> toEntity(trip, dto))
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

    /**
     * 快照 → 实体。时间只沿用「时刻」，日期按当前行程的第 N 天重新锚定：
     * 这样即使用户在两次操作之间改过行程日期，撤销也不会把条目恢复到旧日期上。
     *
     * 保留快照里的 id：撤销时 ItineraryService.replaceAll 靠 id 做 diff，
     * 有 id 才能「就地更新」而不是「全删全插」（id 抖动会让历史快照与前端选中失效）。
     */
    public static ItineraryItem toEntity(Trip trip, ItineraryDtos.ItineraryItemDTO dto) {
        int day = dto.getDayIndex() == null ? 1 : dto.getDayIndex();
        LocalDate date = trip.getStartDate() == null
                ? LocalDate.now()
                : trip.getStartDate().plusDays(Math.max(day, 1) - 1L);
        return ItineraryItem.builder()
                .id(dto.getId())
                .tripId(trip.getId())
                .dayIndex(day)
                .itemType(dto.getItemType() == null ? "attraction" : dto.getItemType())
                .title(dto.getTitle())
                .startTime(reanchor(dto.getStartTime(), date))
                .endTime(reanchor(dto.getEndTime(), date))
                .latitude(dto.getLatitude())
                .longitude(dto.getLongitude())
                .cost(dto.getCost())
                .tags(dto.getTags() == null ? null : String.join(",", dto.getTags()))
                .description(dto.getDescription())
                .status(dto.getStatus() == null ? ItineraryItem.STATUS_PENDING : dto.getStatus())
                .startedAt(parseInstant(dto.getStartedAt()))
                .completedAt(parseInstant(dto.getCompletedAt()))
                .visitedAt(parseInstant(dto.getVisitedAt()))
                .build();
    }

    /** 实际发生时间（开始/完成/到访）按绝对时间还原，不随行程改期平移——它们是不可改写的事实 */
    private static LocalDateTime parseInstant(String iso) {
        if (iso == null || iso.isBlank()) {
            return null;
        }
        try {
            return LocalDateTime.parse(iso);
        } catch (java.time.format.DateTimeParseException e) {
            return null;
        }
    }

    private static LocalDateTime reanchor(String iso, LocalDate date) {
        if (iso == null || iso.isBlank()) {
            return null;
        }
        return LocalDateTime.parse(iso).toLocalTime().atDate(date);
    }
}
