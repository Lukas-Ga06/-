package com.travel.agent.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.travel.agent.util.ErrorSummary;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 启动后的 LLM 模型可用性探测（P0-A）。
 *
 * <p>背景：默认模型 {@code deepseek-chat} 曾不在真实 Key 的模型清单里，配置看起来没问题、
 * 实际每次调用都失败。这里在应用就绪后异步 GET {@code {baseUrl}/models}（超时 ≤3s），
 * 检查配置的模型是否存在：
 * <ul>
 *   <li>存在 → 状态页 llmModelAvailable=true；</li>
 *   <li>不存在 → WARN 并记 false + 可读原因（列出现有模型），提示换模型要用 /models 核对；</li>
 *   <li>请求失败 → llmModelAvailable=null（未校验）+ 原因，不阻塞启动、不刷 ERROR。</li>
 * </ul>
 *
 * <p>探测跑在 daemon 线程上：既不阻塞 Spring 启动，也不会阻止测试/进程退出。
 * 绝不输出 API Key（日志只带 host/模型名，请求头不会进日志）。
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class LlmModelProbe implements ApplicationListener<ApplicationReadyEvent> {

    /** 探测请求的超时（毫秒）：网络慢时最多等 3s，不能影响正常规划链路 */
    static final int PROBE_TIMEOUT_MS = 3000;
    /** 缺失时最多列出的可用模型数，避免状态页/日志被超长清单撑爆 */
    private static final int MAX_LISTED_MODELS = 20;

    private final LlmProperties properties;

    private final AtomicReference<ModelCheck> check = new AtomicReference<>(ModelCheck.UNCHECKED);

    /**
     * 模型探测结果。
     *
     * @param available 模型是否可用；null = 尚未校验成功（未开启探测 / 请求失败 / LLM 未配置）
     * @param error     不可用或未校验的原因；可用时为 null
     * @param pending   探测是否仍在进行
     */
    public record ModelCheck(Boolean available, String error, boolean pending) {
        static final ModelCheck UNCHECKED = new ModelCheck(null, null, false);
        static final ModelCheck PENDING = new ModelCheck(null, null, true);
    }

    @Override
    public void onApplicationEvent(ApplicationReadyEvent event) {
        maybeProbe();
    }

    /** 启动探测入口（包可见：单测可直接驱动，不依赖 ApplicationReadyEvent 的构造细节） */
    void maybeProbe() {
        if (!properties.isModelProbeEnabled()) {
            log.info("LLM 模型探测已关闭 (travel.llm.model-probe-enabled=false)，状态页保持未校验");
            return;
        }
        if (!properties.usable()) {
            // 未配置 Key 时模型本就不会被调用：保持「未校验」，不产生无意义的网络请求
            return;
        }
        Thread thread = new Thread(this::probe, "llm-model-probe");
        thread.setDaemon(true);
        thread.start();
    }

    /** 异步探测体：构建带鉴权与短超时的客户端，结果只写状态，异常不抛出 */
    void probe() {
        check.set(ModelCheck.PENDING);
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(PROBE_TIMEOUT_MS);
        factory.setReadTimeout(PROBE_TIMEOUT_MS);
        RestClient client = RestClient.builder()
                .requestFactory(factory)
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + properties.getApiKey())
                .build();
        probeWith(client);
    }

    /**
     * 探测逻辑（包可见，单测可注入 MockRestServiceServer 构建的客户端，全程不外呼）。
     *
     * @param client 指向 provider 的 RestClient（base 为空，需要绝对 URL）
     */
    void probeWith(RestClient client) {
        String configured = properties.getModel() == null ? "" : properties.getModel().trim();
        if (configured.isEmpty()) {
            // 空模型名多半是部署环境把 LLM_MODEL 显式设成了空串（占位符不会回退默认值）
            String reason = "未配置模型（travel.llm.model 为空）：请设置 LLM_MODEL";
            check.set(new ModelCheck(Boolean.FALSE, reason, false));
            log.warn("LLM 模型不可用：{}（provider={}）", reason, displayBaseUrl());
            return;
        }
        String modelsUrl = modelsUrl();
        try {
            JsonNode response = client.get().uri(modelsUrl).retrieve().body(JsonNode.class);
            List<String> models = readModels(response);
            if (models.contains(configured)) {
                check.set(new ModelCheck(Boolean.TRUE, null, false));
                log.info("LLM 模型探测通过：{} 在模型清单中（provider={}）", configured,
                        properties.providerHost());
                return;
            }
            String reason = "配置的模型「" + configured + "」不在模型清单中；可用模型："
                    + summarizeModels(models);
            check.set(new ModelCheck(Boolean.FALSE, reason, false));
            log.warn("LLM 模型不可用：{}（换 provider/模型必须用 /models 核对，provider={}；缺失会走降级路径）",
                    reason, displayBaseUrl());
        } catch (RuntimeException e) {
            String reason = "无法读取模型清单（" + ErrorSummary.describe(e) + "）";
            check.set(new ModelCheck(null, reason, false));
            // 不刷 ERROR、不打堆栈：模型清单接口失败是外部依赖的常见瞬时状态，启动照常继续
            log.warn("LLM 模型探测失败，状态页标记为未校验：{}", reason);
        }
    }

    /** 当前探测状态（状态接口每次读取最新快照） */
    public ModelCheck status() {
        return check.get();
    }

    private String modelsUrl() {
        String base = properties.getBaseUrl() == null ? "" : properties.getBaseUrl().trim();
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        return base + "/models";
    }

    /** provider 的 host（不含协议/路径/查询串/Key，日志用） */
    private String displayBaseUrl() {
        String host = properties.providerHost();
        return host == null ? "（provider 未配置）" : host;
    }

    private List<String> readModels(JsonNode response) {
        List<String> models = new ArrayList<>();
        JsonNode data = response == null ? null : response.path("data");
        if (data == null || !data.isArray()) {
            return models;
        }
        for (JsonNode item : data) {
            String id = item.path("id").asText("").trim();
            if (!id.isEmpty()) {
                models.add(id);
            }
        }
        return models;
    }

    /** 列出可用模型（最多 20 个，超出显示总数），给运维直接看到「应该换成哪个」 */
    private static String summarizeModels(List<String> models) {
        if (models.isEmpty()) {
            return "（空清单）";
        }
        String listed = String.join("、", models.subList(0, Math.min(models.size(), MAX_LISTED_MODELS)));
        return models.size() > MAX_LISTED_MODELS ? listed + " 等 " + models.size() + " 个" : listed;
    }
}
