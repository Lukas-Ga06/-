package com.travel.agent.controller;

import com.travel.agent.dto.ApiResponse;
import com.travel.agent.config.AmapProperties;
import com.travel.agent.config.RateLimitProperties;
import com.travel.agent.llm.LlmProperties;
import com.travel.agent.llm.LlmRuntimeStatus;
import lombok.RequiredArgsConstructor;
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
 *
 * <p>自报口径（V2.2 验收 P2/P5）：**「配置了」与「真的能用」分开报**——
 * <ul>
 *   <li>{@code llmConfigured}（配置里有 Key）/ {@code llmVerified}（最近一次真实调用成功）；</li>
 *   <li>{@code routeProvider}（现行口径，仅 amap|estimated）、{@code routeConfigured}（配了 Key 或 MCP）、
 *       {@code amapVerified}（最近一次真实路径调用成功）、{@code routeLastError}（失败原因，含 infocode，不含 Key）。</li>
 * </ul>
 * 只配了 Key 但一次都没成功调用过时，{@code routeProvider} 必须是 estimated——估算不能伪装成实时（ADR-006）。
 */
@RestController
@RequestMapping("/api/system")
@RequiredArgsConstructor
public class SystemController {

    private final LlmProperties llmProperties;
    private final RateLimitProperties rateLimitProperties;
    private final com.travel.agent.service.LlmQuotaService llmQuotaService;
    private final AmapProperties amapProperties;
    /** V2.2 FR-05：如实上报路线数据来源（真实路径 / 估算），不涉密钥 */
    private final com.travel.agent.service.RouteService routeService;
    /** P5：最近一次 LLM 调用结果（与「配置了 Key」分离） */
    private final LlmRuntimeStatus llmRuntimeStatus;
    /**
     * 价格能力自报（ADR-006）：当前只有本地参考估算，没有实时房态/库存源。
     * 与 routeProvider 口径一致——没有的能力不许在状态页上宣称。
     */
    private final com.travel.agent.service.PriceReferenceService priceReferenceService;

    @GetMapping("/status")
    public ApiResponse<Map<String, Object>> status() {
        Map<String, Object> status = new LinkedHashMap<>();
        boolean llm = llmProperties.usable();
        boolean amap = amapProperties.usable();

        status.put("llmConfigured", llm);
        // P5：配置态与最近调用态分离——Key 在但 100% 失败时 llmVerified=false，前端不再误以为「AI 在线」
        status.put("llmVerified", llmRuntimeStatus.verified());
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

        // 路线数据来源（FR-05 + P2）：provider 的取值域仍是 amap|estimated（前端系统状态卡与既有测试依赖），
        // 另用 routeConfigured / amapVerified / routeLastError 说明「配置了」「验证过了」「为什么失败」。
        String routeProvider = routeService.provider();
        boolean routeConfigured = routeService.configured();
        boolean routeVerified = routeService.verified();
        String routeLastError = routeService.lastError();
        status.put("routeProvider", routeProvider);
        status.put("routeConfigured", routeConfigured);
        status.put("amapVerified", routeVerified);
        status.put("routeLastError", routeLastError);
        status.put("routeHint", routeHint(routeProvider, routeConfigured, routeLastError));

        // 价格数据来源：只有本地参考估算（reference-estimate），没有实时房价/库存提供商。
        // priceHint 必须与之一致——前端据此在价格旁固定展示「参考价」与免责声明。
        status.put("priceProvider", priceReferenceService.provider());
        status.put("priceHint", priceReferenceService.hint());

        // 运维可见性：待确认方案已落库（多实例可用），限流是否开启
        status.put("pendingPlanStore", "database");
        status.put("rateLimitEnabled", rateLimitProperties.isEnabled());

        // LLM 每日配额：当前登录用户的用量（配额关闭时 limit 为 null，前端不展示进度）
        String user = com.travel.agent.security.UserContext.get();
        if (llmProperties.getQuotaDailyLimit() > 0 && user != null && !user.isBlank()) {
            status.put("llmQuotaUsed", llmQuotaService.usedToday(user));
            status.put("llmQuotaLimit", llmProperties.getQuotaDailyLimit());
        }
        return ApiResponse.ok(status);
    }

    /** 路线提示文案：必须与 routeProvider 口径一致，不许在降级时仍宣称「使用高德真实路径」 */
    private static String routeHint(String routeProvider, boolean routeConfigured, String routeLastError) {
        if ("amap".equals(routeProvider)) {
            return "路线使用高德真实路径规划（打车费用仍为本地估算）";
        }
        if (!routeConfigured) {
            return "路线为本地距离估算（未配置 AMAP_KEY / 高德 MCP）：距离与耗时为近似值";
        }
        if (routeLastError != null && !routeLastError.isBlank()) {
            return "高德已配置但最近一次真实调用失败（" + routeLastError
                    + "）：当前路线为本地估算，恢复后会自动重新使用真实路径";
        }
        return "高德已配置但尚未验证（启动后还没有一次真实路径调用成功）：当前路线为本地估算";
    }
}
