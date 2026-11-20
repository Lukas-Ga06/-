package com.travel.agent.service;

import com.travel.agent.dto.PhotoDtos;
import com.travel.agent.dto.TravelReviewDtos;
import com.travel.agent.entity.ItineraryItem;
import com.travel.agent.entity.OperationHistory;
import com.travel.agent.entity.Trip;
import com.travel.agent.entity.TripEvent;
import com.travel.agent.entity.TripPhoto;
import com.travel.agent.llm.LlmClient;
import com.travel.agent.llm.LlmMessage;
import com.travel.agent.repository.MemoRepository;
import com.travel.agent.repository.OperationHistoryRepository;
import com.travel.agent.repository.ScheduleItemRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;

/**
 * 旅行回顾聚合（P1）：完成率、计划/实际花费、照片、到访地点、实际路线、事件与修改历史。
 *
 * <p>只读，不写库；所有口径都以「实际发生」为准：
 * <ul>
 *   <li>完成率 = COMPLETED / 全部条目；SKIPPED 单列，不冒充完成；</li>
 *   <li>visitedPlaces / actualRoute 只看 visitedAt；计划时间再早也不算去过；</li>
 *   <li>实际支出来自 expense，计划成本来自 itinerary_item.cost，互不覆盖。</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class TripReviewService {

    private static final int PHOTO_LIMIT = 12;
    private static final int HISTORY_LIMIT = 20;
    private static final int DEFAULT_EVENT_LIMIT = 20;

    private final ItineraryService itineraryService;
    private final BudgetService budgetService;
    private final ExpenseService expenseService;
    private final PhotoService photoService;
    private final TripEventService tripEventService;
    private final OperationHistoryRepository historyRepository;
    private final ScheduleItemRepository scheduleRepository;
    private final MemoRepository memoRepository;
    private final LlmClient llmClient;

    public TravelReviewDtos.TripReview forTrip(Trip trip) {
        return forTrip(trip, DEFAULT_EVENT_LIMIT);
    }

    public TravelReviewDtos.TripReview forTrip(Trip trip, int eventLimit) {
        List<ItineraryItem> items = itineraryService.listEntities(trip.getId());
        int total = items.size();
        int completed = (int) items.stream()
                .filter(i -> ItineraryItem.STATUS_COMPLETED.equals(i.getStatus())).count();
        int skipped = (int) items.stream()
                .filter(i -> ItineraryItem.STATUS_SKIPPED.equals(i.getStatus())).count();
        int inProgress = (int) items.stream()
                .filter(i -> ItineraryItem.STATUS_IN_PROGRESS.equals(i.getStatus())).count();
        int pending = total - completed - skipped - inProgress;
        double completionRate = total == 0 ? 0 : round2(completed * 100.0 / total) / 100.0;

        BigDecimal plannedCost = budgetService.totalOf(items);
        BigDecimal actualCost = expenseService.totalOf(trip.getId());
        BigDecimal variance = actualCost.subtract(plannedCost);
        boolean overBudget = trip.getBudget() != null && actualCost.compareTo(trip.getBudget()) > 0;

        List<TravelReviewDtos.Place> plannedPlaces = items.stream()
                .filter(i -> i.getLatitude() != null && i.getLongitude() != null)
                .sorted(Comparator.comparing(ItineraryItem::getDayIndex,
                                Comparator.nullsLast(Integer::compareTo))
                        .thenComparing(ItineraryItem::getStartTime,
                                Comparator.nullsLast(Comparator.naturalOrder())))
                .map(TripReviewService::place)
                .toList();

        List<ItineraryItem> visitedItems = items.stream()
                .filter(i -> i.getVisitedAt() != null)
                .sorted(Comparator.comparing(ItineraryItem::getVisitedAt)
                        .thenComparing(ItineraryItem::getId, Comparator.nullsLast(Long::compareTo)))
                .toList();
        List<TravelReviewDtos.Place> visitedPlaces = visitedItems.stream()
                .map(TripReviewService::place)
                .toList();
        List<TravelReviewDtos.RouteStop> actualRoute = visitedItems.stream()
                .filter(i -> i.getLatitude() != null && i.getLongitude() != null)
                .map(i -> TravelReviewDtos.RouteStop.builder()
                        .itemId(i.getId())
                        .title(i.getTitle())
                        .visitedAt(i.getVisitedAt() == null ? null : i.getVisitedAt().toString())
                        .latitude(i.getLatitude())
                        .longitude(i.getLongitude())
                        .build())
                .toList();

        List<TripPhoto> photoEntities = photoService.list(trip.getId(), trip.getUserId());
        int photoCount = photoEntities.size();
        List<PhotoDtos.Photo> photos = photoEntities.stream()
                .limit(PHOTO_LIMIT)
                .map(PhotoDtos.Photo::from)
                .toList();

        List<TravelReviewDtos.EventView> events = tripEventService.list(trip.getId(), eventLimit).stream()
                .map(TripReviewService::event)
                .toList();
        List<TravelReviewDtos.HistoryView> modifications = historyRepository
                .findByTripIdOrderByIdDesc(trip.getId()).stream()
                .limit(HISTORY_LIMIT)
                .map(TripReviewService::history)
                .toList();

        int memoCount = (int) memoRepository.countByTripId(trip.getId());
        int scheduleCount = (int) scheduleRepository.countByTripId(trip.getId());

        TravelReviewDtos.TripReview review = TravelReviewDtos.TripReview.builder()
                .tripId(trip.getId())
                .name(trip.getName())
                .destination(trip.getDestination())
                .startDate(trip.getStartDate() == null ? null : trip.getStartDate().toString())
                .endDate(trip.getEndDate() == null ? null : trip.getEndDate().toString())
                .totalDays(trip.totalDays())
                .phase(trip.phase(LocalDate.now()))
                .totalItems(total)
                .completedItems(completed)
                .skippedItems(skipped)
                .inProgressItems(inProgress)
                .pendingItems(Math.max(pending, 0))
                .completionRate(completionRate)
                .plannedCost(plannedCost)
                .actualCost(actualCost)
                .budget(trip.getBudget())
                .variance(variance)
                .overBudget(overBudget)
                .plannedPlaces(plannedPlaces)
                .visitedPlaces(visitedPlaces)
                .actualRoute(actualRoute)
                .photoCount(photoCount)
                .photos(photos)
                .events(events)
                .modifications(modifications)
                .memoCount(memoCount)
                .scheduleCount(scheduleCount)
                .build();
        review.setAiSummary(summary(review));
        return review;
    }

    /** 确定性总结：只复述上面的事实，不编造天气/照片内容；LLM 可用时在此基础上润色 */
    private String summary(TravelReviewDtos.TripReview review) {
        if (review.getTotalItems() == 0) {
            return "这趟行程还没有安排，暂时没有可回顾的内容。";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("完成 ").append(review.getCompletedItems()).append("/").append(review.getTotalItems())
                .append(" 项安排（完成率 ").append(Math.round(review.getCompletionRate() * 100)).append("%）");
        if (review.getSkippedItems() > 0) {
            sb.append("，跳过 ").append(review.getSkippedItems()).append(" 项");
        }
        sb.append("；实际支出 ¥").append(money(review.getActualCost()))
                .append("（计划 ¥").append(money(review.getPlannedCost())).append("）");
        if (review.getBudget() != null) {
            sb.append(review.isOverBudget() ? "，已超出预算" : "，仍在预算内");
        }
        sb.append("；实际到访 ").append(review.getVisitedPlaces().size()).append(" 个地点");
        if (review.getPhotoCount() > 0) {
            sb.append("，留下 ").append(review.getPhotoCount()).append(" 张照片");
        }
        sb.append("。");
        String facts = sb.toString();
        if (!llmClient.available()) {
            return facts;
        }
        try {
            String text = llmClient.chat(List.of(
                    LlmMessage.system("你是旅行回顾助手。只能根据给出的真实数据写一段 80 字以内的中文总结，"
                            + "不要编造天气、人物、照片内容或未到访的地点。"),
                    LlmMessage.user("旅行数据：" + facts)), 0.3, 8000);
            return text == null || text.isBlank() ? facts : text.trim();
        } catch (RuntimeException e) {
            log.warn("旅行回顾 AI 总结失败，使用确定性文案: {}", e.getMessage());
            return facts;
        }
    }

    private static TravelReviewDtos.Place place(ItineraryItem item) {
        return TravelReviewDtos.Place.builder()
                .itemId(item.getId())
                .dayIndex(item.getDayIndex())
                .itemType(item.getItemType())
                .title(item.getTitle())
                .status(item.getStatus())
                .plannedAt(item.getStartTime() == null ? null : item.getStartTime().toString())
                .visitedAt(item.getVisitedAt() == null ? null : item.getVisitedAt().toString())
                .latitude(item.getLatitude())
                .longitude(item.getLongitude())
                .build();
    }

    private static TravelReviewDtos.EventView event(TripEvent event) {
        return TravelReviewDtos.EventView.builder()
                .id(event.getId())
                .itemId(event.getItineraryItemId())
                .type(event.getEventType())
                .source(event.getSource())
                .summary(event.getSummary())
                .occurredAt(event.getOccurredAt() == null ? null : event.getOccurredAt().toString())
                .build();
    }

    private static TravelReviewDtos.HistoryView history(OperationHistory history) {
        return TravelReviewDtos.HistoryView.builder()
                .id(history.getId())
                .operationType(history.getOperationType())
                .reason(history.getAgentReason())
                .at(history.getCreatedAt() == null ? null : history.getCreatedAt().toString())
                .build();
    }

    private static String money(BigDecimal value) {
        BigDecimal safe = value == null ? BigDecimal.ZERO : value;
        return safe.setScale(2, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString();
    }

    private static double round2(double value) {
        return Math.round(value * 100.0) / 100.0;
    }
}
