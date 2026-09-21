package com.travel.agent.agent;

import com.travel.agent.entity.Trip;

import java.math.BigDecimal;
import java.time.LocalDate;
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
        Matcher m = Pattern.compile("(\\d+)\\s*[天日]").matcher(text);
        if (m.find()) {
            return Integer.parseInt(m.group(1));
        }
        m = Pattern.compile("([一两二三四五六七八九十])\\s*[天日]").matcher(text);
        if (m.find()) {
            return CN_NUM.getOrDefault(m.group(1).charAt(0), -1);
        }
        if (text.contains("周末")) {
            return 2;
        }
        return -1;
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
