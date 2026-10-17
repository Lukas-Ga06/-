package com.travel.agent.agent;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.travel.agent.entity.Trip;
import com.travel.agent.llm.LlmClient;
import com.travel.agent.llm.LlmMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/** Intent Agent：理解「用户到底想干什么」。LLM 优先，规则兜底。 */
@Component
@RequiredArgsConstructor
@Slf4j
public class IntentAgent {

    /** 明确的时间点，例如「下午3点」「9:30」——用于区分"排日程"与随口提到的会议 */
    private static final Pattern EXPLICIT_TIME =
            Pattern.compile("(上午|下午|中午|晚上|早上)?\\s*\\d{1,2}\\s*[点时:：]");

    private static final List<String> INTENTS = List.of(
            IntentResult.PLAN_TRIP, IntentResult.MODIFY_ITINERARY, IntentResult.QUERY_BUDGET,
            IntentResult.QUERY_KNOWLEDGE, IntentResult.RECORD_EXPENSE,
            IntentResult.ADD_MEMO, IntentResult.QUERY_MEMO,
            IntentResult.ADD_SCHEDULE, IntentResult.QUERY_SCHEDULE,
            IntentResult.GEN_DAILY_REPORT, IntentResult.QUERY_HEALTH, IntentResult.CHITCHAT);

    private final LlmClient llmClient;
    private final ObjectMapper objectMapper;

    public IntentResult detect(AgentContext ctx, Trip trip) {
        String message = ctx.getMessage() == null ? "" : ctx.getMessage();
        if (llmClient.available()) {
            IntentResult result = llmDetect(ctx, trip, message);
            if (result != null && plausible(result, message)) {
                return result;
            }
        }
        return ruleDetect(ctx, trip, message);
    }

    /**
     * 模型越界分类的兜底：例如把「总结我在上海的旅行」判成「生成今日日报」。
     * 日报只应在用户明确提到日报/今天/今日时触发，否则交回规则引擎重新判断
     * （这类问题更适合走预算/知识问答，并带上行程上下文）。
     */
    private boolean plausible(IntentResult result, String message) {
        if (IntentResult.GEN_DAILY_REPORT.equals(result.getIntent())
                && !MessageParser.mentions(message, "日报", "今天", "今日")) {
            log.info("模型把「{}」判成日报，交回规则引擎", message);
            return false;
        }
        return true;
    }

    private IntentResult llmDetect(AgentContext ctx, Trip trip, String message) {
        try {
            String prompt = """
                    你是个人智能助理的意图识别模块。将用户消息分类为以下意图之一：
                    PLAN_TRIP（规划新行程；用户说「重新规划/重排整个行程」也归这里）、
                    MODIFY_ITINERARY（只改某一项或某一天，如换掉某个景点、调整时间）、QUERY_BUDGET（查询预算花费）、
                    QUERY_KNOWLEDGE（旅游知识问答，如攻略/推荐/介绍）、RECORD_EXPENSE（记账：记录一笔实际花费，如「记一笔午餐45元」）、
                    ADD_MEMO（添加个人备忘/待办，如「记一下明天办签证」）、QUERY_MEMO（查看备忘/待办列表）、
                    ADD_SCHEDULE（添加日程，如「明天下午3点开评审会」）、QUERY_SCHEDULE（查看日程安排）、
                    GEN_DAILY_REPORT（生成"今天"的个人日报；只有用户明确说要日报或回顾今天时才用，
                    说「总结我在上海的旅行」「回顾某次旅行」属于 QUERY_KNOWLEDGE 或 QUERY_BUDGET，不要判成日报）、
                    QUERY_HEALTH（查询健康数据，如步数/睡眠/心率/手环）、
                    CHITCHAT（其他闲聊）。
                    target 为操作目标：OUTDOOR_ACTIVITY / SELECTED_ITEM / NONE。
                    destination 为用户想去的城市名（仅中文城市名本身，如 "石家庄"、"洛阳"），无法确定时填 null。
                    仅输出 JSON，格式：{"intent":"...","target":"...","destination":"...","constraints":{"date":"明天","weather":"rain"}}
                    当前用户是否已有行程：%s
                    用户消息：%s
                    """.formatted(trip != null ? "有 (tripId=" + trip.getId() + ")" : "无", message);
            String raw = llmClient.chat(List.of(
                    LlmMessage.system("你只输出 JSON，不要输出任何其他内容。"),
                    LlmMessage.user(prompt)), 0.0);
            if (raw == null) {
                return null;
            }
            String json = raw.replace("```json", "").replace("```", "").trim();
            JsonNode node = objectMapper.readTree(json);
            String intent = node.path("intent").asText("");
            if (!INTENTS.contains(intent)) {
                return null;
            }
            Map<String, Object> constraints;
            if (node.hasNonNull("constraints") && node.path("constraints").isObject()) {
                constraints = objectMapper.convertValue(node.path("constraints"), new TypeReference<>() {});
            } else {
                constraints = new HashMap<>();
            }
            String destination = node.path("destination").asText("").trim();
            if (!destination.isBlank() && !"null".equalsIgnoreCase(destination)) {
                constraints.put("destination", destination);
            }
            return IntentResult.builder()
                    .intent(intent)
                    .target(node.path("target").asText(IntentResult.TARGET_NONE))
                    .constraints(constraints)
                    .build();
        } catch (Exception e) {
            log.warn("LLM 意图识别失败，使用规则兜底: {}", e.getMessage());
            return null;
        }
    }

    private IntentResult ruleDetect(AgentContext ctx, Trip trip, String message) {
        Map<String, Object> constraints = new HashMap<>();
        if (MessageParser.mentions(message, "雨")) {
            constraints.put("weather", "rain");
        }
        String date = MessageParser.mentions(message, "后天") ? "后天"
                : MessageParser.mentions(message, "明天") ? "明天"
                : MessageParser.mentions(message, "今天", "今晚") ? "今天" : null;
        if (date != null) {
            constraints.put("date", date);
        }
        if (MessageParser.mentions(message, "亲子", "孩子", "带娃")) {
            constraints.put("preference", "family");
        }
        if (MessageParser.mentions(message, "地铁")) {
            constraints.put("preference", "near_subway");
        }

        // 1. 修改类关键词最优先
        boolean modify = MessageParser.mentions(message,
                "换掉", "换成", "替换", "换一个", "换个", "改一下", "调整一下", "取消", "删掉", "去掉", "改掉", "移除");
        if (modify) {
            String target = IntentResult.TARGET_OUTDOOR;
            if (ctx.getSelectedElement() != null && MessageParser.mentions(message, "这个", "它", "该", "此", "这里", "那个")) {
                target = IntentResult.TARGET_SELECTED;
            }
            if (MessageParser.mentions(message, "户外")) {
                target = IntentResult.TARGET_OUTDOOR;
            }
            return IntentResult.builder()
                    .intent(IntentResult.MODIFY_ITINERARY).target(target).constraints(constraints).build();
        }

        // 2. 记账：消息中能解析出有效金额且带有记账/花费表述
        if (MessageParser.parseExpenseRequest(message) != null
                && MessageParser.mentions(message, "记", "花", "支出", "消费")) {
            return IntentResult.builder()
                    .intent(IntentResult.RECORD_EXPENSE).target(IntentResult.TARGET_NONE).constraints(constraints).build();
        }

        // 4. 备忘：查看优先于添加
        boolean memoQuery = MessageParser.mentions(message, "我的备忘", "备忘录", "我的待办", "待办事项", "备忘列表", "待办列表");
        boolean memoAdd = !memoQuery && MessageParser.mentions(message, "记一下", "记个", "备忘", "提醒我", "别忘了", "记得帮我");
        if (memoAdd) {
            return IntentResult.builder()
                    .intent(IntentResult.ADD_MEMO).target(IntentResult.TARGET_NONE).constraints(constraints).build();
        }
        if (memoQuery) {
            return IntentResult.builder()
                    .intent(IntentResult.QUERY_MEMO).target(IntentResult.TARGET_NONE).constraints(constraints).build();
        }

        // 5. 办公：日报 / 日程 / 健康
        if (MessageParser.mentions(message, "日报")) {
            return IntentResult.builder()
                    .intent(IntentResult.GEN_DAILY_REPORT).target(IntentResult.TARGET_NONE).constraints(constraints).build();
        }
        if (MessageParser.mentions(message, "健康", "步数", "睡眠", "心率", "手环", "体重")) {
            return IntentResult.builder()
                    .intent(IntentResult.QUERY_HEALTH).target(IntentResult.TARGET_NONE).constraints(constraints).build();
        }
        boolean scheduleQuery = MessageParser.mentions(message, "我的日程", "日程表", "日程安排", "有哪些安排", "日程有哪些");
        // 必须有明确时间点或排期动词才算"添加日程"。
        // 旧实现只要求关键词 + parseScheduleRequest 非 null，而后者对任何文本都返回非 null，
        // 于是"今天开会好累"这类陈述句会被真的写成一条日程。
        boolean hasExplicitTime = EXPLICIT_TIME.matcher(message).find();
        boolean hasSchedulingVerb = MessageParser.mentions(message,
                "排个", "排进", "安排个", "约个", "加入日程", "加到日程", "预约");
        boolean scheduleNoun = MessageParser.mentions(message,
                "日程", "排期", "会议", "开会", "评审会", "例会", "晨会", "周会", "预约", "排个", "排进");
        boolean scheduleAdd = !scheduleQuery && scheduleNoun && (hasExplicitTime || hasSchedulingVerb);
        if (scheduleAdd) {
            return IntentResult.builder()
                    .intent(IntentResult.ADD_SCHEDULE).target(IntentResult.TARGET_NONE).constraints(constraints).build();
        }
        if (scheduleQuery) {
            return IntentResult.builder()
                    .intent(IntentResult.QUERY_SCHEDULE).target(IntentResult.TARGET_NONE).constraints(constraints).build();
        }

        // 6. 强规划意图：明确的规划动词，或「天数 + 出行词」组合
        boolean strongPlan = MessageParser.mentions(message, "规划", "安排", "计划", "想去", "帮我做一个")
                || (MessageParser.extractDays(message) > 0
                        && MessageParser.mentions(message, "旅行", "旅游", "游", "玩", "行程", "出发"));
        if (strongPlan) {
            return IntentResult.builder()
                    .intent(IntentResult.PLAN_TRIP).target(IntentResult.TARGET_NONE).constraints(constraints).build();
        }

        // 7. 疑问句 → 预算 / 知识问答
        boolean money = MessageParser.mentions(message, "预算", "花费", "花多少", "多少钱", "贵不贵", "超支");
        boolean question = MessageParser.mentions(message,
                "什么", "哪些", "为什么", "怎么", "攻略", "介绍", "注意", "值得", "推荐", "适合", "吗", "？", "?");
        if (question && money) {
            return IntentResult.builder()
                    .intent(IntentResult.QUERY_BUDGET).target(IntentResult.TARGET_NONE).constraints(constraints).build();
        }
        if (question) {
            return IntentResult.builder()
                    .intent(IntentResult.QUERY_KNOWLEDGE).target(IntentResult.TARGET_NONE).constraints(constraints).build();
        }
        if (money) {
            return IntentResult.builder()
                    .intent(IntentResult.QUERY_BUDGET).target(IntentResult.TARGET_NONE).constraints(constraints).build();
        }
        if (MessageParser.mentions(message, "交通", "怎么去", "门票", "预约", "几点", "历史")) {
            return IntentResult.builder()
                    .intent(IntentResult.QUERY_KNOWLEDGE).target(IntentResult.TARGET_NONE).constraints(constraints).build();
        }

        // 兜底：没有识别出明确意图时不要贸然开始规划（会走到"暂不支持该目的地"），先给出能力提示
        return IntentResult.builder()
                .intent(IntentResult.CHITCHAT)
                .target(IntentResult.TARGET_NONE).constraints(constraints).build();
    }
}
