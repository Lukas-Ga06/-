package com.travel.agent.service;

import com.travel.agent.entity.TripEvent;
import com.travel.agent.repository.TripEventRepository;
import com.travel.agent.util.Paging;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 旅行事件写入与读取（P1）。
 *
 * <p>事件流只追加：天气突变、拥堵、闭馆、打卡与 Agent 调整都留痕。
 * 写入方（Planner 局部重规划、行程项状态接口、手工修改）只需要给摘要与来源，
 * 不需要关心表结构；读取方（行程页、回顾页）按时间倒序拿最近若干条。
 */
@Service
@RequiredArgsConstructor
public class TripEventService {

    private final TripEventRepository repository;

    /** 记录一条事件（occurredAt 默认当前时间） */
    @Transactional
    public TripEvent record(Long tripId, Long itineraryItemId, String eventType, String source,
                            String summary, String detail) {
        if (tripId == null) {
            return null;
        }
        return repository.save(TripEvent.builder()
                .tripId(tripId)
                .itineraryItemId(itineraryItemId)
                .eventType(eventType)
                .source(source)
                .summary(truncate(summary))
                .detail(detail)
                .occurredAt(LocalDateTime.now())
                .build());
    }

    public TripEvent record(Long tripId, Long itineraryItemId, String eventType, String source, String summary) {
        return record(tripId, itineraryItemId, eventType, source, summary, null);
    }

    /** 最近事件（时间倒序）；limit 走 Paging 归一化，避免异常大的响应 */
    public List<TripEvent> list(Long tripId, int limit) {
        Pageable pageable = PageRequest.of(0, Paging.normalizeLimit(limit));
        return repository.findByTripIdOrderByOccurredAtDescIdDesc(tripId, pageable);
    }

    public long count(Long tripId) {
        return repository.countByTripId(tripId);
    }

    private static String truncate(String summary) {
        if (summary == null) {
            return null;
        }
        return summary.length() > 500 ? summary.substring(0, 500) : summary;
    }
}
