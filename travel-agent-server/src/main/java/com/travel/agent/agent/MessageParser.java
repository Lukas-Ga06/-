package com.travel.agent.agent;

import com.travel.agent.entity.Trip;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.temporal.TemporalAdjusters;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 自然语言解析工具：天数 / 预算 / 日期 */
public final class MessageParser {

    private static final Map<Character, Integer> CN_NUM = Map.ofEntries(
            Map.entry('一', 1), Map.entry('两', 2), Map.entry('二', 2), Map.entry('三', 3),
            Map.entry('四', 4), Map.entry('五', 5), Map.entry('六', 6), Map.entry('七', 7),
            Map.entry('八', 8), Map.entry('九', 9), Map.entry('十', 10));

    private MessageParser() {
    }

    /** 提取天数："3天" / "三天" / "三日游" / "周末" */
    public static int extractDays(String text) {
        if (text == null) {
            return -1;
        }
        // 先把日期片段去掉：否则「11月1日杭州三日游」会先匹配到日期里的「1日」，被当成 1 日游
        String stripped = stripDateExpressions(text);
        Matcher m = Pattern.compile("(\\d+)\\s*[天日]").matcher(stripped);
        if (m.find()) {
            return Integer.parseInt(m.group(1));
        }
        m = Pattern.compile("([一两二三四五六七八九十])\\s*[天日]").matcher(stripped);
        if (m.find()) {
            return CN_NUM.getOrDefault(m.group(1).charAt(0), -1);
        }
        if (text.contains("周末")) {
            return 2;
        }
        return -1;
    }

    /** 去掉文本里的日期片段（完整日期与月日），避免与「N 天/N 日」的天数解析互相干扰 */
    private static String stripDateExpressions(String text) {
        String stripped = FULL_DATE.matcher(text).replaceAll(" ");
        return MONTH_DAY.matcher(stripped).replaceAll(" ");
    }

    /** 提取预算："预算3000" / "3000元" */
    public static BigDecimal extractBudget(String text) {
        if (text == null) {
            return null;
        }
        Matcher m = Pattern.compile("(?:预算|budget)\\s*[:：]?\\s*([0-9,]{3,9})").matcher(text);
        if (m.find()) {
            return parse(m.group(1));
        }
        m = Pattern.compile("([0-9,]{3,9})\\s*(?:元|块|RMB|rmb)").matcher(text);
        if (m.find()) {
            return parse(m.group(1));
        }
        return null;
    }

    private static BigDecimal parse(String s) {
        return new BigDecimal(s.replace(",", ""));
    }

    public static boolean mentions(String text, String... keywords) {
        if (text == null) {
            return false;
        }
        for (String keyword : keywords) {
            if (text.contains(keyword)) {
                return true;
            }
        }
        return false;
    }

    /** 从消息中粗略猜测目的地名（目录外城市联网查询用） */
    public static String guessCityName(String text) {
        if (text == null) {
            return null;
        }
        String[] patterns = {
                "([\\u4e00-\\u9fa5]{2,4}?)(?:[0-9一二两三四五六七八]+日游|周末游|亲子游|游)",
                "去([\\u4e00-\\u9fa5]{2,4}?)(?:玩|旅行|旅游|看看|逛|的)",
                "([\\u4e00-\\u9fa5]{2,4}?)(?:旅行|旅游)"
        };
        for (String pattern : patterns) {
            Matcher m = Pattern.compile(pattern).matcher(text);
            if (m.find()) {
                String group = m.group(1);
                if (group != null && !group.isBlank()
                        && !java.util.Set.of("城市", "哪里", "那里", "这里", "地方", "一个").contains(group)) {
                    return group;
                }
            }
        }
        return null;
    }

    /** 记账请求解析结果 */
    public record ExpenseRequest(java.math.BigDecimal amount, String category, int dayOffset, String paymentMethod) {}

    /**
     * 解析记账请求中的金额、类别、日期偏移与支付方式；解析不出有效金额返回 null。
     * 例：「微信付了 45 元午餐」→ (45, 餐饮, 0, 微信支付)「昨天打车花了 30 块」→ (30, 交通, -1, 现金)
     */
    public static ExpenseRequest parseExpenseRequest(String text) {
        if (text == null) {
            return null;
        }
        java.math.BigDecimal amount = null;
        Matcher m = Pattern.compile("([0-9]+(?:\\.[0-9]+)?)\\s*(?:元|块钱|块)").matcher(text);
        if (m.find()) {
            amount = new java.math.BigDecimal(m.group(1));
        }
        if (amount == null) {
            m = Pattern.compile("(?:花了|花掉|记一笔|记账|记一下|支出)\\s*[:：]?\\s*([0-9]+(?:\\.[0-9]+)?)").matcher(text);
            if (m.find()) {
                amount = new java.math.BigDecimal(m.group(1));
            }
        }
        if (amount == null || amount.signum() <= 0) {
            return null;
        }
        String category = "其他";
        if (mentions(text, "门票", "景区票", "门票钱")) {
            category = "门票";
        } else if (mentions(text, "酒店", "住宿", "房费", "民宿")) {
            category = "住宿";
        } else if (mentions(text, "打车", "出租", "地铁", "公交", "高铁", "火车", "机票", "加油", "停车", "船票")) {
            category = "交通";
        } else if (mentions(text, "早", "午", "晚", "餐", "饭", "面", "小吃", "咖啡", "奶茶", "烧烤", "火锅", "小吃")) {
            category = "餐饮";
        } else if (mentions(text, "买", "购物", "纪念", "特产", "手信")) {
            category = "购物";
        }
        int dayOffset = mentions(text, "昨天", "昨日") ? -1 : 0;
        String payment = "现金";
        if (mentions(text, "微信")) {
            payment = "微信支付";
        } else if (mentions(text, "支付宝")) {
            payment = "支付宝";
        } else if (mentions(text, "银行卡", "刷卡")) {
            payment = "银行卡";
        }
        return new ExpenseRequest(amount, category, dayOffset, payment);
    }

    /** 日程请求解析结果 */
    public record ScheduleRequest(LocalDate date, java.time.LocalTime time, String title) {}

    /**
     * 解析日程请求：日期（今天/明天/后天，缺省今天）、时间（X点[X分]/HH:mm，可缺省）与事项标题。
     * 例：「明天下午3点开评审会」→ (明天日期, 15:00, 开评审会)
     */
    public static ScheduleRequest parseScheduleRequest(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        LocalDate date = mentions(text, "后天") ? LocalDate.now().plusDays(2)
                : mentions(text, "明天") ? LocalDate.now().plusDays(1)
                : LocalDate.now();
        java.time.LocalTime time = null;
        Matcher m = Pattern.compile("(上午|下午|中午|晚上|早上)?\\s*(\\d{1,2})\\s*[点时：:]\\s*(\\d{1,2})?\\s*分?").matcher(text);
        if (m.find()) {
            int hour = Integer.parseInt(m.group(2));
            String period = m.group(1) == null ? "" : m.group(1);
            if ((period.contains("下午") || period.contains("晚上")) && hour < 12) {
                hour += 12;
            }
            int minute = m.group(3) == null ? 0 : Integer.parseInt(m.group(3));
            if (hour >= 0 && hour <= 23 && minute >= 0 && minute <= 59) {
                time = java.time.LocalTime.of(hour, minute);
            }
            text = text.replace(m.group(), " ").trim();
        }
        text = text.replaceAll("(今天|明天|后天|下周[一二三四五六日天]?)", " ").trim();
        text = text.replaceAll("^(帮我|请|把|添加|加|新增|记录?)\\s*[:：,，、]?\\s*", "").trim();
        text = text.replaceAll("^(加入|加到)\\s*日程\\s*[:：,，、]?\\s*", "").trim();
        if (text.startsWith("日程")) {
            text = text.substring(2).trim();
        }
        if (text.isBlank()) {
            text = "日程事项";
        }
        return new ScheduleRequest(date, time, text);
    }

    /** 提取备忘内容：去掉「记一下/备忘/提醒我」等前缀词，返回剩余正文 */
    public static String parseMemoContent(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        String t = text.trim();
        String stripped = t
                .replaceFirst("^(帮我记一下|帮我记个|帮我记|记一下|记个|备忘录|备忘|提醒我|提醒|别忘了|记得)\\s*[:：,，、]?\\s*", "")
                .replaceFirst("[。.！!]$", "")
                .trim();
        return stripped.isEmpty() ? null : stripped;
    }

    /** 解析用户提到的日期（明天/后天/今天），否则回退 currentDay 对应日期 */
    public static LocalDate resolveDate(String text, Trip trip, Integer currentDay) {
        if (mentions(text, "后天")) {
            return LocalDate.now().plusDays(2);
        }
        if (mentions(text, "明天")) {
            return LocalDate.now().plusDays(1);
        }
        if (mentions(text, "今天", "今晚")) {
            return LocalDate.now();
        }
        if (trip != null && trip.getStartDate() != null) {
            int day = currentDay == null ? 1 : currentDay;
            return trip.getStartDate().plusDays(Math.max(day, 1) - 1L);
        }
        return LocalDate.now().plusDays(1);
    }

    /** 未提及相对日期时的哨兵值 */
    public static final int NO_RELATIVE_DATE = Integer.MIN_VALUE;

    /** 今天=0 / 明天=1 / 后天=2；未提及返回 NO_RELATIVE_DATE */
    public static int relativeDayOffset(String text) {
        if (text == null) {
            return NO_RELATIVE_DATE;
        }
        if (text.contains("后天")) {
            return 2;
        }
        if (text.contains("明天")) {
            return 1;
        }
        if (text.contains("今天") || text.contains("今晚")) {
            return 0;
        }
        return NO_RELATIVE_DATE;
    }

    /** 出发日期解析结果：explicit=false 表示用户没给日期 */
    public record DepartureDate(LocalDate date, String phrase, boolean explicit) {
        public static DepartureDate none() {
            return new DepartureDate(null, null, false);
        }
    }

    private static final Pattern FULL_DATE =
            Pattern.compile("(\\d{4})\\s*[-/年]\\s*(\\d{1,2})\\s*[-/月]\\s*(\\d{1,2})\\s*日?");
    private static final Pattern MONTH_DAY =
            Pattern.compile("(\\d{1,2})\\s*[月/]\\s*(\\d{1,2})\\s*日?");
    private static final Pattern WEEKDAY =
            Pattern.compile("(下周|下个星期|本周|这周|这个星期|星期|周)([一二三四五六日天])");

    /**
     * 解析「出发日期」。支持：
     * <ul>
     *   <li>完整日期：2026-10-01 / 2026年10月1日</li>
     *   <li>月日：10月1日 / 10/1（今年已过则顺延到明年）</li>
     *   <li>节日：国庆(10/1)、元旦(1/1)、劳动节·五一(5/1)（农历节日不支持）</li>
     *   <li>相对：今天 / 明天 / 后天</li>
     *   <li>周末：周末 / 下周末 / 周六 / 本周六 / 下周日</li>
     * </ul>
     *
     * @param today 用于计算相对日期（测试可传固定日期）
     */
    public static DepartureDate extractDepartureDate(String text, LocalDate today) {
        if (text == null || text.isBlank()) {
            return DepartureDate.none();
        }
        LocalDate base = today == null ? LocalDate.now() : today;

        // 1) 完整日期（优先，避免被下面的月日规则截断）
        Matcher full = FULL_DATE.matcher(text);
        if (full.find()) {
            try {
                LocalDate date = LocalDate.of(Integer.parseInt(full.group(1)),
                        Integer.parseInt(full.group(2)), Integer.parseInt(full.group(3)));
                return new DepartureDate(date, full.group(), true);
            } catch (RuntimeException ignored) {
                // 非法日期（如 2026-13-40）继续尝试其它规则
            }
        }

        // 2) 相对日期
        int offset = relativeDayOffset(text);
        if (offset != NO_RELATIVE_DATE) {
            String phrase = offset == 0 ? "今天" : offset == 1 ? "明天" : "后天";
            return new DepartureDate(base.plusDays(offset), phrase, true);
        }

        // 3) 周末 / 下周X / 周X
        if (text.contains("下周") && text.contains("末")) {
            LocalDate monday = base.with(TemporalAdjusters.next(DayOfWeek.MONDAY));
            return new DepartureDate(monday.plusDays(5), "下周末", true);
        }
        if (text.contains("周末")) {
            return new DepartureDate(comingWeekday(base, DayOfWeek.SATURDAY), "周末", true);
        }
        Matcher weekday = WEEKDAY.matcher(text);
        if (weekday.find()) {
            String prefix = weekday.group(1);
            DayOfWeek target = weekdayOf(weekday.group(2));
            if (target != null) {
                LocalDate date;
                if (prefix.startsWith("下")) {
                    LocalDate nextMonday = base.with(TemporalAdjusters.next(DayOfWeek.MONDAY));
                    date = nextMonday.plusDays(target.getValue() - 1L);
                } else if (prefix.startsWith("本") || prefix.startsWith("这")) {
                    LocalDate monday = base.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
                    date = monday.plusDays(target.getValue() - 1L);
                    if (date.isBefore(base)) {
                        date = date.plusWeeks(1); // 本周已过的那天 → 顺延到下一周
                    }
                } else {
                    date = comingWeekday(base, target);
                }
                return new DepartureDate(date, weekday.group(), true);
            }
        }

        // 4) 固定节日（农历节日无法只靠本类推算，暂不支持）
        if (text.contains("国庆")) {
            return new DepartureDate(rollForward(base, 10, 1), "国庆", true);
        }
        if (text.contains("元旦")) {
            return new DepartureDate(rollForward(base, 1, 1), "元旦", true);
        }
        if (text.contains("劳动节") || text.contains("五一")) {
            return new DepartureDate(rollForward(base, 5, 1), "劳动节", true);
        }

        // 5) 月日（今年已过 → 顺延到明年）
        Matcher md = MONTH_DAY.matcher(text);
        if (md.find()) {
            try {
                int month = Integer.parseInt(md.group(1));
                int day = Integer.parseInt(md.group(2));
                if (month >= 1 && month <= 12 && day >= 1 && day <= 31) {
                    return new DepartureDate(rollForward(base, month, day), md.group(), true);
                }
            } catch (RuntimeException ignored) {
                // 落到"未识别"
            }
        }
        return DepartureDate.none();
    }

    private static DayOfWeek weekdayOf(String chinese) {
        if ("日".equals(chinese) || "天".equals(chinese)) {
            return DayOfWeek.SUNDAY;
        }
        Integer n = CN_NUM.get(chinese.charAt(0));
        return n == null || n < 1 || n > 6 ? null : DayOfWeek.of(n);
    }

    /** 下一个指定星期几（今天是该天则返回今天） */
    private static LocalDate comingWeekday(LocalDate base, DayOfWeek target) {
        int diff = (target.getValue() - base.getDayOfWeek().getValue() + 7) % 7;
        return base.plusDays(diff);
    }

    /** 月日：今年已过则顺延到明年 */
    private static LocalDate rollForward(LocalDate base, int month, int day) {
        LocalDate date = LocalDate.of(base.getYear(), month, day);
        return date.isBefore(base) ? date.plusYears(1) : date;
    }

    /** 日期到行程天的映射结果：outsideTrip=true 表示用户提到的日期并不在行程区间内 */
    public record TripDay(int dayIndex, boolean outsideTrip, LocalDate date) {}

    /**
     * 把「今天/明天/后天」映射到行程内的某一天。
     *
     * 旧实现直接用绝对日期做 dayIndex 再 clamp，导致行程日期与今天不重合时会「说改明天、实际改最后一天」。
     * 现在的规则：
     * 1) 提到的日期落在行程区间内 → 用该天；
     * 2) 落在区间外（行程未开始/已结束）→ 按「当前天 + 偏移」在行程内顺推，并标记 outsideTrip 供上层提示。
     */
    public static TripDay resolveTripDay(Trip trip, Integer currentDay, String text) {
        int offset = relativeDayOffset(text);
        int base = currentDay == null || currentDay < 1 ? 1 : currentDay;
        if (trip == null || trip.getStartDate() == null) {
            LocalDate day = offset == NO_RELATIVE_DATE ? LocalDate.now() : LocalDate.now().plusDays(offset);
            return new TripDay(1, false, day);
        }
        int totalDays = tripDays(trip);
        if (offset == NO_RELATIVE_DATE) {
            // 也支持用户直接说日期（如「10月3日下雨」）：落在行程内就用那天
            DepartureDate explicit = extractDepartureDate(text, LocalDate.now());
            if (explicit.explicit() && explicit.date() != null) {
                LocalDate mentioned = explicit.date();
                boolean inRange = !mentioned.isBefore(trip.getStartDate())
                        && (trip.getEndDate() == null || !mentioned.isAfter(trip.getEndDate()));
                if (inRange) {
                    int idx = dayIndexOf(trip, mentioned);
                    return new TripDay(idx, false, tripDayDate(trip, idx));
                }
                int idx = clampDay(base, totalDays);
                return new TripDay(idx, true, tripDayDate(trip, idx));
            }
            int idx = clampDay(base, totalDays);
            return new TripDay(idx, false, tripDayDate(trip, idx));
        }
        LocalDate mentioned = LocalDate.now().plusDays(offset);
        boolean inRange = !mentioned.isBefore(trip.getStartDate())
                && (trip.getEndDate() == null || !mentioned.isAfter(trip.getEndDate()));
        if (inRange) {
            int idx = dayIndexOf(trip, mentioned);
            return new TripDay(idx, false, tripDayDate(trip, idx));
        }
        int idx = clampDay(base + offset, totalDays);
        return new TripDay(idx, true, tripDayDate(trip, idx));
    }

    public static int tripDays(Trip trip) {
        if (trip == null || trip.getStartDate() == null) {
            return 1;
        }
        if (trip.getEndDate() == null) {
            return 3;
        }
        return (int) Math.max(ChronoUnit.DAYS.between(trip.getStartDate(), trip.getEndDate()) + 1, 1);
    }

    public static LocalDate tripDayDate(Trip trip, int dayIndex) {
        LocalDate start = trip != null && trip.getStartDate() != null ? trip.getStartDate() : LocalDate.now();
        return start.plusDays(Math.max(dayIndex, 1) - 1L);
    }

    private static int clampDay(int day, int totalDays) {
        return Math.min(Math.max(day, 1), Math.max(totalDays, 1));
    }

    /** 日期 → 行程中的 dayIndex（1-based） */
    public static int dayIndexOf(Trip trip, LocalDate date) {
        if (trip == null || trip.getStartDate() == null) {
            return 1;
        }
        int index = (int) (ChronoUnit.DAYS.between(trip.getStartDate(), date) + 1);
        long days = trip.getEndDate() == null ? 3
                : ChronoUnit.DAYS.between(trip.getStartDate(), trip.getEndDate()) + 1;
        return Math.min(Math.max(index, 1), (int) Math.max(days, 1));
    }
}
