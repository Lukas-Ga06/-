package com.travel.agent.service;

import com.travel.agent.agent.TripDraft;
import com.travel.agent.dto.TripDtos;
import com.travel.agent.entity.ItineraryItem;
import com.travel.agent.entity.Trip;
import com.travel.agent.exception.BusinessException;
import com.travel.agent.repository.ItineraryItemRepository;
import com.travel.agent.repository.TripRepository;
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
                ? LocalDate.now().plusDays(1) : LocalDate.parse(req.getStartDate());
        int days = 3;
        if (req.getEndDate() != null && !req.getEndDate().isBlank()) {
            days = (int) (LocalDate.parse(req.getEndDate()).toEpochDay() - start.toEpochDay()) + 1;
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
        if (req.getStartDate() != null && !req.getStartDate().isBlank()) trip.setStartDate(LocalDate.parse(req.getStartDate()));
        if (req.getEndDate() != null && !req.getEndDate().isBlank()) trip.setEndDate(LocalDate.parse(req.getEndDate()));
        if (req.getBudget() != null) trip.setBudget(req.getBudget());
        return tripRepository.save(trip);
    }

    @Transactional
    public void delete(Long tripId, Long userId) {
        Trip trip = getOwned(tripId, userId);
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
