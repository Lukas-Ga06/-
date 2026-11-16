package com.travel.agent.rag;

import com.travel.agent.entity.RagChunk;
import com.travel.agent.entity.RagDocument;
import com.travel.agent.entity.RagDocumentVersion;
import com.travel.agent.repository.RagChunkRepository;
import com.travel.agent.repository.RagDocumentRepository;
import com.travel.agent.repository.RagDocumentVersionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 内存向量库：全量加载 chunk 向量，余弦相似度 Top-K 检索。
 *
 * <p>版本化（batch9 / C）后的装载规则——"只认 ACTIVE"：
 * <ul>
 *   <li>{@code chunk.version_id} 为空：版本化上线前的历史数据，按兼容规则<b>仍可检索</b>；</li>
 *   <li>{@code chunk.version_id} 非空：只有对应版本状态为 ACTIVE 时才装载，非 ACTIVE（历史版本）的块
 *       留在库里但不参与检索；</li>
 *   <li>{@code version_id} 指向的版本行不存在（脏数据）→ 视为不可检索，避免脏块混入。</li>
 * </ul>
 * 版本切换（内容更新 / 回滚）后由 {@link RagService#reload()} 重建，切换即时生效。
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class InMemoryVectorStore implements VectorStore {

    private final RagChunkRepository chunkRepository;
    private final RagDocumentRepository documentRepository;
    private final RagDocumentVersionRepository versionRepository;
    private final EmbeddingCodec codec;

    private record Entry(RagChunk chunk, RagDocument document, float[] vector) {}

    /** 与城市无关的通用文档：任何目的地的问答都应能检索到 */
    private static final java.util.Set<String> CITY_AGNOSTIC = java.util.Set.of("通用", "全部", "通用知识");

    private final List<Entry> entries = new CopyOnWriteArrayList<>();

    @EventListener(ApplicationReadyEvent.class)
    @Override
    public void reload() {
        Map<Long, RagDocument> docs = documentRepository.findAll().stream()
                .collect(Collectors.toMap(RagDocument::getId, Function.identity()));
        Set<Long> activeVersionIds = new HashSet<>(
                versionRepository.findIdsByStatus(RagDocumentVersion.Status.ACTIVE));
        List<Entry> loaded = new ArrayList<>();
        int skipped = 0;
        for (RagChunk chunk : chunkRepository.findAll()) {
            RagDocument doc = docs.get(chunk.getDocumentId());
            if (doc == null || chunk.getEmbedding() == null
                    || !isSearchable(chunk.getVersionId(), activeVersionIds)) {
                skipped++;
                continue;
            }
            loaded.add(new Entry(chunk, doc, codec.parse(chunk.getEmbedding())));
        }
        entries.clear();
        entries.addAll(loaded);
        log.debug("内存向量库重建：装载 {} 个知识块（跳过 {} 个：无文档/无向量/非 ACTIVE 版本）",
                loaded.size(), skipped);
    }

    /**
     * 版本化兼容规则（契约 §2）：
     * version_id 为空（历史块）→ 可检索；非空 → 只有 ACTIVE 版本可检索。
     */
    static boolean isSearchable(Long versionId, Set<Long> activeVersionIds) {
        return versionId == null || (activeVersionIds != null && activeVersionIds.contains(versionId));
    }

    @Override
    public List<VectorSearchResult> search(float[] queryVector, String city, Long currentUserId, int topK) {
        List<VectorSearchResult> results = new ArrayList<>();
        for (Entry entry : entries) {
            if (!matchesCity(entry.document().getCity(), city)) {
                continue;
            }
            if (!matchesUser(entry.document().getUserId(), currentUserId)) {
                continue;
            }
            double score = cosine(queryVector, entry.vector());
            results.add(new VectorSearchResult(entry.chunk(), entry.document(), score));
        }
        return results.stream()
                .sorted(Comparator.comparingDouble(VectorSearchResult::getScore).reversed())
                .limit(topK)
                .toList();
    }

    /**
     * 城市过滤：指定城市时，保留「该城市文档 + 通用文档（city 为空或标记为通用）」，
     * 而不是只保留城市完全相等的文档——否则「注意事项」这类通用知识在有行程时永远查不到，
     * 且目录外城市（无城市级文档）会得到空结果。
     */
    static boolean matchesCity(String docCity, String queryCity) {
        if (queryCity == null || queryCity.isBlank()) {
            return true;
        }
        if (docCity == null || docCity.isBlank()) {
            return true;
        }
        if (CITY_AGNOSTIC.contains(docCity.trim())) {
            return true;
        }
        return queryCity.trim().equals(docCity.trim());
    }

    /**
     * 用户过滤：公共知识（userId 为空）对所有人生效；个人知识只对归属用户生效。
     * currentUserId 为空（内部/公共检索）时只能看到公共知识。
     */
    static boolean matchesUser(Long docUserId, Long currentUserId) {
        if (docUserId == null) {
            return true;
        }
        return docUserId.equals(currentUserId);
    }

    private double cosine(float[] a, float[] b) {
        if (a == null || b == null || a.length != b.length) {
            return 0;
        }
        double dot = 0;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
        }
        return dot;
    }
}