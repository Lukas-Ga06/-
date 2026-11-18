package com.travel.agent.agent;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/** 新行程草稿（PLAN_TRIP 确认后创建 Trip） */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TripDraft {
    private String name;
    private String destination;
    private LocalDate startDate;
    private Integer days;
    /** 同行人数（FR-01）：确认后落到 trip.party_size；为空按 1 人 */
    private Integer partySize;
    private BigDecimal budget;
    private List<String> preferences;
}
