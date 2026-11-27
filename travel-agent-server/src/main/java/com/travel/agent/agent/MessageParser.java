package com.travel.agent.agent;

import com.travel.agent.entity.Trip;
import com.travel.agent.service.CityAtlas;
import com.travel.agent.util.CityNames;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalDate;
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
        Matcher m = Pattern.compile("(\\d{1,3})\\s*[天日]").matcher(stripped);
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
        // 空白与可选冒号都用有界量词（CodeQL polynomial-redos）：无界 \s* 叠加可选组，
        // 在 2 万字符的恶意输入上会退化成秒级回溯。
        Matcher m = Pattern.compile("(?:预算|budget)\\s{0,4}[:：]?\\s{0,4}([0-9,]{3,9})").matcher(text);
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

    /**
     * 从消息中粗略猜测目的地名（目录外城市联网查询、需求槽位都依赖它）。
     *
     * <p>2026-11-24 修正（灰度期真实用户撞到的脏目的地）：
     * <ul>
     *   <li>先剥「从 A 出发去/到 B」「A 出发的 B」的出发地从句，避免把出发地当目的地
     *       （「北京出发去西安」曾抽成北京、「南京出发去扬州镇江」曾抽成「州镇江的」）；</li>
     *   <li>优先取「去/到/前往…」后面的城市名，再退回「X 日游/周末游」前的城市；</li>
     *   <li>先认 {@link CityAtlas#knownNames()} 里的已知城市，再退回 2-4 字启发式，
     *       并剥掉「的/吃/玩/发去」这类残留（「成都吃」→成都、「张家界的」→张家界）；</li>
     *   <li>两个真城市粘在一起（「扬州镇江」「大理丽江」）取第一个：单次只能规划一个城市，
     *       宁可落到一个真实城市，也不产出「州镇江的」这种查无此城的串；</li>
     *   <li>猜不出来返回 null——槽位缺目的地时交给 Agent 追问，绝不在这里编一个城市。</li>
     * </ul>
     */
    public static String guessCityName(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        String destinationText = CityNames.stripOriginClause(text);

        Matcher explicit = EXPLICIT_DESTINATION.matcher(destinationText);
        if (explicit.find()) {
            String city = cleanCityCandidate(explicit.group(1));
            if (city != null) {
                return city;
            }
        }

        Matcher goTo = GO_TO_CITY.matcher(destinationText);
        while (goTo.find()) {
            String city = cleanCityCandidate(goTo.group(1));
            if (city != null) {
                return city;
            }
        }

        Matcher tripWord = TRIP_WORD.matcher(destinationText);
        while (tripWord.find()) {
            String city = cityBefore(destinationText, tripWord.start());
            if (city != null) {
                return city;
            }
        }

        for (String pattern : FALLBACK_CITY_PATTERNS) {
            Matcher m = Pattern.compile(pattern).matcher(destinationText);
            while (m.find()) {
                String city = cleanCityCandidate(m.group(1));
                if (city != null) {
                    return city;
                }
            }
        }
        return null;
    }

    /** 取 end 位置之前最近的一段文字，按城市名规则清洗；这是「X 日游」句式的落点 */
    private static String cityBefore(String text, int end) {
        int from = Math.max(0, end - 14);
        return cleanCityCandidate(text.substring(from, end));
    }

    /**
     * 把一段「像城市名但可能带前后缀」的文本清洗成城市名。
     * 清洗顺序：取开头连续汉字 → 剥动词前缀 → 已知城市前缀 → 剥天数与尾部助词/动词
     * → 剥地理后缀（群岛/古城…）→ 2-4 字启发式兜底。
     */
    private static String cleanCityCandidate(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        Matcher run = LEADING_HAN.matcher(raw.trim());
        if (!run.find()) {
            return null;
        }
        String candidate = stripLeading(run.group(1));
        // 先认已知城市：「成都吃四天」「张家界的」「扬州镇江」都会在这里得到真实城市
        String known = longestKnownPrefix(candidate);
        if (known != null) {
            return known;
        }
        candidate = DAY_IN_TEXT.matcher(candidate).replaceAll("");
        candidate = stripTrailingModifiers(candidate);
        candidate = stripLeading(candidate);
        if (candidate.isBlank() || isAllChineseNumeral(candidate)) {
            return null;
        }
        String geo = stripGeoSuffix(candidate);
        if (geo != null && isCityLike(geo)) {
            return geo;
        }
        if (candidate.length() >= 2 && candidate.length() <= 4 && isCityLike(candidate)) {
            return candidate;
        }
        return null;
    }

    /** 已知城市里最长的前缀；没有返回 null */
    private static String longestKnownPrefix(String text) {
        String best = null;
        for (String name : CityAtlas.knownNames()) {
            if (text.startsWith(name) && (best == null || name.length() > best.length())) {
                best = name;
            }
        }
        return best;
    }

    /** 剥尾部助词/动词/场景词（可叠加：「成都吃」→成都、「雪乡的」→雪乡） */
    private static String stripTrailingModifiers(String text) {
        String city = text;
        boolean changed = true;
        while (changed) {
            changed = false;
            for (String suffix : TRAILING_MODIFIERS) {
                if (city.length() > suffix.length() && city.endsWith(suffix)) {
                    city = city.substring(0, city.length() - suffix.length());
                    changed = true;
                    break;
                }
            }
        }
        return city;
    }

    /** 剥地理后缀（「舟山群岛」→舟山）；没有命中返回 null */
    private static String stripGeoSuffix(String text) {
        for (String suffix : GEO_SUFFIXES) {
            if (text.length() > suffix.length() + 1 && text.endsWith(suffix)) {
                return text.substring(0, text.length() - suffix.length());
            }
        }
        return null;
    }

    private static boolean isAllChineseNumeral(String text) {
        if (text.isEmpty()) {
            return false;
        }
        for (int i = 0; i < text.length(); i++) {
            if (CN_NUMERALS.indexOf(text.charAt(i)) < 0) {
                return false;
            }
        }
        return true;
    }

    /** 剥掉「帮我/规划/去」这类紧贴城市名的动词前缀（可能叠了好几层） */
    private static String stripLeading(String candidate) {
        String city = candidate;
        boolean changed = true;
        while (changed) {
            changed = false;
            for (String prefix : CITY_VERB_PREFIXES) {
                if (city.length() >= prefix.length() + 2 && city.startsWith(prefix)) {
                    city = city.substring(prefix.length());
                    changed = true;
                    break;
                }
            }
        }
        return city;
    }

    /** 城市名前后常粘连的动词/限定词（按长度从长到短剥） */
    private static final java.util.List<String> CITY_VERB_PREFIXES = java.util.List.of(
            "请帮我", "帮我", "我想", "我们", "我的", "规划", "安排", "计划", "这次", "下次",
            "一趟", "前往", "出发", "从", "自", "由", "的", "了", "着", "过",
            "去", "到", "在", "飞", "游", "请", "想", "来", "发");

    /** 尾部常见的场景/修饰词（不是城市名的一部分） */
    private static final java.util.List<String> TRAILING_MODIFIERS = java.util.List.of(
            "周末", "亲子", "假期", "假日", "旅行", "旅游", "出游", "出行", "出差", "海边", "海岛",
            "的", "地", "得", "了", "着", "过", "玩", "吃", "逛", "看", "住", "待", "游");

    private static final java.util.List<String> GEO_SUFFIXES = java.util.List.of(
            "风景区", "国家公园", "度假区", "景区", "群岛", "半岛", "古城", "古镇");

    private static final Pattern EXPLICIT_DESTINATION = Pattern.compile(
            "目的地\\s*[:：]\\s*([\\u4e00-\\u9fa5]{2,12})");
    private static final Pattern GO_TO_CITY = Pattern.compile(
            "(?:去|到|前往|飞往|抵达|赶赴)\\s*([\\u4e00-\\u9fa5]{2,12})");
    private static final Pattern TRIP_WORD = Pattern.compile(
            "([0-9一二两三四五六七八九十]{1,4}日游|周末游|亲子游|游)");
    private static final Pattern LEADING_HAN = Pattern.compile("^([\\u4e00-\\u9fa5]{2,12})");
    private static final Pattern DAY_IN_TEXT = Pattern.compile(
            "[0-9一二两三四五六七八九十]{1,4}(?:天|日)(?:一夜|晚)?");
    private static final String CN_NUMERALS = "零一二两三四五六七八九十百千万";
    private static final String[] FALLBACK_CITY_PATTERNS = {
            "(?:到|在|飞)\\s*([\\u4e00-\\u9fa5]{2,4}?)(?:玩|旅行|旅游|待|过|出差)",
            "([\\u4e00-\\u9fa5]{2,4}?)(?:的)?(?:行程|旅行|旅游|出差)"
    };

    /** 「像城市名」：2-4 个汉字且不是被句式误伤的动词/限定词 */
    private static boolean isCityLike(String candidate) {
        if (candidate == null) {
            return false;
        }
        String city = CityNames.normalize(candidate);
        if (city.length() < 2 || city.length() > 4 || !CityNames.isPlausibleName(city)) {
            return false;
        }
        for (String stop : CITY_STOPWORDS) {
            if (city.contains(stop)) {
                return false;
            }
        }
        return true;
    }

    /**
     * 追问后的极短回答：「上海」「北京」整句就是一个城市名。
     *
     * <p>只接受 2-4 个纯汉字、且不像天数/人数/预算的整句——「三天」「两个人」「预算」都不是城市。
     * 追问场景之外的消息本来就有完整句式，用 {@link #guessCityName} 即可。
     */
    public static String guessBareCityName(String text) {
        if (text == null) {
            return null;
        }
        String t = text.trim().replaceAll("[，。！？、,.!?\\s]", "");
        if (t.length() < 2 || t.length() > 4) {
            return null;
        }
        if (extractDays(t) > 0 || extractPartySize(t) > 0 || extractBudget(t) != null) {
            return null;
        }
        if (t.chars().anyMatch(Character::isDigit)) {
            return null;
        }
        return isCityLike(t) ? t : null;
    }

    private static final java.util.List<String> CITY_STOPWORDS = java.util.List.of(
            "城市", "哪里", "那里", "这里", "地方", "一个", "帮我", "规划", "安排", "计划",
            "我们", "我的", "这次", "下次", "一趟", "整个", "行程", "旅行", "旅游", "出行",
            "出游", "目的地", "周末", "今天", "明天", "后天", "下周", "本周", "附近", "周边",
            "国内", "国外", "几天", "玩耍", "小孩子", "一天", "两天", "三天", "四天", "五天",
            "带小孩", "孩子", "一家", "老人", "父母", "学生", "海边", "海岛", "群岛", "半岛",
            "古城", "古镇", "景区", "度假",
            "预算", "花费", "消费", "天气", "地铁", "酒店", "民宿", "亲子", "美食", "景点", "门票");

    /**
     * 提取同行人数："3人" / "三个人" / "2大1小" / "一家三口" / "我们俩" / "一个人"。
     * 结构化表单的「成人 N，儿童 M」按人头相加（票价/床位的计价口径一致）。
     * 解析不出来返回 -1；结果收敛到 1..{@link RequirementSlots#MAX_PARTY_SIZE}。
     */
    public static int extractPartySize(String text) {
        if (text == null || text.isBlank()) {
            return -1;
        }
        // 1) 结构化表单/明确分人：成人 + 儿童
        Matcher adult = Pattern.compile("(?:成人|大人)\\s{0,4}[:：]?\\s{0,4}(\\d{1,2})").matcher(text);
        Matcher kid = Pattern.compile("(?:儿童|孩子|小孩)\\s{0,4}[:：]?\\s{0,4}(\\d{1,2})").matcher(text);
        boolean hasAdult = adult.find();
        boolean hasKid = kid.find();
        if (hasAdult || hasKid) {
            int count = (hasAdult ? Integer.parseInt(adult.group(1)) : 0)
                    + (hasKid ? Integer.parseInt(kid.group(1)) : 0);
            if (count > 0) {
                return clampParty(count);
            }
        }
        // 2) 「2大1小」
        Matcher bigSmall = Pattern.compile("(\\d{1,2})\\s*大\\s*(\\d{1,2})\\s*小").matcher(text);
        if (bigSmall.find()) {
            return clampParty(Integer.parseInt(bigSmall.group(1)) + Integer.parseInt(bigSmall.group(2)));
        }
        // 3) 「一家三口」
        Matcher family = Pattern.compile("一家([三四五六七八九十])口").matcher(text);
        if (family.find()) {
            Integer count = CN_NUM.get(family.group(1).charAt(0));
            if (count != null) {
                return clampParty(count);
            }
        }
        // 4) 「3人 / 3个人 / 3位」
        Matcher arabic = Pattern.compile("(\\d{1,2})\\s{0,4}(?:个|位)?\\s{0,4}人").matcher(text);
        if (arabic.find()) {
            return clampParty(Integer.parseInt(arabic.group(1)));
        }
        // 5) 「三个人 / 两个人」
        Matcher chinese = Pattern.compile("([一两二三四五六七八九十])\\s{0,4}(?:个|位)?\\s{0,4}人").matcher(text);
        if (chinese.find()) {
            Integer count = CN_NUM.get(chinese.group(1).charAt(0));
            if (count != null) {
                return clampParty(count);
            }
        }
        // 6) 单人表述
        if (text.contains("独自") || text.contains("一个人") || text.contains("单人")) {
            return 1;
        }
        return -1;
    }

    private static int clampParty(int count) {
        return Math.min(Math.max(count, 1), RequirementSlots.MAX_PARTY_SIZE);
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
        Matcher m = Pattern.compile("([0-9]{1,9}(?:\\.[0-9]{1,2})?)\\s{0,4}(?:元|块钱|块)").matcher(text);
        if (m.find()) {
            amount = new java.math.BigDecimal(m.group(1));
        }
        if (amount == null) {
            m = Pattern.compile("(?:花了|花掉|记一笔|记账|记一下|支出)\\s{0,4}[:：]?\\s{0,4}([0-9]{1,9}(?:\\.[0-9]{1,2})?)").matcher(text);
            if (m.find()) {
                amount = new java.math.BigDecimal(m.group(1));
            }
        }
        if (amount == null || amount.signum() <= 0) {
            return null;
        }
        // 分类词表与发票报销共用一处（util.ExpenseCategories），避免两条入口各维护一份
        String category = com.travel.agent.util.ExpenseCategories.of(text);
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
    public record ScheduleRequest(LocalDate date, java.time.LocalTime time, String title, String recurrence) {}

    /**
     * 解析日程请求：日期、时间、事项标题与重复规则。
     * 例：「明天下午3点开评审会」→ (明天, 15:00, 开评审会, NONE)
     *     「每周一上午10点例会」→ (下个周一, 10:00, 例会, WEEKLY)
     */
    public static ScheduleRequest parseScheduleRequest(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        LocalDate today = LocalDate.now();
        String recurrence = mentions(text, "每天", "每日") ? "DAILY"
                : mentions(text, "每周", "每星期") ? "WEEKLY"
                : mentions(text, "每月") ? "MONTHLY" : "NONE";

        LocalDate date;
        if (mentions(text, "后天")) {
            date = today.plusDays(2);
        } else if (mentions(text, "明天")) {
            date = today.plusDays(1);
        } else if (mentions(text, "今天", "今晚")) {
            date = today;
        } else {
            // 支持「周一 / 下周六 / 10月1日 / 周末」等写法（与行程规划共用同一套日期解析）
            DepartureDate explicit = extractDepartureDate(text, today);
            date = explicit.explicit() && explicit.date() != null ? explicit.date() : today;
        }
        java.time.LocalTime time = null;
        Matcher m = Pattern.compile("(上午|下午|中午|晚上|早上)?\\s{0,4}(\\d{1,2})\\s{0,4}[点时：:]\\s{0,4}(\\d{1,2})?\\s{0,4}分?").matcher(text);
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
        // 先替换更长的重复词（每周一），再替换「周一」，避免留下「一例会」这种残句
        text = text.replaceAll("(每天|每日|每周[一二三四五六日天]|每星期[一二三四五六日天]|每周|每星期|每月"
                + "|下周[一二三四五六日天]?|本周[一二三四五六日天]?|星期[一二三四五六日天]|周[一二三四五六日天]"
                + "|今天|明天|后天)", " ").trim();
        text = text.replaceAll("\\d{4}\\s*[-/年]\\s*\\d{1,2}\\s*[-/月]\\s*\\d{1,2}\\s*日?", " ").trim();
        text = text.replaceAll("\\d{1,2}\\s*月\\s*\\d{1,2}\\s*日?", " ").trim();
        text = text.replaceAll("^(帮我|请|把|添加|加|新增|记录?)\\s*[:：,，、]?\\s*", "").trim();
        text = text.replaceFirst("^(提醒我|提醒|别忘了|记得)\\s*[:：,，、]?\\s*", "").trim();
        text = text.replaceAll("^(加入|加到)\\s*日程\\s*[:：,，、]?\\s*", "").trim();
        if (text.startsWith("日程")) {
            text = text.substring(2).trim();
        }
        if (text.isBlank()) {
            text = "日程事项";
        }
        return new ScheduleRequest(date, time, text, recurrence);
    }

    /** 会议时长：默认 1 小时，支持「半小时/30分钟」与「X小时/一小时/两小时」 */
    public static final int DEFAULT_MEETING_MINUTES = 60;
    public static final int MIN_MEETING_MINUTES = 30;
    public static final int MAX_MEETING_MINUTES = 480;

    private static final Pattern HOUR_DURATION = Pattern.compile("(\\d{1,2})\\s{0,2}(?:个)?\\s*小时");
    private static final Pattern CN_HOUR_DURATION = Pattern.compile("([一两二三四五六七八九十])\\s{0,2}(?:个)?\\s*小时");
    private static final Pattern MINUTE_DURATION = Pattern.compile("(\\d{1,3})\\s{0,2}分钟");

    /** 从消息里解析会议时长（分钟）；解析不出按 1 小时，结果收敛到 30..480 */
    public static int parseMeetingMinutes(String text) {
        if (text == null || text.isBlank()) {
            return DEFAULT_MEETING_MINUTES;
        }
        int minutes = DEFAULT_MEETING_MINUTES;
        if (mentions(text, "一个半小时")) {
            minutes = 90;
        } else if (mentions(text, "半小时", "半个小时")) {
            minutes = MIN_MEETING_MINUTES;
        } else {
            Matcher hour = HOUR_DURATION.matcher(text);
            Matcher cnHour = CN_HOUR_DURATION.matcher(text);
            Matcher minute = MINUTE_DURATION.matcher(text);
            if (hour.find()) {
                minutes = Integer.parseInt(hour.group(1)) * 60;
            } else if (cnHour.find()) {
                Integer hours = CN_NUM.get(cnHour.group(1).charAt(0));
                if (hours != null) {
                    minutes = hours * 60;
                }
            } else if (minute.find()) {
                minutes = Integer.parseInt(minute.group(1));
            }
        }
        return Math.min(Math.max(minutes, MIN_MEETING_MINUTES), MAX_MEETING_MINUTES);
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

    /** 空闲查询的日期范围（含首尾） */
    public record DateRange(LocalDate from, LocalDate to) {
    }

    /** 日期范围的人话表述：同一天写单日，跨天写「A 至 B」 */
    public static String formatRange(DateRange range) {
        if (range == null || range.from() == null || range.to() == null) {
            return "";
        }
        return range.from().equals(range.to())
                ? range.from().toString()
                : range.from() + " 至 " + range.to();
    }

    /** 「下周」后面直接跟星期几（下周X）时按单日解析，不按整周解析 */
    private static final Pattern WEEKDAY_AFTER_WEEK =
            Pattern.compile("(下周|下个星期|本星期|这星期|本周|这周|这个星期)[一二三四五六日天]");

    /** 「下下周」：带星期几是单日，不带是整周（基准：下下周的周一） */
    private static final Pattern WEEK_AFTER_NEXT = Pattern.compile("下下周([一二三四五六日天])?");

    /** 下下周的周一 = 今天所在周的下一周的下一周 */
    private static LocalDate mondayAfterNext(LocalDate base) {
        return base.with(TemporalAdjusters.next(DayOfWeek.MONDAY)).plusWeeks(1);
    }

    /**
     * 解析消息里<b>明确说出</b>的日期范围；一条日期词都没有时返回 {@code null}（由调用方决定默认值）。
     *
     * <p>支持：今天 / 今晚 / 明天 / 后天 / 大后天；下周（下周一至周日）、下下周（下下周一至周日）；
     * 本周 / 这周（今天起至本周日）；下周X、下下周X、周X 等单日写法与 10月1日 / 2026-10-01
     * （复用 {@link #extractDepartureDate}）。
     *
     * <p>返回 null 与「默认明天」的区别是语义上的：空闲查询可以默认明天，
     * 而「清空日程」这类破坏性操作必须先问清范围，绝不能替用户猜。
     */
    public static DateRange parseExplicitRange(String text, LocalDate today) {
        LocalDate base = today == null ? LocalDate.now() : today;
        if (text == null || text.isBlank()) {
            return null;
        }
        if (text.contains("大后天")) {
            return new DateRange(base.plusDays(3), base.plusDays(3));
        }
        if (text.contains("明天")) {
            return new DateRange(base.plusDays(1), base.plusDays(1));
        }
        if (text.contains("后天")) {
            return new DateRange(base.plusDays(2), base.plusDays(2));
        }
        if (text.contains("今天") || text.contains("今晚")) {
            return new DateRange(base, base);
        }
        // 「下下周」必须先于「下周」判断：「下下周一」里含有子串「下周一」，先命中会早一周
        Matcher weekAfterNext = WEEK_AFTER_NEXT.matcher(text);
        if (weekAfterNext.find()) {
            LocalDate monday = mondayAfterNext(base);
            DayOfWeek target = weekAfterNext.group(1) == null ? null : weekdayOf(weekAfterNext.group(1));
            if (target == null) {
                return new DateRange(monday, monday.plusDays(6));
            }
            LocalDate day = monday.plusDays(target.getValue() - 1L);
            return new DateRange(day, day);
        }
        boolean explicitWeekday = WEEKDAY_AFTER_WEEK.matcher(text).find();
        if (text.contains("下周") && !explicitWeekday && !text.contains("下周末")) {
            LocalDate nextMonday = base.with(TemporalAdjusters.next(DayOfWeek.MONDAY));
            return new DateRange(nextMonday, nextMonday.plusDays(6));
        }
        if ((text.contains("本周") || text.contains("这周") || text.contains("这个星期"))
                && !explicitWeekday && !text.contains("本周末") && !text.contains("这周末")) {
            LocalDate monday = base.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
            LocalDate sunday = monday.plusDays(6);
            return new DateRange(base, sunday.isBefore(base) ? base : sunday);
        }
        DepartureDate explicit = extractDepartureDate(text, base);
        if (explicit.explicit() && explicit.date() != null) {
            return new DateRange(explicit.date(), explicit.date());
        }
        return null;
    }

    /**
     * 解析空闲查询的日期范围：显式日期优先，没说就是明天一天。
     * 范围最多 7 天，超出由 {@code FreeTimeService#clampRangeEnd} 截断。
     */
    public static DateRange parseFreeTimeRange(String text, LocalDate today) {
        LocalDate base = today == null ? LocalDate.now() : today;
        DateRange explicit = parseExplicitRange(text, base);
        if (explicit != null) {
            return explicit;
        }
        LocalDate tomorrow = base.plusDays(1);
        return new DateRange(tomorrow, tomorrow);
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
        // 先用 long 夹到 [1, totalDays] 再转 int：避免 base + offset 在 int 上溢出（CodeQL tainted-arithmetic）
        int idx = clampDay((int) Math.max(1L, Math.min((long) base + offset, Math.max(totalDays, 1))), totalDays);
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
