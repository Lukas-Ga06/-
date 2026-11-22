package com.travel.agent.agent;

import com.travel.agent.entity.Trip;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * 规划需求槽位（FR-01）：一句话里能确定的「目的地 / 天数 / 人数 / 预算 / 出发日期」。
 *
 * <p>为什么需要它：规划器原来在所有字段缺失时都用默认值兜底（3 天 / ¥5000），
 * 用户没说清楚也照规划——<b>不是问，而是猜</b>。本类只负责「解析 + 合并 + 缺哪些」；
 * 追问、兜底与文案由 {@link AgentEngine} 决定（见 ADR-010）。
 *
 * <p>必填槽位只有三个：<b>目的地 / 天数 / 人数</b>。预算与出发日期可省略——
 * 缺了就用默认值（¥5000 / 明天）并在回复里明示，不静默替用户决定。
 *
 * <p>槽位是<b>一次规划会话内的短期状态</b>：跨轮累积的依据是会话历史里
 * 「上一轮助手是需求追问」（{@code intent=PLAN_TRIP_NEED_INFO}）的标记，
 * 且只读取该片段内用户自己说过的话。它不进入长期记忆，也不改变
 * 「历史只用于指代消解」的既有边界（见 {@code ConversationService} 类注释与 ADR-010）。
 *
 * @param destination 目的地（城市名）
 * @param days        天数
 * @param partySize   同行人数（含儿童，1..20）
 * @param budget      总预算（元）
 * @param startDate   出发日期
 */
public record RequirementSlots(String destination, Integer days, Integer partySize,
                               BigDecimal budget, LocalDate startDate) {

    /** 天数兜底：与规划器历史默认值一致 */
    public static final int DEFAULT_DAYS = 3;
    /** 人数兜底：1 人（与「未接入人数」时代的单人成本口径一致，升级前后预算不突变） */
    public static final int DEFAULT_PARTY_SIZE = 1;
    /** 预算兜底：与规划器历史默认值一致 */
    public static final BigDecimal DEFAULT_BUDGET = BigDecimal.valueOf(5000);
    /** 用户拒绝回答且连目的地都没给时的兜底城市（明示后仍可改） */
    public static final String DEFAULT_DESTINATION = "上海";
    /** 人数上限：与 trip.party_size 的校验口径一致 */
    public static final int MAX_PARTY_SIZE = 20;

    /** 空槽位：什么都没解析到 */
    public static RequirementSlots empty() {
        return new RequirementSlots(null, null, null, null, null);
    }

    /**
     * 从一条用户消息做确定性解析（规则路径 / 追问后的短回答都走它）。
     *
     * <p>只认用户明确说过的值，不做任何推断：解析不出就是 {@code null}。
     */
    public static RequirementSlots fromMessage(String message) {
        if (message == null || message.isBlank()) {
            return empty();
        }
        MessageParser.DepartureDate date = MessageParser.extractDepartureDate(message, LocalDate.now());
        int days = MessageParser.extractDays(message);
        int party = MessageParser.extractPartySize(message);
        String city = MessageParser.guessCityName(message);
        if (city == null && !isDecline(message)) {
            // 追问后的极短回答：「上海」「北京」整句就是城市名。
            // 「你决定吧」这类拒绝回答也是 2-4 个汉字，但绝不能被当成城市名。
            city = MessageParser.guessBareCityName(message);
        }
        return new RequirementSlots(
                city,
                days > 0 ? days : null,
                party > 0 ? party : null,
                MessageParser.extractBudget(message),
                date.explicit() ? date.date() : null);
    }

    /**
     * 从已有行程取槽位（重新规划整份行程时沿用原行程的目的地/天数/人数/预算/日期）。
     * 人数为空的历史行程按 1 人处理：这是升级前的成本口径。
     */
    public static RequirementSlots fromTrip(Trip trip) {
        if (trip == null) {
            return empty();
        }
        int days = trip.totalDays();
        return new RequirementSlots(
                blankToNull(trip.getDestination()),
                days > 0 ? days : null,
                effectiveParty(trip.getPartySize()),
                trip.getBudget(),
                trip.getStartDate());
    }

    /** 必填槽位中缺失的键：destination / days / partySize */
    public List<String> missingRequired() {
        List<String> missing = new ArrayList<>(3);
        if (destination == null || destination.isBlank()) {
            missing.add("destination");
        }
        if (days == null || days <= 0) {
            missing.add("days");
        }
        if (partySize == null || partySize <= 0) {
            missing.add("partySize");
        }
        return missing;
    }

    /**
     * 用更早的槽位填补本槽位的空缺；<b>本槽位（较新）优先</b>。
     * 用于「当前消息 > 会话里此前说过的话 > 已有行程」的合并方向。
     */
    public RequirementSlots fillMissingFrom(RequirementSlots older) {
        if (older == null) {
            return this;
        }
        return new RequirementSlots(
                destination == null || destination.isBlank() ? older.destination : destination,
                days == null ? older.days : days,
                partySize == null ? older.partySize : partySize,
                budget == null ? older.budget : budget,
                startDate == null ? older.startDate : startDate);
    }

    /**
     * 补齐可省略槽位的默认值（只应在「用户拒绝回答」或「追问已达上限」时调用）。
     * 目的地缺失也按 {@link #DEFAULT_DESTINATION} 兜底，但调用方必须把兜底项写进回复。
     *
     * @param today 用于计算「明天」的基准日期（测试可传固定值）
     */
    public RequirementSlots withDefaults(LocalDate today) {
        LocalDate base = today == null ? LocalDate.now() : today;
        return new RequirementSlots(
                destination == null || destination.isBlank() ? DEFAULT_DESTINATION : destination.trim(),
                days == null || days <= 0 ? DEFAULT_DAYS : days,
                partySize == null || partySize <= 0 ? DEFAULT_PARTY_SIZE : effectiveParty(partySize),
                budget == null ? DEFAULT_BUDGET : budget,
                startDate == null ? base.plusDays(1) : startDate);
    }

    /** 是否与另一组槽位在「必填项」上完全相同（用于判断本轮有没有补到新信息） */
    public boolean sameRequired(RequirementSlots other) {
        if (other == null) {
            return false;
        }
        return equal(destination, other.destination) && equal(days, other.days)
                && equal(partySize, other.partySize);
    }

    /** 用户在追问后明确表示「你定吧」：允许用默认值继续，而不是再问一轮 */
    public static boolean isDecline(String message) {
        return MessageParser.mentions(message, "随便", "你决定", "你看着办", "你来定", "你安排",
                "都行", "都可以", "听你的", "不用问", "别问了", "不想说", "没想好", "不知道", "不确定",
                "没概念", "无所谓", "简单点", "帮我定");
    }

    /** 人数口径：null/≤0 → 1，超过上限截断（与 Trip.partySize 校验一致） */
    public static int effectiveParty(Integer partySize) {
        if (partySize == null || partySize <= 0) {
            return DEFAULT_PARTY_SIZE;
        }
        return Math.min(partySize, MAX_PARTY_SIZE);
    }

    private static boolean equal(Object a, Object b) {
        return a == null ? b == null : a.equals(b);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}