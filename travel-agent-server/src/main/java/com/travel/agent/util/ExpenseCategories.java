package com.travel.agent.util;

import com.travel.agent.entity.Expense;

/**
 * 记账分类的唯一词表：把一段文本（用户消息 / 发票商户与原文）归到
 * {@code Expense.CAT_*} 之一，识别不出落 {@link Expense#CAT_OTHER}。
 *
 * <p>为什么抽到 {@code util}：同一套字面既服务对话记账（{@code MessageParser.parseExpenseRequest}），
 * 也服务发票报销（{@code OcrInvoiceService}）。两份词表一旦漂移，
 * 同一笔「酒店房费」在手动记账里是住宿、在发票报销里成了其他，用户无从理解。
 *
 * <p>顺序即优先级：门票 → 住宿 → 交通 → 餐饮 → 购物。「买门票」既有「门票」也有「买」，
 * 归门票；「买咖啡」命中餐饮（在有「咖啡」的情况下），只在都不命中时才落购物。
 */
public final class ExpenseCategories {

    private static final String[] TICKETS = {"门票", "景区票", "门票钱"};
    private static final String[] LODGING = {"酒店", "住宿", "房费", "民宿"};
    private static final String[] TRANSPORT = {
            "打车", "出租", "地铁", "公交", "高铁", "火车", "机票", "加油", "停车", "船票"};
    /** 餐饮词表刻意包含「早/午/晚/餐/饭/面」这类单字：中文里「午饭」「面」几乎只出现在吃上 */
    private static final String[] FOOD = {
            "早", "午", "晚", "餐", "饭", "面", "小吃", "咖啡", "奶茶", "烧烤", "火锅"};
    private static final String[] SHOPPING = {"买", "购物", "纪念", "特产", "手信"};

    private ExpenseCategories() {
    }

    /** 文本 → 分类常量（null / 空白 → 其他）；判定是「包含」而不是分词，与对话记账同一口径 */
    public static String of(String text) {
        if (text == null || text.isBlank()) {
            return Expense.CAT_OTHER;
        }
        if (mentions(text, TICKETS)) {
            return Expense.CAT_TICKET;
        }
        if (mentions(text, LODGING)) {
            return Expense.CAT_HOTEL;
        }
        if (mentions(text, TRANSPORT)) {
            return Expense.CAT_TRANSPORT;
        }
        if (mentions(text, FOOD)) {
            return Expense.CAT_FOOD;
        }
        if (mentions(text, SHOPPING)) {
            return Expense.CAT_SHOPPING;
        }
        return Expense.CAT_OTHER;
    }

    private static boolean mentions(String text, String[] keywords) {
        for (String keyword : keywords) {
            if (text.contains(keyword)) {
                return true;
            }
        }
        return false;
    }
}