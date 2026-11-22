package com.travel.agent.rag;

import com.travel.agent.llm.LlmClient;
import com.travel.agent.llm.LlmMessage;
import com.travel.agent.observability.TravelMetrics;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

/**
 * RAG 检索与问答。架构原则：
 * 静态知识（攻略/介绍/注意事项）→ RAG；实时信息（天气/价格/排队）→ Tools。
 *
 * <p>版本化（batch9 / C）：检索只命中「version_id 为空的历史块」或「所属版本为 ACTIVE 的块」，
 * 内容更新/回滚后由 {@link #reload()} 重建内存索引，切换即时生效。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class RagService {

    private final EmbeddingService embeddingService;
    private final VectorStore vectorStore;
    private final LlmClient llmClient;
    private final RagVersionService versionService;

    /**
     * 低相似度阈值：低于它的命中不进入问答上下文（避免「没有资料却硬答」）。
     * 只影响问答，不影响知识库搜索接口与 Planner 研究（它们需要看到完整候选与分数）。
     */
    @Value("${travel.rag.min-score:0.05}")
    private double minScore;

    /**
     * T5 检索命中指标（可选注入）：既有单测直接 {@code new RagService(...)} 四参构造，
     * 指标缺失（纯单测）时静默跳过，绝不能因为"没有指标"就让检索失败。
     */
    @Autowired(required = false)
    private TravelMetrics travelMetrics;

    public record RagAnswer(String message, List<String> sources, List<RagCitation> citations) {}

    /**
     * 重建内存向量索引（只装载 ACTIVE 版本与 version_id 为空的历史块）。
     * 版本切换（内容更新 / 回滚）后必须调用，否则内存里还是旧版本的块。
     */
    public void reload() {
        vectorStore.reload();
    }

    /**
     * 删除文档：版本与知识块连带删除并刷新索引，保持"删除后不可检索、不再出现在版本列表"的对外语义。
     * 目前没有对外的文档删除接口，该方法供 Agent/后台清理等调用方复用。
     */
    public void deleteDocument(Long documentId) {
        versionService.deleteDocument(documentId);
        vectorStore.reload();
    }

    /** 公共知识检索（无用户上下文）：内部公共检索/测试用 */
    public List<VectorSearchResult> search(String query, String city, int topK) {
        return search(query, city, null, topK);
    }

    /**
     * 带用户上下文的检索：公共知识始终可见，个人知识只对归属用户可见。
     *
     * @param currentUserId 当前用户 id；null = 只检索公共知识
     */
    public List<VectorSearchResult> search(String query, String city, Long currentUserId, int topK) {
        float[] vector = embeddingService.embed(query);
        return vectorStore.search(vector, city, currentUserId, topK);
    }

    /**
     * 带引用的检索（P1）：在向量命中之上补齐 documentId / version / chunkId / score / source。
     *
     * <p>Planner 研究链路、知识库搜索接口与问答都走这里，保证「方案里引用的知识」
     * 与「知识库页面上看到的来源」是同一份口径。
     */
    public List<RagCitation> searchWithCitations(String query, String city, Long currentUserId, int topK) {
        List<RagCitation> citations = search(query, city, currentUserId, topK).stream()
                .map(r -> new RagCitation(
                        r.getDocument().getId(),
                        versionService.versionNoOf(r.getChunk().getVersionId()),
                        r.getChunk().getId(),
                        r.getScore(),
                        r.getDocument().getSource(),
                        r.getDocument().getTitle(),
                        r.getChunk().getContent()))
                .toList();
        recordRetrieval(citations);
        return citations;
    }

    /** T5：命中/未命中计数。检索抛异常时不记——失败会由调用方日志与 5xx 体现，不该混进命中率 */
    private void recordRetrieval(List<RagCitation> citations) {
        if (travelMetrics == null) {
            return;
        }
        try {
            travelMetrics.ragRetrieval(citations != null && !citations.isEmpty());
        } catch (RuntimeException ignored) {
            // 指标故障不影响检索
        }
    }

    public RagAnswer answer(String question, String city) {
        return answer(question, city, null);
    }

    public RagAnswer answer(String question, String city, Long currentUserId) {
        List<RagCitation> hits = searchWithCitations(question, city, currentUserId, 3);
        // 相似度阈值（P1）：低相似度命中不能伪装成「知识库里有这条资料」；
        // 阈值可配（travel.rag.min-score），默认 0.05 只滤掉近乎无关的噪声。
        List<RagCitation> results = hits.stream()
                .filter(c -> c.score() >= minScore)
                .toList();
        if (results.isEmpty()) {
            return new RagAnswer("知识库中暂无可靠资料（相似度低于阈值），你可以换个问法，或直接告诉我你的旅行需求。",
                    List.of(), List.of());
        }
        String context = results.stream()
                .map(r -> "【" + r.title() + (r.version() == null ? "" : " v" + r.version()) + "】" + r.snippet())
                .collect(Collectors.joining("\n\n"));
        List<String> sources = results.stream()
                .map(RagCitation::label)
                .distinct()
                .toList();

        String llmReply = null;
        if (llmClient.available()) {
            llmReply = llmClient.chat(List.of(
                    LlmMessage.system("你是旅行助手，仅根据提供的知识库内容回答问题，回答简洁友好。若知识库内容不足以回答，请如实说明。"),
                    LlmMessage.user("知识库内容：\n" + context + "\n\n用户问题：" + question)
            ), 0.3);
        }
        if (llmReply != null) {
            return new RagAnswer(llmReply, sources, results);
        }
        // 规则降级：直接拼接最相关知识片段（带文档来源与版本，便于追溯）
        StringBuilder sb = new StringBuilder("根据旅游知识库：\n");
        for (RagCitation r : results) {
            String snippet = r.snippet();
            if (snippet.length() > 160) {
                snippet = snippet.substring(0, 160) + "……";
            }
            sb.append("· ").append(snippet).append("（来源：").append(r.label()).append("）\n");
        }
        return new RagAnswer(sb.toString().trim(), sources, results);
    }
}

