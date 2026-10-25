package com.travel.agent.service;

import com.travel.agent.llm.LlmClient;
import com.travel.agent.llm.LlmMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 方案文案润色：把规则模板生成的确认/方案说明交给模型重写得更自然。
 *
 * 事实底线（日期、价格、名称、条数）全部来自模板原文，模型只做语言组织，
 * 不允许新增信息；输出做长度与关键数字校验，不达标一律回退模板原文。
 * 用独立的紧读超时，模型慢时不拖住整条对话链路。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class PlanNarrationService {

    /** 润色专用读超时：辅助能力不值得让用户等全局 30s */
    private static final int POLISH_READ_TIMEOUT_MS = 8000;
    private static final int MAX_OUTPUT_CHARS = 800;

    private static final Pattern NUMBERS = Pattern.compile("\\d+(?:\\.\\d+)?");

    private final LlmClient llmClient;

    /** 润色失败/不可用时原样返回模板，调用方无需判空 */
    public String polish(String template) {
        if (template == null || template.isBlank() || !llmClient.available()) {
            return template;
        }
        try {
            String polished = llmClient.chat(List.of(
                    LlmMessage.system("你是旅行助理的文案编辑。只输出润色后的中文正文，不要任何解释、前后缀或 Markdown 代码块。"),
                    LlmMessage.user(PROMPT + template)), 0.4, POLISH_READ_TIMEOUT_MS);
            if (polished == null) {
                return template;
            }
            String text = polished.trim();
            if (!acceptable(template, text)) {
                log.info("方案文案润色结果未通过校验，回退模板原文");
                return template;
            }
            return text;
        } catch (Exception e) {
            log.warn("方案文案润色失败，使用模板原文: {}", e.getMessage());
            return template;
        }
    }

    /** 模板原文直接拼接，不走 String.format——模板里可能出现「降雨概率 30%」这类百分号 */
    private static final String PROMPT = """
            下面是一段给用户看的行程方案说明（模板生成，事实准确但生硬）。
            请重写得更自然、有温度，像真人旅行规划师说的话。要求：
            1. 只重新组织语言，严禁新增、修改或省略任何事实（城市、日期、天数、金额、景点名、酒店名、条数）；
            2. 保留换行结构：原文按行分点的，结果也按行分点；
            3. 长度与原文相当，不超过 800 字。
            原文：
            """;

    /** 长度失控或丢了模板里的最大金额，都视为不可信输出 */
    private static boolean acceptable(String template, String polished) {
        if (polished.isBlank() || polished.length() > MAX_OUTPUT_CHARS) {
            return false;
        }
        if (polished.length() < Math.max(20, template.length() / 3)) {
            return false;
        }
        return containsKeyAmount(template, polished);
    }

    /** 模板里出现的最大数字（通常是总花费）必须在润色结果中保留 */
    private static boolean containsKeyAmount(String template, String polished) {
        double max = 0;
        for (Matcher m = NUMBERS.matcher(template); m.find(); ) {
            try {
                max = Math.max(max, Double.parseDouble(m.group()));
            } catch (NumberFormatException ignored) {
                // 理论上正则保证可解析，这里仅防御
            }
        }
        if (max < 100) {
            return true; // 没有大金额的文案（如纯修改说明）不做数字强校验
        }
        return polished.contains(trimNumber(max));
    }

    /** 去掉整数金额可能的小数尾巴，按整数串匹配（1274.0 → "1274"） */
    private static String trimNumber(double value) {
        return value == Math.rint(value) ? String.valueOf((long) value) : String.valueOf(value);
    }
}
