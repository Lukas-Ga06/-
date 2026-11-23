package com.travel.agent.agent;

import com.travel.agent.dto.AgentDtos;
import com.travel.agent.dto.BudgetDtos;
import com.travel.agent.model.WeatherInfo;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.stream.Collectors;

/** Agent 回复文案模板（LLM 可用时会在此基础上润色） */
public final class MessageTemplates {

    private MessageTemplates() {
    }

    public static String planTrip(TripDraft draft, int attractions, int restaurants, boolean hasHotel, BigDecimal total) {
        StringBuilder sb = new StringBuilder();
        sb.append("已为你规划「").append(draft.getName()).append("」");
        if (draft.getStartDate() != null) {
            boolean sameYear = draft.getStartDate().getYear() == LocalDate.now().getYear();
            sb.append("（").append(draft.getStartDate().format(DateTimeFormatter.ofPattern(
                    sameYear ? "M月d日" : "yyyy年M月d日"))).append("出发");
            if (draft.getPartySize() != null && draft.getPartySize() > 0) {
                sb.append("，").append(draft.getPartySize()).append(" 人");
            }
            sb.append("）");
        }
        sb.append("：")
                .append(attractions).append("个景点、").append(restaurants).append("顿美食");
        if (hasHotel) {
            sb.append("，酒店已按你的偏好挑选");
        }
        sb.append("，预计总花费 ¥").append(total.stripTrailingZeros().toPlainString());
        if (draft.getBudget() != null) {
            sb.append(total.compareTo(draft.getBudget()) <= 0
                    ? "，在你 ¥" + draft.getBudget().stripTrailingZeros().toPlainString() + " 的预算内。"
                    : "，已尽量向 ¥" + draft.getBudget().stripTrailingZeros().toPlainString() + " 的预算靠拢。");
        }
        sb.append("请查看时间轴与地图，确认后即可保存行程。");
        return sb.toString();
    }

    /**
     * 没有可用真实天气时的修改文案：<b>不提天气</b>。
     * 拿不到真实天气就绝不写「因为下雨」——那会让用户以为做过天气判断（ADR-006）。
     */
    public static String modify(List<String> replacements, int day) {
        return modifyText(null, replacements);
    }

    /** 有真实预报时的修改文案（调用方不得传 null：没有天气时走 {@link #modify(List, int)}） */
    public static String modify(WeatherInfo weather, List<String> replacements, int day) {
        return modifyText(weather, replacements);
    }

    /** 唯一实现：{@code weather} 为 null 表示本轮没有可用天气，此时不写任何天气结论 */
    private static String modifyText(WeatherInfo weather, List<String> replacements) {
        StringBuilder sb = new StringBuilder();
        if (weather != null && weather.isRainy()) {
            sb.append("明天").append(weather.city()).append("预计").append(weather.condition())
                    .append("（降雨概率 ").append(weather.rainProbability())
                    // 模拟天气只在测试/离线演示开关下出现，文案必须自报来源
                    .append(weather.isSimulated() ? "%，模拟天气" : "%").append("），");
        }
        sb.append("建议调整：").append(String.join("，", replacements)).append("。");
        sb.append("请在方案对比中确认修改。");
        return sb.toString();
    }

    public static String budgetSummary(String tripName, int itemCount, AgentDtos.BudgetSummary budget, BigDecimal tripBudget) {
        StringBuilder sb = new StringBuilder("行程「").append(tripName).append("」共 ")
                .append(itemCount).append(" 项安排，预计总花费 ¥")
                .append(budget.getTotal().stripTrailingZeros().toPlainString());
        if (tripBudget != null) {
            sb.append(tripBudget.compareTo(budget.getTotal()) >= 0 ? "（预算 ¥" : "，已超出预算 ¥")
                    .append(tripBudget.stripTrailingZeros().toPlainString());
            sb.append(tripBudget.compareTo(budget.getTotal()) >= 0 ? "）" : "）");
        }
        if (budget.getBreakdown() != null && !budget.getBreakdown().isEmpty()) {
            sb.append("。分项：");
            budget.getBreakdown().forEach((type, cost) ->
                    sb.append(translateType(type)).append(" ¥").append(cost.stripTrailingZeros().toPlainString()).append("，"));
            sb.setLength(sb.length() - 1);
        }
        return sb.toString();
    }

    /** 预算执行：计划 / 预算 / 实际 / 偏差 + 分类对账（统一口径，供 Agent 与接口复用） */
    public static String budgetExecution(BudgetDtos.BudgetExecution e) {
        StringBuilder sb = new StringBuilder("行程「").append(e.getTripName()).append("」：计划 ¥")
                .append(plain(e.getPlanned()));
        if (e.getBudget() != null) {
            sb.append("，预算 ¥").append(plain(e.getBudget()));
        }
        sb.append("，已支出 ¥").append(plain(e.getActual()));
        if (e.getRemaining() != null) {
            sb.append(e.getRemaining().signum() >= 0 ? "，剩余预算 ¥" : "，已超预算 ¥")
                    .append(plain(e.getRemaining().abs()));
        }
        sb.append("；较计划").append(e.getVariance().signum() >= 0 ? "多" : "少")
                .append(" ¥").append(plain(e.getVariance().abs())).append("。");
        if (e.isPlanOverBudget()) {
            sb.append("⚠ 仅计划成本就已超出预算。");
        }
        if (!e.getCategories().isEmpty()) {
            sb.append(" 分项：").append(e.getCategories().stream()
                    .map(c -> c.getCategory() + " 计划 ¥" + plain(c.getPlanned())
                            + " / 实际 ¥" + plain(c.getActual()))
                    .collect(Collectors.joining("，"))).append("。");
        }
        return sb.toString();
    }

    private static String plain(BigDecimal value) {
        return value == null ? "0" : value.stripTrailingZeros().toPlainString();
    }

    public static String translateType(String type) {
        return switch (type) {
            case "hotel" -> "酒店";
            case "attraction" -> "景点";
            case "restaurant" -> "餐饮";
            case "transport" -> "交通";
            default -> type;
        };
    }

    public static final String HELP = """
            我是你的智能旅行助手，目前可用：
            ① 规划行程：「帮我规划上海三日游，2 人，预算 3000，酒店近地铁」
            ② 动态调整：「明天下雨，把户外景点换掉」（也可以先点选时间轴上的安排，说「这个换成类似的」）
            ③ 记账：「记一笔午餐 45 元」「微信付了 55 元打车」
            ④ 备忘：「记一下明天办签证」「我的备忘」
            ⑤ 预算查询：「现在花了多少钱？」
            ⑥ 旅游问答：「上海有什么适合亲子游的地方？」""";
}
