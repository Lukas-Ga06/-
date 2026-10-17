package com.travel.agent.controller;

import com.travel.agent.dto.ApiResponse;
import com.travel.agent.config.RateLimitProperties;
import com.travel.agent.llm.LlmProperties;
import com.travel.agent.service.InvoiceTextExtractor;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 运行能力自检：把「大模型是否可用、联网定位走哪条路」等外部依赖状态暴露给前端。
 *
 * 之所以需要它：未配置 LLM_API_KEY 时 Agent 会静默降级为规则引擎、未配置 AMAP_KEY 时
 * 城市定位会退到 OSM，使用者容易误以为"AI 不能联网/不会规划"。这里只返回布尔与说明，不泄露任何密钥。
 */
@RestController
@RequestMapping("/api/system")
@RequiredArgsConstructor
public class SystemController {

    private final LlmProperties llmProperties;
    private final InvoiceTextExtractor invoiceTextExtractor;
    private final RateLimitProperties rateLimitProperties;

    @Value("${travel.amap.key:}")
    private String amapKey;

    @GetMapping("/status")
    public ApiResponse<Map<String, Object>> status() {
        Map<String, Object> status = new LinkedHashMap<>();
        boolean llm = llmProperties.usable();
        boolean amap = amapKey != null && !amapKey.isBlank();

        status.put("llmConfigured", llm);
        status.put("llmModel", llm ? llmProperties.getModel() : null);
        status.put("llmHint", llm
                ? "已接入大模型：意图识别、方案生成与文案润色走模型"
                : "未配置 LLM_API_KEY：当前为规则引擎模式（功能完整，超纲目的地与生僻说法识别较弱）");

        status.put("amapConfigured", amap);
        status.put("geoProvider", amap ? "amap" : "osm");
        status.put("geoHint", amap
                ? "高德 Web 服务已配置：城市与 POI 定位使用真实数据"
                : "未配置 AMAP_KEY：城市定位退回 OSM Nominatim，国内网络可能较慢或失败");

        status.put("timezone", ZoneId.systemDefault().getId());
        status.put("ocrConfigured", invoiceTextExtractor.ocrConfigured());
        status.put("ocrHint", invoiceTextExtractor.ocrConfigured()
                ? "已配置 Tesseract：图片发票可直接 OCR"
                : "未配置 OCR：支持 PDF 电子发票与粘贴文本；图片需设置 TESSERACT_CMD");

        // 运维可见性：待确认方案已落库（多实例可用），限流是否开启
        status.put("pendingPlanStore", "database");
        status.put("rateLimitEnabled", rateLimitProperties.isEnabled());
        return ApiResponse.ok(status);
    }
}
