package com.travel.agent.service;

import com.travel.agent.agent.PlanOperation;
import com.travel.agent.dto.ItineraryDtos;
import com.travel.agent.entity.ItineraryItem;
import com.travel.agent.entity.Trip;
import com.travel.agent.exception.BusinessException;
import com.travel.agent.repository.ItineraryItemRepository;
import com.travel.agent.repository.TripRepository;
import com.travel.agent.util.Paging;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
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

    /**
     * 单行程条目响应上限（DB 层 limit 兜底）。
     * 行程条目量受行程天数约束，正常远小于该值；这里只用数据库 LIMIT 防止异常数据把响应撑爆，
     * 不改变「返回该行程全部安排」的语义，也不引入分页元数据。
     */
    private static final int MAX_ITINERARY_ROWS = Paging.MAX_LIMIT;

    private final ItineraryItemRepository repository;
    /** 行程城市与日期：价格深链（携程 cityId / checkin+checkout）以它为准，不信任条目自身字段 */
    private final TripRepository tripRepository;
    /** 参考价自报（ADR-006）：纯本地推算，无网络调用 */
    private final PriceReferenceService priceReferenceService;
    /** P1：行程项状态变化写入旅行事件（打卡/跳过留痕） */
    private final TripEventService tripEventService;

    public List<ItineraryDtos.ItineraryItemDTO> listByTrip(Long tripId) {
        Pageable pageable = PageRequest.of(0, MAX_ITINERARY_ROWS,
                Sort.by(Sort.Order.asc("dayIndex"), Sort.Order.asc("startTime"), Sort.Order.asc("id")));
        // 同一事务/持久化上下文内该查询会被缓存；这里只为拿到城市与起止日期，不改变返回语义
        Trip trip = tripRepository.findById(tripId).orElse(null);
        return repository.findByTripId(tripId, pageable).stream()
                .map(item -> toDto(item, trip))
                .toList();
    }

    /**
     * 实体 → DTO（带参考价）。
     *
     * <p>列表、手动新增、手动修改、Agent 工具等所有「对外返回行程项」的路径都必须走这里，
     * 否则会出现「列表有 price、新增返回没有」的形状不一致（前端同一套渲染代码）。
     * 内部快照/还原（{@link ItineraryDtos.ItineraryItemDTO#from(ItineraryItem)}）刻意不带价格：
     * 价格是展示层的实时推算结果，不落库、不进撤销快照，读取时按当前行程重算。
     */
    public ItineraryDtos.ItineraryItemDTO toDto(ItineraryItem item, Trip trip) {
        return ItineraryDtos.ItineraryItemDTO.from(item, priceReferenceService.build(item, trip));
    }

    public List<ItineraryItem> listEntities(Long tripId) {
        return repository.findByTripIdOrderByDayIndexAscStartTimeAsc(tripId);
    }

    public ItineraryItem findById(Long id) {
        return repository.findById(id)
                .orElseThrow(() -> new BusinessException(404, "行程条目不存在"));
    }

    /**
     * 条目的「落点日期」由 {@code trip.startDate + dayIndex - 1} 派生（表里只存 dayIndex），
     * 所以「日期不超出行程跨度」等价于 {@code 1 <= dayIndex <= 行程天数}。
     * 越界的安排会算出行程之外的日期（路书/日程/天气都按它取数），在写入入口直接拒绝。
     */
    private static void requireDayWithinTrip(Trip trip, int dayIndex) {
        int totalDays = trip.totalDays();
        if (dayIndex < 1 || dayIndex > totalDays) {
            throw new BusinessException(400,
                    "第 " + dayIndex + " 天超出行程范围（当前共 " + totalDays + " 天）");
        }
    }

    @Transactional
    public ItineraryItem addItem(Trip trip, ItineraryDtos.AddItemRequest req) {
        if (req.getTitle() == null || req.getTitle().isBlank()) {
            throw new BusinessException("标题不能为空");
        }
        int day = req.getDayIndex() == null ? 1 : req.getDayIndex();
        requireDayWithinTrip(trip, day);
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
        if (req.getDayIndex() != null) {
            requireDayWithinTrip(trip, req.getDayIndex());
            item.setDayIndex(req.getDayIndex());
        }
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
    public ItineraryItem updateStatus(Trip trip, Long itemId, String status) {
        ItineraryItem item = repository.findById(itemId)
                .filter(i -> i.getTripId().equals(trip.getId()))
                .orElseThrow(() -> new BusinessException(404, "行程条目不存在"));
        String normalized = status == null ? "" : status.trim().toUpperCase();
        if (!java.util.Set.of(ItineraryItem.STATUS_PENDING, ItineraryItem.STATUS_IN_PROGRESS,
                ItineraryItem.STATUS_COMPLETED, ItineraryItem.STATUS_SKIPPED).contains(normalized)) {
            throw new BusinessException(400, "未知的行程项状态: " + status);
        }
        java.time.LocalDateTime now = java.time.LocalDateTime.now();
        switch (normalized) {
            case ItineraryItem.STATUS_PENDING -> {
                // 重置为未开始：清掉实际时间，回到可被自动重规划调整的状态
                item.setStartedAt(null);
                item.setCompletedAt(null);
                item.setVisitedAt(null);
            }
            case ItineraryItem.STATUS_IN_PROGRESS -> {
                if (item.getStartedAt() == null) {
                    item.setStartedAt(now);
                }
                item.setCompletedAt(null);
                item.setVisitedAt(null);
            }
            case ItineraryItem.STATUS_COMPLETED -> {
                if (item.getStartedAt() == null) {
                    item.setStartedAt(now);
                }
                item.setCompletedAt(now);
                // 实际到访时间 = 完成时间：足迹只认 visitedAt，不把计划时间当成「去过」
                item.setVisitedAt(now);
            }
            case ItineraryItem.STATUS_SKIPPED -> {
                item.setCompletedAt(now);
                item.setVisitedAt(null);
            }
            default -> throw new BusinessException(400, "未知的行程项状态: " + status);
        }
        item.setStatus(normalized);
        ItineraryItem saved = repository.save(item);
        // 打卡/完成/跳过写旅行事件；重置回未开始不记事件（避免把「撤销打卡」记成一次打卡）
        if (!ItineraryItem.STATUS_PENDING.equals(normalized)) {
            String summary = switch (normalized) {
                case ItineraryItem.STATUS_IN_PROGRESS -> "开始：" + item.getTitle();
                case ItineraryItem.STATUS_COMPLETED -> "已完成：" + item.getTitle();
                case ItineraryItem.STATUS_SKIPPED -> "已跳过：" + item.getTitle();
                default -> "状态更新：" + item.getTitle();
            };
            tripEventService.record(trip.getId(), item.getId(),
                    ItineraryItem.STATUS_COMPLETED.equals(normalized)
                            ? com.travel.agent.entity.TripEvent.TYPE_CHECK_IN
                            : com.travel.agent.entity.TripEvent.TYPE_MANUAL_EDIT,
                    "user", summary);
        }
        return saved;
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

    /**
     * 用快照整体替换行程（撤销用）。
     *
     * 按 id 做 diff，而不是「deleteByTripId + saveAll」：
     * - 快照里带 id 且该条目仍属于本行程 → 就地更新（id 保持不变，不会让历史快照/前端选中失效）；
     * - 快照里带 id 但条目已不存在（例如中途被手工删掉）→ 作为新增插入；
     * - 当前存在但快照里没有的条目 → 删除。
     * 全删全插除了 id 抖动之外，还会把「撤销期间新写入但不在旧快照里的条目」一起抹掉。
     */
    @Transactional
    public void replaceAll(Long tripId, List<ItineraryItem> items) {
        List<ItineraryItem> existing = repository.findByTripIdOrderByDayIndexAscStartTimeAsc(tripId);
        java.util.Map<Long, ItineraryItem> existingById = existing.stream()
                .filter(item -> item.getId() != null)
                .collect(java.util.stream.Collectors.toMap(ItineraryItem::getId,
                        java.util.function.Function.identity()));
        java.util.Set<Long> kept = new java.util.HashSet<>();
        for (ItineraryItem desired : items) {
            Long id = desired.getId();
            ItineraryItem target = id == null ? null : existingById.get(id);
            // kept.add 返回 false = 同一快照里重复出现的 id，按新增处理，避免两条快照条目合并成一条
            if (target == null || !kept.add(id)) {
                desired.setId(null);
                desired.setTripId(tripId);
                repository.save(desired);
            } else {
                copyFields(desired, target, tripId);
                repository.save(target);
            }
        }
        existing.stream()
                .filter(item -> item.getId() != null && !kept.contains(item.getId()))
                .forEach(repository::delete);
    }

    /** 把快照条目（desired）的字段就地写到已有实体（target）上，保持 id 与 tripId 归属 */
    private void copyFields(ItineraryItem desired, ItineraryItem target, Long tripId) {
        target.setTripId(tripId);
        target.setDayIndex(desired.getDayIndex() == null ? 1 : desired.getDayIndex());
        target.setItemType(desired.getItemType());
        target.setTitle(desired.getTitle());
        target.setStartTime(desired.getStartTime());
        target.setEndTime(desired.getEndTime());
        target.setLatitude(desired.getLatitude());
        target.setLongitude(desired.getLongitude());
        target.setCost(desired.getCost());
        target.setTags(desired.getTags());
        target.setDescription(desired.getDescription());
        // 执行状态随快照还原：撤销「手动改行程」不应把已完成的打卡重置掉
        target.setStatus(desired.getStatus() == null ? ItineraryItem.STATUS_PENDING : desired.getStatus());
        target.setStartedAt(desired.getStartedAt());
        target.setCompletedAt(desired.getCompletedAt());
        target.setVisitedAt(desired.getVisitedAt());
    }

    /**
     * 行程改期：把该行程所有条目的绝对时间平移 N 天（dayIndex 不变）。
     * 条目时间是「trip.startDate + dayIndex」算出来的，所以 Trip 改期必须同步平移，
     * 否则会出现"行程 10/8 出发、安排还停在 10/1"这种自相矛盾的数据。
     */
    @Transactional
    public int shiftDates(Long tripId, long days) {
        if (days == 0) {
            return 0;
        }
        List<ItineraryItem> items = repository.findByTripIdOrderByDayIndexAscStartTimeAsc(tripId);
        for (ItineraryItem item : items) {
            if (item.getStartTime() != null) {
                item.setStartTime(item.getStartTime().plusDays(days));
            }
            if (item.getEndTime() != null) {
                item.setEndTime(item.getEndTime().plusDays(days));
            }
        }
        repository.saveAll(items);
        return items.size();
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

    /**
     * 计划输入指纹：行程日期 + 预算 + 全部条目（id/天/标题/时间/费用/类型）。
     *
     * 用途：生成 Diff 时记下指纹，用户确认时再算一次。若中间行程被改过（哪怕在另一个标签页），
     * 指纹不一致就拒绝提交，避免按过期 Diff 覆盖新数据。
     */
    public static String planRevision(Trip trip, List<ItineraryItem> items) {
        StringBuilder sb = new StringBuilder("b=").append(trip.getBudget())
                .append("|s=").append(trip.getStartDate())
                .append("|e=").append(trip.getEndDate());
        items.stream()
                .sorted(java.util.Comparator.comparing(ItineraryItem::getId,
                        java.util.Comparator.nullsLast(Long::compareTo)))
                .forEach(item -> sb.append('|').append(item.getId())
                        .append(':').append(item.getDayIndex())
                        .append(':').append(item.getTitle())
                        .append(':').append(item.getStartTime())
                        .append(':').append(item.getEndTime())
                        .append(':').append(item.getCost())
                        .append(':').append(item.getItemType())
                        .append(':').append(item.getStatus()));
        return sha256(sb.toString());
    }

    private static String sha256(String input) {
        try {
            byte[] hash = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(input.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (int i = 0; i < 16; i++) {
                hex.append(String.format("%02x", hash[i]));
            }
            return hex.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            return Integer.toHexString(input.hashCode());
        }
    }
}
