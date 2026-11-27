package com.travel.agent.tool.impl;

import com.travel.agent.entity.User;
import com.travel.agent.exception.BusinessException;
import com.travel.agent.security.UserContext;
import com.travel.agent.service.AuthService;
import com.travel.agent.service.ocr.InvoiceFields;
import com.travel.agent.service.ocr.OcrInvoiceService;
import com.travel.agent.tool.AgentTool;
import com.travel.agent.tool.RiskLevel;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * ocr_invoice：识别「当前用户最新一张发票照片」的只读查询入口（R1）。
 *
 * <p>只读：不发方案、不落库，结果形如
 * {@code {available, reason, photoId, amount, merchant, date, category}}；
 * 未配置 OCR 或识别失败时 {@code available=false} + 原因，
 * <b>不会编造金额/商户</b>——模型据此要么如实说没识别到，要么请用户手动记账。
 *
 * <p>分工：用户说「报销这张发票」时由 {@code InvoiceCommandHandler} 直接调
 * {@link OcrInvoiceService} 生成待确认方案（单一权威，避免同一张发票走两条路径）；
 * 本工具是给模型研究链路（{@code INVOKE_TOOL} / 规划取证）用的只读入口。
 */
@Component
@RequiredArgsConstructor
public class OcrInvoiceTool implements AgentTool {

    private final OcrInvoiceService ocrInvoiceService;
    private final AuthService authService;

    @Override
    public String getName() {
        return "ocr_invoice";
    }

    @Override
    public String getDescription() {
        return "识别当前用户最新上传的发票照片（金额/商户/日期/类目）；未配置或识别失败时返回 available=false 与原因，"
                + "不会返回模拟结果";
    }

    @Override
    public RiskLevel getRiskLevel() {
        return RiskLevel.LOW;
    }

    /** 零参数：识别对象由「当前用户 + 最新发票照片」唯一确定，不接受模型指定的 photoId */
    @Override
    public List<ToolParameter> getParameters() {
        return List.of();
    }

    @Override
    public Object execute(Map<String, Object> args) {
        String username = UserContext.get();
        if (username == null || username.isBlank()) {
            return unavailable("没有当前用户上下文");
        }
        User user;
        try {
            user = authService.requireUser(username);
        } catch (BusinessException e) {
            // 工具层 fail-soft：账号不存在/已注销也要给出可解释的结果，不让研究循环直接炸掉
            return unavailable("找不到当前用户：" + e.getMessage());
        }
        OcrInvoiceService.InvoiceLookup lookup = ocrInvoiceService.recognizeLatest(user.getId());
        LinkedHashMap<String, Object> result = new LinkedHashMap<>();
        result.put("available", lookup.available());
        result.put("reason", lookup.reason());
        result.put("photoId", lookup.photoId());
        InvoiceFields fields = lookup.fields();
        result.put("amount", fields == null ? null : fields.amount());
        result.put("merchant", fields == null ? null : fields.merchant());
        result.put("date", fields == null || fields.invoiceDate() == null ? null : fields.invoiceDate().toString());
        result.put("category", fields == null ? null : fields.category());
        return result;
    }

    private static Map<String, Object> unavailable(String reason) {
        LinkedHashMap<String, Object> result = new LinkedHashMap<>();
        result.put("available", false);
        result.put("reason", reason);
        result.put("photoId", null);
        result.put("amount", null);
        result.put("merchant", null);
        result.put("date", null);
        result.put("category", null);
        return result;
    }
}