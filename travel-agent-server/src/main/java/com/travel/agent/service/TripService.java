package com.travel.agent.service;

import com.travel.agent.agent.TripDraft;
import com.travel.agent.dto.TripDtos;
import com.travel.agent.entity.ItineraryItem;
import com.travel.agent.entity.Trip;
import com.travel.agent.exception.BusinessException;
import com.travel.agent.repository.ExpenseRepository;
import com.travel.agent.repository.ItineraryItemRepository;
import com.travel.agent.repository.OperationHistoryRepository;
import com.travel.agent.repository.TripPhotoRepository;
import com.travel.agent.repository.TripRepository;
import com.travel.agent.util.Dates;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

@Service
@RequiredArgsConstructor
public class TripService {

    private final TripRepository tripRepository;
    private final ItineraryItemRepository itineraryItemRepository;
    private final ExpenseRepository expenseRepository;
    private final OperationHistoryRepository operationHistoryRepository;
    private final TripPhotoRepository tripPhotoRepository;
    private final PhotoStorage photoStorage;

    public List<TripDtos.TripResponse> list(Long userId) {
        return tripRepository.findByUserIdOrderByCreatedAtDesc(userId).stream()
                .map(TripDtos.TripResponse::from)
                .toList();
    }

    public Trip getOwned(Long tripId, Long userId) {
        return tripRepository.findByIdAndUserId(tripId, userId)
                .orElseThrow(() -> new BusinessException(404, "行程不存在或无权访问"));
    }

    public Trip create(Long userId, TripDtos.TripRequest req) {
        LocalDate start = req.getStartDate() == null || req.getStartDate().isBlank()
                ? LocalDate.now().plusDays(1) : Dates.parseDate(req.getStartDate(), "开始日期");
        LocalDate requestedEnd = Dates.parseDate(req.getEndDate(), "结束日期");
        if (requestedEnd != null && requestedEnd.isBefore(start)) {
            throw new BusinessException(400, "结束日期不能早于开始日期");
        }
        int days = 3;
        if (requestedEnd != null) {
            days = (int) (requestedEnd.toEpochDay() - start.toEpochDay()) + 1;
        }
        Trip trip = Trip.builder()
                .userId(userId)
                .name(req.getName() == null || req.getName().isBlank() ? "我的旅行" : req.getName())
                .destination(req.getDestination())
                .startDate(start)
                .endDate(start.plusDays(Math.max(days, 1) - 1L))
                .budget(req.getBudget() == null ? BigDecimal.valueOf(5000) : req.getBudget())
                .status(Trip.STATUS_PLANNING)
                .build();
        return tripRepository.save(trip);
    }

    public Trip update(Long tripId, Long userId, TripDtos.TripRequest req) {
        Trip trip = getOwned(tripId, userId);
        if (req.getName() != null) trip.setName(req.getName());
        if (req.getDestination() != null) trip.setDestination(req.getDestination());
        LocalDate newStart = Dates.parseDate(req.getStartDate(), "开始日期");
        LocalDate newEnd = Dates.parseDate(req.getEndDate(), "结束日期");
        if (newStart != null) trip.setStartDate(newStart);
        if (newEnd != null) trip.setEndDate(newEnd);
        if (trip.getStartDate() != null && trip.getEndDate() != null && trip.getEndDate().isBefore(trip.getStartDate())) {
            throw new BusinessException(400, "结束日期不能早于开始日期");
        }
        if (req.getBudget() != null) trip.setBudget(req.getBudget());
        return tripRepository.save(trip);
    }

    @Transactional
    public void delete(Long tripId, Long userId) {
        Trip trip = getOwned(tripId, userId);
        // 级联清理：条目、账单、操作历史、照片（含磁盘文件）
        tripPhotoRepository.findByTripIdOrderByCreatedAtDesc(trip.getId())
                .forEach(photo -> photoStorage.deleteQuietly(photo.getStoredName()));
        tripPhotoRepository.deleteByTripId(trip.getId());
        expenseRepository.deleteByTripId(trip.getId());
        operationHistoryRepository.deleteByTripId(trip.getId());
        itineraryItemRepository.deleteByTripId(trip.getId());
        tripRepository.delete(trip);
    }

    /** Agent 确认后创建行程（含完整行程条目） */
    @Transactional
    public Trip createFromAgent(Long userId, TripDraft draft, List<ItineraryItem> items) {
        Trip trip = Trip.builder()
                .userId(userId)
                .name(draft.getName())
                .destination(draft.getDestination())
                .startDate(draft.getStartDate())
                .endDate(draft.getStartDate().plusDays(Math.max(draft.getDays(), 1) - 1L))
                .budget(draft.getBudget())
                .status(Trip.STATUS_CONFIRMED)
                .build();
        trip = tripRepository.save(trip);
        for (ItineraryItem item : items) {
            item.setTripId(trip.getId());
        }
        itineraryItemRepository.saveAll(items);
        return trip;
    }
}
