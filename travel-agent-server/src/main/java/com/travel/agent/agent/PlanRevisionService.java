package com.travel.agent.agent;

import com.travel.agent.entity.ScheduleItem;
import com.travel.agent.entity.Trip;
import com.travel.agent.service.ItineraryService;
import com.travel.agent.service.ScheduleService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * 待确认方案的版本指纹（R4 双指纹里的 {@code planRevision}）。
 *
 * <p>行程场景沿用 {@link ItineraryService#planRevision}；日程不在 {@code itinerary_item} 的指纹里，
 * 所以批量清空日程的方案必须再把「该用户、该日期范围内的全部日程（含已完成）」折进指纹——
 * 确认期间另一个标签页新增/删除/改了范围内的日程，这份 Diff 就失效。
 *
 * <p>生成（{@code ScheduleCommandHandler}）与确认（{@code PlanConfirmationService}）都调这里，
 * 两侧必须对称；指纹变了就拒绝提交，语义与行程版本校验一致。
 *
 * <p>放 agent 包而不是 service：只有 agent 侧的生成与确认两处用它，放 service 会让
 * service 反向依赖 agent（依赖方向见 docs/guide/code-structure.md §2）。
 */
@Component
@RequiredArgsConstructor
public class PlanRevisionService {

    /** extras 里的范围键：ISO-8601 日期字符串（plan_json 反序列化后仍是 String） */
    static final String SCOPE_FROM = "scheduleScopeFrom";
    static final String SCOPE_TO = "scheduleScopeTo";

    private final ItineraryService itineraryService;
    private final ScheduleService scheduleService;

    /**
     * 方案指纹。行程或日程范围至少有一个才有意义；两者都没有返回 null（新建行程型方案不绑定指纹）。
     *
     * <p>{@code pending_plan.plan_revision} 是 VARCHAR(64)，所以「行程基指纹 + 日程摘要」不能再各占
     * 一个 32 字符的 hex 直接拼起来；同时含两者时把两段材料一起做一次 SHA-256。
     */
    public String revision(AgentPlan plan, Trip trip, Long userId) {
        String base = trip == null ? null
                : ItineraryService.planRevision(trip, itineraryService.listEntities(trip.getId()));
        String scheduleSummary = scheduleSummary(plan, userId);
        if (base == null && scheduleSummary == null) {
            return null;
        }
        if (base == null) {
            return "s:" + sha256(scheduleSummary);
        }
        if (scheduleSummary == null) {
            return base;
        }
        return sha256(base + "|schedule=" + scheduleSummary);
    }

    /** 方案是否带日程范围（确认侧据此决定要不要做日程指纹校验） */
    public static boolean hasScheduleScope(AgentPlan plan) {
        return scheduleRange(plan) != null;
    }

    /** 方案 extras 里的日程范围；没有或不完整时返回 null */
    public static MessageParser.DateRange scheduleRange(AgentPlan plan) {
        if (plan == null) {
            return null;
        }
        LocalDate from = scopeDate(plan.getExtras(), SCOPE_FROM);
        LocalDate to = scopeDate(plan.getExtras(), SCOPE_TO);
        return from == null || to == null ? null : new MessageParser.DateRange(from, to);
    }

    private static LocalDate scopeDate(Map<String, Object> extras, String key) {
        if (extras == null) {
            return null;
        }
        Object raw = extras.get(key);
        if (raw == null) {
            return null;
        }
        try {
            return LocalDate.parse(String.valueOf(raw));
        } catch (RuntimeException e) {
            // 脏值当作没有范围：宁可少一次校验，也不要让确认链路抛异常
            return null;
        }
    }

    /**
     * 该用户、该范围内全部日程的字段摘要；没有范围（或没有用户）时返回 null。
     *
     * <p>必须包含已完成（done）的日程：清空动作会删掉它们，确认期间有人点掉提醒/打卡
     * 也属于「范围内的日程变了」。
     */
    private String scheduleSummary(AgentPlan plan, Long userId) {
        MessageParser.DateRange range = scheduleRange(plan);
        if (range == null || userId == null) {
            return null;
        }
        List<ScheduleItem> items = scheduleService.findInRange(userId, range.from(), range.to());
        StringBuilder sb = new StringBuilder();
        items.stream()
                .sorted(Comparator.comparing(ScheduleItem::getId, Comparator.nullsLast(Long::compareTo)))
                .forEach(item -> sb.append('|').append(item.getId())
                        .append(':').append(item.getDate())
                        .append(':').append(item.getTime())
                        .append(':').append(item.getTitle())
                        .append(':').append(item.getNote())
                        .append(':').append(item.getRecurrence())
                        .append(':').append(item.getRepeatUntil())
                        .append(':').append(item.isDone())
                        .append(':').append(item.getTripId())
                        .append(':').append(item.getTravelDay())
                        .append(':').append(item.getSource())
                        .append(':').append(item.getSourceKey())
                        .append(':').append(item.getReminderDismissedAt()));
        return sb.toString();
    }

    /** SHA-256 前 16 字节（32 字符 hex），与 {@link ItineraryService#planRevision} 的取值口径一致 */
    private static String sha256(String input) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256")
                    .digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (int i = 0; i < 16; i++) {
                hex.append(String.format("%02x", hash[i]));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("当前 JVM 不支持 SHA-256", e);
        }
    }
}