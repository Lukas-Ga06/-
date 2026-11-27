package com.travel.agent.agent;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.travel.agent.entity.Trip;
import com.travel.agent.llm.LlmClient;
import com.travel.agent.llm.LlmMessage;
import com.travel.agent.tool.AgentTool;
import com.travel.agent.tool.ToolRegistry;
import com.travel.agent.util.CityNames;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Intent Agent：理解「用户到底想干什么」。LLM 优先，规则兜底。
 *
 * <p>会话历史只在这里被消费（{@link #renderHistory}）：用于理解指代/省略
 * （「那把第二天的酒店也换掉」要知道上一轮聊的是哪趟行程），
 * 它<b>不进入规划产物</b>——PlannerAgent / StructuredPlanner 不读 history。
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class IntentAgent {

    /** 明确的时间点，例如「下午3点」「9:30」——用于区分"排日程"与随口提到的会议 */
    private static final Pattern EXPLICIT_TIME =
            Pattern.compile("(上午|下午|中午|晚上|早上)?\\s*\\d{1,2}\\s*[点时:：]");

    /** P0-B 多意图：最多保留 3 条「其余未执行请求」，每条最多 40 字 */
    static final int MAX_ALSO_REQUESTED = 3;
    static final int MAX_ALSO_REQUESTED_LENGTH = 40;

    private static final List<String> INTENTS = List.of(
            IntentResult.PLAN_TRIP, IntentResult.MODIFY_ITINERARY, IntentResult.QUERY_BUDGET,
            IntentResult.QUERY_KNOWLEDGE, IntentResult.RECORD_EXPENSE,
            IntentResult.ADD_MEMO, IntentResult.QUERY_MEMO,
            IntentResult.ADD_SCHEDULE, IntentResult.QUERY_SCHEDULE, IntentResult.QUERY_FREE_TIME,
            IntentResult.CLEAR_SCHEDULE, IntentResult.INVOKE_TOOL, IntentResult.CHITCHAT);

    private final LlmClient llmClient;
    private final ObjectMapper objectMapper;
    private final ToolRegistry toolRegistry;

    public IntentResult detect(AgentContext ctx, Trip trip) {
        String message = ctx.getMessage() == null ? "" : ctx.getMessage();
        AgentBudget budget = ctx.getBudget();
        if (llmClient.available() && budget.allowsLlmCall(AgentBudget.MIN_LLM_CALL_MS)) {
            IntentResult result = llmDetect(ctx, trip, message, budget);
            if (result != null && plausible(result, message)) {
                return result;
            }
        } else if (llmClient.available()) {
            // 只有「模型可用、但时间预算不够」才算预算降级；无模型是既有的规则兜底，
            // 两条路径的日志与追踪必须能区分开（P1 验收：不得把两者混为一谈）。
            ctx.recordBudgetDegradation(AgentBudget.STAGE_INTENT,
                    "预算不足，跳过 LLM 意图识别，改用规则引擎");
        }
        IntentResult rule = ruleDetect(ctx, trip, message);
        // 规则路径同样产出需求槽位（FR-01）：追问与规划都依赖这份确定性解析
        if (rule != null && rule.getSlots() == null) {
            rule.setSlots(RequirementSlots.fromMessage(message));
        }
        return rule;
    }

    /**
     * 模型越界分类的兜底：只认注册过的 mcp_* 工具；模型编造工具名时交回规则引擎。
     */
    private boolean plausible(IntentResult result, String message) {
        // INVOKE_TOOL 只认注册过的 mcp_* 工具：模型编造工具名时交回规则引擎
        if (IntentResult.INVOKE_TOOL.equals(result.getIntent())
                && (result.getToolName() == null || result.getToolName().isBlank()
                    || !result.getToolName().startsWith("mcp_")
                    || toolRegistry.get(result.getToolName()) == null)) {
            log.info("模型给出了不可用的 MCP 工具「{}」，交回规则引擎", result.getToolName());
            return false;
        }
        return true;
    }

    private IntentResult llmDetect(AgentContext ctx, Trip trip, String message, AgentBudget budget) {
        try {
            String historyBlock = renderHistory(ctx);
            String toolsBlock = renderToolCatalog();
            String prompt = """
                    你是智能旅行助手的意图识别模块。将用户消息分类为以下意图之一：
                    PLAN_TRIP（规划新行程；用户说「重新规划/重排整个行程」也归这里）、
                    MODIFY_ITINERARY（只改某一项或某一天，如换掉某个景点、调整时间）、QUERY_BUDGET（查询预算花费）、
                    QUERY_KNOWLEDGE（旅游知识问答，如攻略/推荐/介绍）、RECORD_EXPENSE（记账：记录一笔实际花费，如「记一笔午餐45元」）、
                    ADD_MEMO（添加个人备忘/待办，如「记一下明天办签证」）、QUERY_MEMO（查看备忘/待办列表）、
                    ADD_SCHEDULE（添加出行日程/提醒，如「明天下午3点去办签证」）、QUERY_SCHEDULE（查看日程安排）、
                    QUERY_FREE_TIME（查询空闲时段，如「明天下午有空吗」「下周哪些时间空闲」）、
                    CLEAR_SCHEDULE（批量清空某段时间的全部日程，如「下周一清空所有日程」「删掉明天的所有提醒」；
                    只处理日程本身，不涉及行程条目；没给日期范围的也要归到这里，由后续步骤追问）、
                    CHITCHAT（其他闲聊）。
                    %s
                    target 为操作目标：OUTDOOR_ACTIVITY / SELECTED_ITEM / NONE。
                    destination 为用户想去的城市名（仅中文城市名本身，如 "石家庄"、"洛阳"），无法确定时填 null。
                    slots 为用户在这条消息里明确给出的规划需求：destination 城市名、days 天数（整数）、
                    partySize 同行人数（整数，儿童也计入）、budget 总预算（数字，元）、startDate 出发日期（yyyy-MM-dd）。
                    没说的一律填 null，不要猜默认值。
                    结合对话历史理解指代与省略：用户说「那把第二天的酒店也换掉」「它太贵了」时，
                    要结合上一轮内容判断目标；分类仍然只看这一条消息的意图本身。
                    仅输出 JSON，格式：{"intent":"...","target":"...","destination":"...","constraints":{"date":"明天","weather":"rain"},"slots":{"destination":"上海","days":3,"partySize":2,"budget":3000,"startDate":"2026-11-21"},"alsoRequested":["其余请求的短语"],"tool":"工具名","args":{"参数":"值"}}
                    （intent 为 INVOKE_TOOL 时必须带 tool 与 args 两个字段；其他意图忽略 tool/args）
                    alsoRequested：如果同一条消息里还包含其他独立、可执行的请求（与 intent 不同的动作），
                    把其余请求各用一句短语放进数组，最多 3 条、每条不超过 40 字；没有就填 []。
                    当前仍只处理最重要的一个意图，不要把这些请求合并执行。
                    当前用户是否已有行程：%s
                    %s用户消息：
                    """.formatted(toolsBlock, trip != null ? "有 (tripId=" + trip.getId() + ")" : "无", historyBlock) + message;
            String raw = budget.chat(llmClient, List.of(
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
            if (!destination.isBlank() && !"null".equalsIgnoreCase(destination)
                    && CityNames.isPlausibleName(destination)) {
                constraints.put("destination", destination);
            }
            IntentResult.IntentResultBuilder builder = IntentResult.builder()
                    .intent(intent)
                    .target(node.path("target").asText(IntentResult.TARGET_NONE))
                    .constraints(constraints)
                    // P0-B 多意图：其余独立请求只作为「未执行提示」的来源，不改变本轮处理范围
                    .alsoRequested(parseAlsoRequested(node.path("alsoRequested")))
                    // 槽位以确定性解析为主，模型只补它听懂、但规则没解析出来的部分
                    .slots(RequirementSlots.fromMessage(message).fillMissingFrom(parseSlots(node.path("slots"))));
            if (IntentResult.INVOKE_TOOL.equals(intent)) {
                builder.toolName(node.path("tool").asText("").trim());
                if (node.hasNonNull("args") && node.path("args").isObject()) {
                    builder.toolArgs(objectMapper.convertValue(node.path("args"), new TypeReference<>() {}));
                }
            }
            return builder.build();
        } catch (Exception e) {
            log.warn("LLM 意图识别失败，使用规则兜底: {}", e.getMessage());
            return null;
        }
    }

    /** 模型输出的 slots 节点 → 槽位（字段缺失/非法一律视为 null，绝不让模型猜默认值） */
    private RequirementSlots parseSlots(JsonNode node) {
        if (node == null || !node.isObject()) {
            return null;
        }
        String destination = node.path("destination").asText("").trim();
        if (destination.isBlank() || "null".equalsIgnoreCase(destination)
                || !CityNames.isPlausibleName(destination)) {
            destination = null;
        }
        int days = node.path("days").asInt(0);
        int partySize = node.path("partySize").asInt(0);
        java.math.BigDecimal budget = null;
        if (node.hasNonNull("budget")) {
            try {
                budget = new java.math.BigDecimal(node.path("budget").asText("").trim());
            } catch (RuntimeException ignored) {
                // 非法金额当作没说
            }
        }
        java.time.LocalDate startDate = null;
        if (node.hasNonNull("startDate")) {
            try {
                startDate = java.time.LocalDate.parse(node.path("startDate").asText("").trim());
            } catch (RuntimeException ignored) {
                // 非法日期当作没说
            }
        }
        return new RequirementSlots(destination, days > 0 ? days : null,
                partySize > 0 ? partySize : null, budget, startDate);
    }

    /**
     * 解析模型给出的「其余未执行请求」（P0-B）：只保留非空且不超过 40 字的短语，最多 3 条。
     * 超长/空值直接丢弃（不截断成半句话），解析失败按空列表处理——多意图提示是补充信息，
     * 不能因为模型输出脏数据而影响主流程。
     */
    private List<String> parseAlsoRequested(JsonNode node) {
        if (node == null || !node.isArray()) {
            return List.of();
        }
        List<String> result = new ArrayList<>();
        for (JsonNode item : node) {
            String text = item.asText("").trim();
            if (text.isEmpty() || text.length() > MAX_ALSO_REQUESTED_LENGTH) {
                continue;
            }
            result.add(text);
            if (result.size() >= MAX_ALSO_REQUESTED) {
                break;
            }
        }
        return result;
    }

    /** 注册的 MCP 外部工具目录；未接入任何 MCP 工具时提示模型禁用 INVOKE_TOOL */
    private String renderToolCatalog() {
        List<AgentTool> mcpTools = toolRegistry.all().values().stream()
                .filter(t -> t.getName().startsWith("mcp_"))
                .toList();
        if (mcpTools.isEmpty()) {
            return "外部工具：本次未接入任何 MCP 外部工具，不要使用 INVOKE_TOOL。";
        }
        StringBuilder sb = new StringBuilder(
                "INVOKE_TOOL（通过外部 MCP 工具查询外部数据，如地图/百科/搜索；仅当用户明确要查外部数据且下面的工具适用时使用）。可用外部工具：\n");
        for (AgentTool tool : mcpTools) {
            sb.append("- ").append(tool.getName()).append("：").append(tool.getDescription()).append('\n');
        }
        return sb.toString();
    }

    /**
     * 最近对话渲染成简短的历史块；无历史返回空串，提示词里不出现多余空行。
     *
     * <p>条数由 {@code travel.conversation.history-limit} 决定（默认 10 条 ≈ 5 轮），
     * 由 ConversationService.recent() 截断后放进 AgentContext；这里每条再截 200 字控制提示词体积。
     * 历史只参与意图判断，不会被写进任何规划/操作计划。
     */
    private String renderHistory(AgentContext ctx) {
        List<com.travel.agent.entity.ConversationMessage> history = ctx.getHistory();
        if (history == null || history.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder("最近对话（时间正序，供理解指代，不要回复这些）：\n");
        for (com.travel.agent.entity.ConversationMessage m : history) {
            String content = m.getContent();
            if (content != null && content.length() > 200) {
                content = content.substring(0, 200) + "…";
            }
            sb.append("user".equals(m.getRole()) ? "用户：" : "助理：")
                    .append(content == null ? "" : content.replace("\n", " "))
                    .append('\n');
        }
        return sb.append('\n').toString();
    }

    private IntentResult ruleDetect(AgentContext ctx, Trip trip, String message) {
        // 0. 批量清空日程必须排在「修改类关键词」（删掉/取消/移除…）之前，
        //    否则「删掉明天所有日程」会被 MODIFY_ITINERARY 抢走，落到行程修改而不是日程。
        //    只认「清空类动词 + 日程/提醒」的组合：没有清空动词的「明天下午3点开评审会」仍是 ADD_SCHEDULE。
        boolean clearScheduleVerb = MessageParser.mentions(message,
                "清空", "全删", "删光", "清掉", "清除", "删除所有", "删掉所有", "都删掉",
                "取消所有", "全部删除", "全部删掉")
                || (MessageParser.mentions(message, "删除", "删掉", "取消", "移除")
                        && MessageParser.mentions(message, "所有", "全部"));
        if (clearScheduleVerb && MessageParser.mentions(message, "日程", "提醒")) {
            return IntentResult.builder()
                    .intent(IntentResult.CLEAR_SCHEDULE).target(IntentResult.TARGET_NONE)
                    .constraints(new HashMap<>()).build();
        }

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

        // 5. 日程（出行提醒）
        boolean scheduleQuery = MessageParser.mentions(message, "我的日程", "日程表", "日程安排", "有哪些安排", "日程有哪些");
        // 必须有明确时间点或排期动词才算"添加日程"。
        // 旧实现只要求关键词 + parseScheduleRequest 非 null，而后者对任何文本都返回非 null，
        // 于是"今天开会好累"这类陈述句会被真的写成一条日程。
        boolean hasExplicitTime = EXPLICIT_TIME.matcher(message).find();
        boolean hasSchedulingVerb = MessageParser.mentions(message,
                "排个", "排进", "安排个", "约个", "加入日程", "加到日程", "预约", "提醒我");
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

        // 6. 空闲时段查询（只读）：放在排日程判定之后（「明天下午3点开评审会」仍是 ADD_SCHEDULE），
        //    放在强规划与疑问句之前（「明天有空吗」不该落到知识问答）。
        //    带规划动词/行程尺度的消息不算空闲查询，交给后面的规划分支。
        boolean freeTimeQuestion = MessageParser.mentions(message,
                "有空", "空闲", "空档", "什么时候有时间", "哪些时间", "时间空不空")
                && !MessageParser.mentions(message, "规划", "想去", "行程", "几天", "天数");
        if (freeTimeQuestion) {
            return IntentResult.builder()
                    .intent(IntentResult.QUERY_FREE_TIME).target(IntentResult.TARGET_NONE).constraints(constraints).build();
        }

        // 7. 强规划意图：明确的规划动词，或「天数 + 出行词」组合，或「城市 + 天数」的出行说法
        //    （「三个人下周六去北京两天」没有规划动词，但目的地/天数俱全，就是一次规划请求；
        //     带疑问词的「上海三天怎么玩」仍按知识问答处理，交给下面的疑问句分支）
        boolean adviceQuestion = MessageParser.mentions(message,
                "什么", "哪些", "怎么", "攻略", "推荐", "介绍", "值得", "适合", "吗", "？", "?");
        boolean cityAndDays = MessageParser.guessCityName(message) != null
                && MessageParser.extractDays(message) > 0 && !adviceQuestion;
        boolean strongPlan = MessageParser.mentions(message, "规划", "安排", "计划", "想去", "帮我做一个")
                || (MessageParser.extractDays(message) > 0
                        && MessageParser.mentions(message, "旅行", "旅游", "游", "玩", "行程", "出发"))
                || cityAndDays;
        if (strongPlan) {
            return IntentResult.builder()
                    .intent(IntentResult.PLAN_TRIP).target(IntentResult.TARGET_NONE).constraints(constraints).build();
        }

        // 8. 疑问句 → 预算 / 知识问答
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
