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
import com.travel.agent.util.Paging;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Set;

@Service
@RequiredArgsConstructor
@Slf4j
public class TripService {

    private final TripRepository tripRepository;
    private final ItineraryItemRepository itineraryItemRepository;
    private final ExpenseRepository expenseRepository;
    private final OperationHistoryRepository operationHistoryRepository;
    private final TripPhotoRepository tripPhotoRepository;
    private final PhotoStore photoStorage;
    private final ItineraryService itineraryService;

    public List<TripDtos.TripResponse> list(Long userId) {
        return tripRepository.findByUserIdOrderByCreatedAtDesc(userId).stream()
                .map(TripDtos.TripResponse::from)
                .toList();
    }

    /** 行程列表分页：排序与旧实现一致（created_at desc），分页/排序下推数据库 */
    public Page<TripDtos.TripResponse> page(Long userId, int offset, int limit) {
        Pageable pageable = Paging.pageable(offset, limit,
                Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id")));
        return tripRepository.findByUserId(userId, pageable).map(TripDtos.TripResponse::from);
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

    @Transactional
    public Trip update(Long tripId, Long userId, TripDtos.TripRequest req) {
        Trip trip = getOwned(tripId, userId);
        LocalDate oldStart = trip.getStartDate();
        if (req.getName() != null) trip.setName(req.getName());
        if (req.getDestination() != null) trip.setDestination(req.getDestination());
        LocalDate newStart = Dates.parseDate(req.getStartDate(), "开始日期");
        LocalDate newEnd = Dates.parseDate(req.getEndDate(), "结束日期");
        if (newStart != null) trip.setStartDate(newStart);
        if (newEnd != null) trip.setEndDate(newEnd);
        if (trip.getStartDate() != null && trip.getEndDate() != null && trip.getEndDate().isBefore(trip.getStartDate())) {
            throw new BusinessException(400, "结束日期不能早于开始日期");
        }
        // 缩短行程时，先确认没有安排落在新范围之外（不做静默删除）
        int totalDays = trip.totalDays();
        int maxDay = itineraryService.listEntities(tripId).stream()
                .mapToInt(item -> item.getDayIndex() == null ? 1 : item.getDayIndex())
                .max()
                .orElse(0);
        if (maxDay > totalDays) {
            throw new BusinessException(400, "缩短后第 " + maxDay + " 天的安排会超出范围（当前共 "
                    + totalDays + " 天），请先调整或删除这些安排");
        }
        // 改起始日期：条目绝对时间整体平移，保持「第几天 + 时刻」不变
        if (oldStart != null && trip.getStartDate() != null && !oldStart.equals(trip.getStartDate())) {
            itineraryService.shiftDates(tripId, ChronoUnit.DAYS.between(oldStart, trip.getStartDate()));
        }
        if (req.getBudget() != null) trip.setBudget(req.getBudget());
        return tripRepository.save(trip);
    }

    /**
     * 行程状态：PLANNING（待确认）/ CONFIRMED（已确认）/ CANCELLED（已取消）。
     * 允许取消后恢复；阶段（未开始/进行中/已结束）由日期派生，不在这里存。
     */
    @Transactional
    public Trip updateStatus(Long tripId, Long userId, String status) {
        Trip trip = getOwned(tripId, userId);
        String normalized = status == null ? "" : status.trim().toUpperCase();
        if (!Set.of(Trip.STATUS_PLANNING, Trip.STATUS_CONFIRMED, Trip.STATUS_CANCELLED).contains(normalized)) {
            throw new BusinessException(400, "不支持的行程状态: " + status);
        }
        if (!normalized.equals(trip.getStatus())) {
            trip.setStatus(normalized);
            trip = tripRepository.save(trip);
        }
        return trip;
    }

    /** 忽略该行程的出发提醒 */
    @Transactional
    public Trip dismissDepartureReminder(Long tripId, Long userId) {
        Trip trip = getOwned(tripId, userId);
        if (trip.getDepartureReminderDismissedAt() == null) {
            trip.setDepartureReminderDismissedAt(java.time.LocalDateTime.now());
            trip = tripRepository.save(trip);
        }
        return trip;
    }

    /**
     * 删除行程：DB 级联清理在一个事务里完成；
     * 照片文件属于外部副作用（磁盘/S3 不参与 JDBC 事务），改成事务提交后再删。
     *
     * 旧实现先删文件再删行：一旦后续 DB 删除失败回滚，行还在而照片已被永久删除（不可逆裂图）。
     * 现在回滚只会留下孤儿文件（可后台补偿清理），不再丢用户数据。
     */
    @Transactional
    public void delete(Long tripId, Long userId) {
        Trip trip = getOwned(tripId, userId);
        List<String> photoFiles = tripPhotoRepository.findByTripIdOrderByCreatedAtDesc(trip.getId()).stream()
                .map(com.travel.agent.entity.TripPhoto::getStoredName)
                .filter(java.util.Objects::nonNull)
                .toList();
        // 级联清理元数据：照片、账单、操作历史、行程项
        tripPhotoRepository.deleteByTripId(trip.getId());
        expenseRepository.deleteByTripId(trip.getId());
        operationHistoryRepository.deleteByTripId(trip.getId());
        itineraryItemRepository.deleteByTripId(trip.getId());
        tripRepository.delete(trip);
        // 文件删除安排在 afterCommit：提交成功才删，失败只记日志
        deletePhotoFilesAfterCommit(photoFiles);
    }

    /** 把「删照片文件」挂到当前事务提交之后；没有事务时直接执行（保持方法单独调用也能工作） */
    private void deletePhotoFilesAfterCommit(List<String> storedNames) {
        if (storedNames == null || storedNames.isEmpty()) {
            return;
        }
        if (TransactionSynchronizationManager.isSynchronizationActive()
                && TransactionSynchronizationManager.isActualTransactionActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    deletePhotoFilesQuietly(storedNames);
                }
            });
        } else {
            deletePhotoFilesQuietly(storedNames);
        }
    }

    /** 删除失败只告警：DB 已提交，残留文件不影响功能，也不能再影响删除结果 */
    private void deletePhotoFilesQuietly(List<String> storedNames) {
        for (String storedName : storedNames) {
            try {
                photoStorage.deleteQuietly(storedName);
            } catch (RuntimeException e) {
                log.warn("照片文件删除失败（行程记录已删除，残留文件待清理）: {}", storedName, e);
            }
        }
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
