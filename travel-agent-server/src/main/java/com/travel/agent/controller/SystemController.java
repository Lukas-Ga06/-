package com.travel.agent.controller;

import com.travel.agent.config.AmapProperties;
import com.travel.agent.config.RateLimitProperties;
import com.travel.agent.dto.ApiResponse;
import com.travel.agent.llm.LlmModelProbe;
import com.travel.agent.llm.LlmProperties;
import com.travel.agent.llm.LlmRuntimeStatus;
import com.travel.agent.mcp.AmapMcpAdapter;
import com.travel.agent.mcp.McpToolBridge;
import com.travel.agent.tool.ToolRegistry;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 运行能力自检：把「大模型是否可用、联网定位走哪条路」等外部依赖状态暴露给前端。
 *
 * 之所以需要它：未配置 LLM_API_KEY 时 Agent 会静默降级为规则引擎、未配置 AMAP_KEY 时
 * 城市定位会退到 OSM，使用者容易误以为"AI 不能联网/不会规划"。这里只返回布尔与说明，不泄露任何密钥。
 *
 * <p>自报口径（V2.2 验收 P2/P5 + P0-A）：
 * <ul>
 *   <li>{@code llmConfigured}（配置里有 Key）/ {@code llmVerified}（最近一次真实调用成功）；</li>
 *   <li>{@code llmProvider}（base-url 的 host，不含 Key/路径）/ {@code llmModelAvailable}
 *       （启动探测 /models 的结论，null=尚未校验）/ {@code llmModelError} / {@code llmModelCheckPending}；</li>
 *   <li>{@code routeProvider}（现行口径，仅 amap|estimated）、{@code routeConfigured}（配了 Key 或 MCP）、
 *       {@code amapVerified}（最近一次真实路径调用成功）、{@code routeLastError}（失败原因，含 infocode，不含 Key）；</li>
 *   <li>{@code mcpEnabled} / {@code mcpConnected} / {@code mcpToolCount} / {@code mcpServers}
 *       / {@code mcpLastError} / {@code mcpHint}（MCP 接入状态，P0-A）/ {@code amapMcpToolCount}；</li>
 *   <li>{@code weatherProvider}（仅 open-meteo|simulated|unavailable）/ {@code weatherLastError}
 *       （最近一次真实失败原因，不含密钥）/ {@code weatherHint}（P0 数据源自报：产品路径无模拟天气）。</li>
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
    /** P0-A：MCP 接入状态快照（启用/连接/工具数/每 server 最近错误） */
    private final McpToolBridge mcpToolBridge;
    /** P0-A：高德 MCP 语义工具发现（只读，不调用远端） */
    private final AmapMcpAdapter amapMcpAdapter;
    /** P0-A：注册表用于统计已接入的高德 MCP 工具数 */
    private final ToolRegistry toolRegistry;
    /** P0-A：启动后异步探测的模型可用性（null=尚未校验，不阻塞状态接口） */
    private final LlmModelProbe llmModelProbe;
    /**
     * 天气来源自报（ADR-006 延续）：产品路径只认真实预报。
     * provider 只可能是 open-meteo|simulated|unavailable，模拟只在测试/离线演示开关下出现。
     */
    private final com.travel.agent.service.WeatherService weatherService;

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

        // P0-A：provider 只暴露 host（不含协议/路径/Key）；模型是否可用由启动探测给出，
        // null 表示「还没校验」（未配置 Key / 探测未开启 / 探测请求失败），不能当成可用。
        LlmModelProbe.ModelCheck modelCheck = llmModelProbe.status();
        status.put("llmProvider", llm ? llmProperties.providerHost() : null);
        status.put("llmModelAvailable", llm ? modelCheck.available() : null);
        status.put("llmModelError", llm ? modelCheck.error() : null);
        status.put("llmModelCheckPending", llm && modelCheck.pending());

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

        // 天气来源自报：产品路径永不使用模拟天气，拿不到真实预报就是 unavailable；
        // 依赖天气的行程修改会被明确拒绝，而不是拿模拟数据把流程演下去。
        String weatherProvider = weatherService.provider();
        String weatherLastError = weatherService.lastError();
        status.put("weatherProvider", weatherProvider);
        status.put("weatherLastError", weatherLastError);
        status.put("weatherHint", weatherHint(weatherProvider, weatherLastError));

        // P0-A：MCP 接入状态。mcpConnected 只在「桥接已启动且至少一个 server 连接成功」时为 true；
        // 大模型可用的外部工具数来自桥接快照，高德语义工具数来自适配层（两者口径不同，分开报）。
        McpToolBridge.McpStatusSnapshot mcp = mcpToolBridge.status();
        boolean mcpConnected = mcp.running() && mcp.servers().stream().anyMatch(McpToolBridge.McpServerStatus::connected);
        int amapMcpToolCount = amapMcpAdapter.discover(toolRegistry).size();
        status.put("mcpEnabled", mcp.enabled());
        status.put("mcpConnected", mcpConnected);
        status.put("mcpToolCount", mcp.toolCount());
        status.put("mcpServers", mcpServers(mcp));
        status.put("mcpLastError", mcp.lastError());
        status.put("mcpHint", mcpHint(mcp, amapMcpToolCount));
        status.put("amapMcpToolCount", amapMcpToolCount);

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

    /** 每个 MCP server 的接入结果（name/connected/toolCount/lastError），前端可直接渲染诊断卡 */
    private static List<Map<String, Object>> mcpServers(McpToolBridge.McpStatusSnapshot mcp) {
        return mcp.servers().stream().map(server -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("name", server.name());
            row.put("connected", server.connected());
            row.put("toolCount", server.toolCount());
            row.put("lastError", server.lastError());
            return row;
        }).toList();
    }

    /** MCP 提示文案：与 mcpEnabled / mcpConnected / mcpToolCount 口径一致，不夸大接入状态 */
    private static String mcpHint(McpToolBridge.McpStatusSnapshot mcp, int amapMcpToolCount) {
        if (!mcp.enabled()) {
            return "MCP 未启用（travel.mcp.enabled=false）：仅使用内置工具";
        }
        boolean anyConnected = mcp.running()
                && mcp.servers().stream().anyMatch(McpToolBridge.McpServerStatus::connected);
        if (mcp.toolCount() == 0) {
            if (mcp.lastError() != null && !mcp.lastError().isBlank()) {
                return "MCP 已启用但接入失败（" + mcp.lastError() + "）：当前没有外部工具可用";
            }
            return anyConnected
                    ? "MCP 已连接服务器但没有注册任何工具：仅使用内置工具"
                    : "MCP 已启用但当前没有已连接服务器：仅使用内置工具";
        }
        return "MCP 已接入 " + mcp.toolCount() + " 个外部工具"
                + (amapMcpToolCount > 0 ? "（其中高德地图 " + amapMcpToolCount + " 个）" : "");
    }

    /**
     * 天气提示文案：必须与 weatherProvider 口径一致。
     * 模拟天气只在测试/离线演示开关下出现，且必须明确写出来；不可用时给出最近一次失败原因。
     */
    private static String weatherHint(String weatherProvider, String weatherLastError) {
        if ("open-meteo".equals(weatherProvider)) {
            return "真实天气：Open-Meteo（免费）";
        }
        if ("simulated".equals(weatherProvider)) {
            return "模拟天气（仅测试/演示开关开启）";
        }
        if (weatherLastError != null && !weatherLastError.isBlank()) {
            return "未获取到真实天气：" + weatherLastError;
        }
        return "未获取到真实天气：尚未成功连接 Open-Meteo";
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
