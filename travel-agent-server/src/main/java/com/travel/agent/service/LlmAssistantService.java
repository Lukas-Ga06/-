package com.travel.agent.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.travel.agent.entity.HealthRecord;
import com.travel.agent.llm.LlmClient;
import com.travel.agent.llm.LlmMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 个人助理的 LLM 生成中枢：办公自动化（邮件分类/会议纪要/日报/发票提取）
 * 与学习辅助（知识框架/学习路线/重点归纳）。
 * 每个能力都有规则兜底：LLM 未配置或调用失败时功能仍然可用。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class LlmAssistantService {

    private final LlmClient llmClient;
    private final ObjectMapper objectMapper;

    private String chat(String system, String user) {
        if (!llmClient.available()) {
            return null;
        }
        return llmClient.chat(List.of(LlmMessage.system(system), LlmMessage.user(user)), 0.4);
    }

    private String stripFences(String raw) {
        return raw == null ? null : raw.replace("```json", "").replace("```", "").trim();
    }

    // ==================== 邮件分类 ====================

    public record MailResult(String category, String summary, String suggestedReply) {}

    public MailResult classifyMail(String text) {
        String raw = chat("你是邮件分类助手，只输出 JSON。",
                "对以下邮件分类（category ∈ 重要/会议/账单/通知/垃圾/其他），给出 summary（50 字内摘要）"
                        + "与 suggestedReply（一句中文建议回复）。仅输出 JSON："
                        + "{\"category\":\"...\",\"summary\":\"...\",\"suggestedReply\":\"...\"}\n邮件内容：\n" + text);
        if (raw != null) {
            try {
                JsonNode n = objectMapper.readTree(stripFences(raw));
                String category = n.path("category").asText("其他");
                String summary = n.path("summary").asText("");
                String reply = n.path("suggestedReply").asText("");
                if (!category.isBlank()) {
                    return new MailResult(category,
                            summary.isBlank() ? text.substring(0, Math.min(50, text.length())) : summary,
                            reply.isBlank() ? "已收到，谢谢。" : reply);
                }
            } catch (Exception e) {
                log.warn("邮件分类 JSON 解析失败: {}", e.getMessage());
            }
        }
        // 规则兜底
        String category = "其他";
        if (text.contains("会议") || text.contains("开会")) category = "会议";
        else if (text.contains("发票") || text.contains("账单") || text.contains("缴费") || text.contains("还款")) category = "账单";
        else if (text.contains("验证码") || text.contains("中奖") || text.contains("推广") || text.contains("优惠")) category = "垃圾";
        else if (text.contains("重要") || text.contains("紧急") || text.contains("截止")) category = "重要";
        else if (text.contains("通知") || text.contains("公告")) category = "通知";
        String summary = text.length() > 50 ? text.substring(0, 50) + "…" : text;
        return new MailResult(category, summary.replace('\n', ' '), "已收到，谢谢。");
    }

    // ==================== 会议纪要 ====================

    public String generateMinutes(String title, String rawText) {
        String raw = chat("你是会议纪要生成器，输出 Markdown 格式的结构化纪要。",
                "根据以下会议记录生成纪要，结构：\n## 会议主题\n## 讨论要点\n- 要点\n## 结论\n- 结论\n## 待办事项\n- [ ] 待办\n\n"
                        + "会议标题：" + title + "\n会议记录：\n" + rawText);
        return raw != null ? raw : fallbackMinutes(rawText);
    }

    private String fallbackMinutes(String rawText) {
        StringBuilder sb = new StringBuilder("## 会议纪要（规则生成，建议配置 LLM 获得更佳效果）\n\n### 记录要点\n");
        for (String line : rawText.split("\n")) {
            String t = line.trim();
            if (!t.isBlank()) {
                sb.append("- ").append(t).append('\n');
            }
        }
        sb.append("\n### 待办事项\n- [ ] 确认以上事项并分工");
        return sb.toString();
    }

    // ==================== 日报 ====================

    public String generateDailyReport(String facts) {
        String raw = chat("你是个人日报写作助手，输出简洁专业的 Markdown 日报。",
                "根据以下当日真实数据写一份个人日报，结构：\n## 今日概览\n## 关键事项\n## 明日建议\n"
                        + "只使用数据中出现的事实，不要编造。\n当日数据：\n" + facts);
        return raw != null ? raw : "## 今日概览（数据自动汇总）\n" + facts;
    }

    // ==================== 发票提取 ====================

    public record InvoiceResult(String title, String vendor, String category, BigDecimal amount, LocalDate date) {}

    public InvoiceResult extractInvoice(String text) {
        String raw = chat("你是发票信息提取助手，只输出 JSON。",
                "从以下发票文本提取字段，category ∈ 交通/餐饮/办公/住宿/其他，date 格式 yyyy-MM-dd（无法判断填今天日期）。仅输出 JSON："
                        + "{\"title\":\"发票抬头\",\"vendor\":\"开票方\",\"category\":\"...\",\"amount\":0,\"date\":\"yyyy-MM-dd\"}\n发票文本：\n" + text);
        if (raw != null) {
            try {
                JsonNode n = objectMapper.readTree(stripFences(raw));
                BigDecimal amount = new BigDecimal(n.path("amount").asText("0"));
                if (amount.signum() > 0) {
                    LocalDate date;
                    try {
                        date = LocalDate.parse(n.path("date").asText(LocalDate.now().toString()));
                    } catch (Exception e) {
                        date = LocalDate.now();
                    }
                    String category = n.path("category").asText("其他");
                    String title = n.path("title").asText("").trim();
                    if (title.isEmpty()) title = "个人";
                    return new InvoiceResult(
                            title,
                            n.path("vendor").asText(""),
                            List.of("交通", "餐饮", "办公", "住宿", "其他").contains(category) ? category : "其他",
                            amount, date);
                }
            } catch (Exception e) {
                log.warn("发票提取 JSON 解析失败: {}", e.getMessage());
            }
        }
        // 规则兜底
        BigDecimal amount = null;
        Matcher m = Pattern.compile("(?i)(?:价税合计|合计|金额|total|amount)\\s*[:：]?\\s*[¥$]?\\s*([0-9]+(?:\\.[0-9]+)?)").matcher(text);
        if (m.find()) amount = new BigDecimal(m.group(1));
        if (amount == null) return null;
        LocalDate date = LocalDate.now();
        Matcher dm = Pattern.compile("(\\d{4})\\s*[-/年]\\s*(\\d{1,2})\\s*[-/月]\\s*(\\d{1,2})\\s*日?").matcher(text);
        if (dm.find()) {
            try {
                date = LocalDate.of(Integer.parseInt(dm.group(1)),
                        Integer.parseInt(dm.group(2)), Integer.parseInt(dm.group(3)));
            } catch (Exception ignored) {}
        }
        String title = "个人";
        String vendor = "";
        for (String line : text.split("\n")) {
            if (line.contains("公司") && vendor.isBlank()) vendor = line.trim();
        }
        String lower = text.toLowerCase();
        String category = text.contains("旅客运输") || text.contains("出租")
                || lower.contains("taxi") || lower.contains("transport")
                || lower.contains("flight") || lower.contains("train") ? "交通"
                : text.contains("住宿") || lower.contains("hotel") ? "住宿"
                : text.contains("餐饮") || lower.contains("restaurant") || lower.contains("meal") ? "餐饮" : "办公";
        return new InvoiceResult(title, vendor, category, amount, date);
    }

    // ==================== 学习辅助 ====================

    public String generateFramework(String topic) {
        String raw = chat("你是知识整理专家，输出 Markdown 知识框架。",
                "为主题「" + topic + "」生成知识框架：用 ## 一级分支、### 二级分支、- 关键点 的层级结构，覆盖核心概念、原理、应用、常见误区。");
        return raw != null ? raw : "## " + topic + " 知识框架（通用骨架）\n\n### 核心概念\n- 定义与边界\n- 关键术语\n\n### 基本原理\n- 底层逻辑\n- 运行机制\n\n### 实践应用\n- 典型场景\n- 工具与方法\n\n### 常见误区\n- 易混淆点\n- 新手陷阱";
    }

    public String generatePath(String topic) {
        String raw = chat("你是学习路线规划师，输出 Markdown 学习路线。",
                "为目标「" + topic + "」规划学习路线：分 3-4 个阶段（## 阶段一：xxx），每阶段包含 目标、关键内容（- 条目）、产出物、建议时长。");
        return raw != null ? raw : "## " + topic + " 学习路线（通用骨架）\n\n## 阶段一：入门\n- 目标：建立整体认知\n- 产出：学习笔记\n- 时长：2 周\n\n## 阶段二：核心实践\n- 目标：掌握核心技能\n- 产出：完成一个小项目\n\n## 阶段三：进阶与输出\n- 目标：形成体系\n- 产出：总结分享";
    }

    public String generateSummary(String text) {
        String raw = chat("你是重点归纳助手，输出 Markdown。",
                "归纳以下内容的重点：\n## 核心观点\n- 要点\n## 关键细节\n- 细节\n## 行动建议\n- 建议\n\n内容：\n" + text);
        if (raw != null) return raw;
        StringBuilder sb = new StringBuilder("## 重点归纳（规则生成）\n\n### 内容要点\n");
        for (String line : text.split("\n")) {
            String t = line.trim();
            if (t.length() > 8) sb.append("- ").append(t.length() > 80 ? t.substring(0, 80) + "…" : t).append('\n');
        }
        return sb.toString();
    }

    // ==================== 健康建议（规则） ====================

    public String healthAdvice(HealthRecord latest, double avgSteps, double avgSleep) {
        if (latest == null) {
            return "暂无健康数据，先导入或记录一天的数据吧。";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("最近一周平均步数 ").append((int) avgSteps).append("，平均睡眠 ")
                .append(String.format("%.1f", avgSleep)).append(" 小时。");
        if (avgSteps < 6000) sb.append(" 步数偏少，建议每天增加 30 分钟快走。");
        else sb.append(" 步数表现不错，保持规律运动。");
        if (avgSleep < 7) sb.append(" 睡眠不足 7 小时，建议提前 30 分钟入睡。");
        else sb.append(" 睡眠时长健康。");
        if (latest.getRestingHeartRate() != null && latest.getRestingHeartRate() > 90) {
            sb.append(" 静息心率偏高，请注意休息，必要时就医复查。");
        }
        return sb.toString();
    }
}
