package com.travel.agent.service;

import com.travel.agent.agent.PlanOperation;
import com.travel.agent.dto.ItineraryDtos;
import com.travel.agent.entity.ItineraryItem;
import com.travel.agent.entity.Trip;
import com.travel.agent.exception.BusinessException;
import com.travel.agent.repository.ItineraryItemRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

@Service
@RequiredArgsConstructor
public class ItineraryService {

    private final ItineraryItemRepository repository;

    public List<ItineraryDtos.ItineraryItemDTO> listByTrip(Long tripId) {
        return repository.findByTripIdOrderByDayIndexAscStartTimeAsc(tripId).stream()
                .map(ItineraryDtos.ItineraryItemDTO::from)
                .toList();
    }

    public List<ItineraryItem> listEntities(Long tripId) {
        return repository.findByTripIdOrderByDayIndexAscStartTimeAsc(tripId);
    }

    public ItineraryItem findById(Long id) {
        return repository.findById(id)
                .orElseThrow(() -> new BusinessException(404, "行程条目不存在"));
    }

    @Transactional
    public ItineraryItem addItem(Trip trip, ItineraryDtos.AddItemRequest req) {
        if (req.getTitle() == null || req.getTitle().isBlank()) {
            throw new BusinessException("标题不能为空");
        }
        int day = req.getDayIndex() == null ? 1 : req.getDayIndex();
        ItineraryItem item = ItineraryItem.builder()
                .tripId(trip.getId())
                .dayIndex(day)
                .itemType(req.getItemType() == null ? "attraction" : req.getItemType())
                .title(req.getTitle())
                .startTime(atTime(trip.getStartDate(), day, req.getStartTime(), "09:00"))
                .endTime(atTime(trip.getStartDate(), day, req.getEndTime(), "11:00"))
                .latitude(req.getLatitude())
                .longitude(req.getLongitude())
                .cost(req.getCost())
                .tags(req.getTags() == null ? null : String.join(",", req.getTags()))
                .description(req.getDescription())
                .build();
        return repository.save(item);
    }

    @Transactional
    public ItineraryItem updateItem(Trip trip, Long itemId, ItineraryDtos.UpdateItemRequest req) {
        ItineraryItem item = repository.findById(itemId)
                .filter(i -> i.getTripId().equals(trip.getId()))
                .orElseThrow(() -> new BusinessException(404, "行程条目不存在"));
        if (req.getTitle() != null) item.setTitle(req.getTitle());
        if (req.getItemType() != null) item.setItemType(req.getItemType());
        if (req.getDayIndex() != null) item.setDayIndex(req.getDayIndex());
        if (req.getStartTime() != null) item.setStartTime(atTime(trip.getStartDate(), item.getDayIndex(), req.getStartTime(), "09:00"));
        if (req.getEndTime() != null) item.setEndTime(atTime(trip.getStartDate(), item.getDayIndex(), req.getEndTime(), "11:00"));
        if (req.getCost() != null) item.setCost(req.getCost());
        if (req.getLatitude() != null) item.setLatitude(req.getLatitude());
        if (req.getLongitude() != null) item.setLongitude(req.getLongitude());
        if (req.getTags() != null) item.setTags(String.join(",", req.getTags()));
        if (req.getDescription() != null) item.setDescription(req.getDescription());
        return repository.save(item);
    }

    @Transactional
    public void deleteItem(Trip trip, Long itemId) {
        ItineraryItem item = repository.findById(itemId)
                .filter(i -> i.getTripId().equals(trip.getId()))
                .orElseThrow(() -> new BusinessException(404, "行程条目不存在"));
        repository.delete(item);
    }

    /** 应用 Agent 计划（已确认后）：REMOVE / ADD / MODIFY */
    @Transactional
    public void applyOperations(Long tripId, List<PlanOperation> operations) {
        for (PlanOperation op : operations) {
            switch (op.getType()) {
                case "REMOVE_ITEM" -> repository.delete(requireItemOfTrip(tripId, op.getTargetItemId()));
                case "ADD_ITEM" -> {
                    ItineraryItem draft = op.getDraft();
                    if (draft == null) {
                        throw new BusinessException(400, "新增条目缺少内容");
                    }
                    draft.setTripId(tripId);
                    repository.save(draft);
                }
                case "MODIFY_ITEM" -> {
                    ItineraryItem target = op.getDraft();
                    if (target == null) {
                        throw new BusinessException(400, "修改条目缺少内容");
                    }
                    ItineraryItem existing = requireItemOfTrip(tripId, op.getTargetItemId());
                    existing.setTitle(target.getTitle());
                    existing.setItemType(target.getItemType());
                    existing.setStartTime(target.getStartTime());
                    existing.setEndTime(target.getEndTime());
                    existing.setLatitude(target.getLatitude());
                    existing.setLongitude(target.getLongitude());
                    existing.setCost(target.getCost());
                    existing.setTags(target.getTags());
                    existing.setDescription(target.getDescription());
                    repository.save(existing);
                }
                default -> throw new BusinessException("未知操作类型: " + op.getType());
            }
        }
    }

    /** 计划中的条目必须属于当前行程，避免跨行程改写/删除数据 */
    private ItineraryItem requireItemOfTrip(Long tripId, Long itemId) {
        if (itemId == null) {
            throw new BusinessException(400, "操作缺少目标条目");
        }
        return repository.findById(itemId)
                .filter(item -> item.getTripId().equals(tripId))
                .orElseThrow(() -> new BusinessException(404, "行程条目不存在或不属于该行程"));
    }

    /** 用快照整体替换行程（撤销用） */
    @Transactional
    public void replaceAll(Long tripId, List<ItineraryItem> items) {
        repository.deleteByTripId(tripId);
        List<ItineraryItem> toSave = new ArrayList<>();
        for (ItineraryItem item : items) {
            item.setId(null);
            item.setTripId(tripId);
            toSave.add(item);
        }
        repository.saveAll(toSave);
    }

    public static LocalTime parseTime(String hhmm, String fallback) {
        try {
            return LocalTime.parse(hhmm == null || hhmm.isBlank() ? fallback : hhmm);
        } catch (Exception e) {
            return LocalTime.parse(fallback);
        }
    }

    public static java.time.LocalDateTime atTime(LocalDate startDate, int dayIndex, String hhmm, String fallback) {
        LocalDate date = startDate != null ? startDate.plusDays(Math.max(dayIndex, 1) - 1L) : LocalDate.now();
        return date.atTime(parseTime(hhmm, fallback));
    }

    public static List<String> splitTags(String tags) {
        return tags == null || tags.isBlank() ? new ArrayList<>() : Arrays.asList(tags.split(","));
    }
}
