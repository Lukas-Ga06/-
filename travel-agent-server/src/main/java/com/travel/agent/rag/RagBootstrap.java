package com.travel.agent.rag;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.travel.agent.entity.RagDocument;
import com.travel.agent.repository.RagDocumentRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 启动时把 rag/knowledge.json 同步进知识库：切块 → Embedding → 向量入库（版本化写入）。
 *
 * <p>同步按指纹（sha256 of title+city+content）增量进行：
 * <ul>
 *   <li>新标题 → 新建文档 + v1（ACTIVE）+ 该版本的知识块；</li>
 *   <li>指纹相同 → 跳过；</li>
 *   <li>指纹不同（内容被修改，或存量库无指纹）→ 旧 ACTIVE 置 INACTIVE、
 *       追加 version_no = 上一版 + 1（ACTIVE）、新块挂到新版本；
 *       <b>旧版本正文与旧知识块保留不删</b>（batch9 / C：可审计、可回滚）；</li>
 *   <li>已从 knowledge.json 移除的文档保留不删（可能含用户手工添加的知识），仅日志提示；</li>
 *   <li>启动前先做一次存量回填（{@link RagVersionService#backfillLegacyVersions()}）：
 *       没有版本行的旧文档补 v1，历史知识块（version_id 为空）挂到该版本上。</li>
 * </ul>
 *
 * <p>batch10 / C 变更：文档行不再「先查 title+city 再 insert」，统一走
 * {@link RagDocumentUpsertService#upsert}——按业务键 {@code doc_key} 查找/插入，
 * 并发首建撞唯一索引时回读复用，不会出现两条同标题同城市的文档。
 *
 * <p>注意：向量空间一致性——切换 Embedding 模型后指纹无法感知（内容没变），
 * 需手工清空 rag_document / rag_chunk / rag_document_version 重建（见 README）。
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class RagBootstrap implements ApplicationRunner {

    /** 单篇同步结果：动作 + 文档 + 本次写入/挂接的知识块数 */
    public record SyncOutcome(DocumentAction action, RagDocument document, int chunks) {}

    public enum DocumentAction {
        CREATED,
        UPDATED,
        SKIPPED
    }

    private final RagDocumentRepository documentRepository;
    private final RagVersionService versionService;
    private final RagDocumentUpsertService upsertService;
    private final ObjectMapper objectMapper;

    @Override
    public void run(ApplicationArguments args) throws Exception {
        // 0) 存量/历史数据回填（幂等）：旧文档补 v1、历史块挂版本、版本归属补齐
        versionService.backfillLegacyVersions();

        List<Map<String, String>> docs = objectMapper.readValue(
                new ClassPathResource("rag/knowledge.json").getInputStream(),
                new TypeReference<>() {});
        // 只为"已从知识源移除"的日志计数保留：同步本身按 doc_key 查，不依赖这份快照
        Map<String, RagDocument> existing = documentRepository.findAll().stream()
                .collect(Collectors.toMap(d -> d.getTitle() + "|" + d.getCity(),
                        Function.identity(), (a, b) -> a));

        int created = 0;
        int updated = 0;
        int skipped = 0;
        int chunkCount = 0;
        for (Map<String, String> doc : docs) {
            SyncOutcome outcome = syncDocument(doc.get("title"), doc.get("city"), doc.get("category"),
                    doc.get("content"), doc.getOrDefault("source", "内置知识库"));
            chunkCount += outcome.chunks();
            switch (outcome.action()) {
                case CREATED -> created++;
                case UPDATED -> updated++;
                default -> skipped++;
            }
        }
        long removed = existing.size() - docs.size();
        if (removed > 0) {
            log.info("知识源中有 {} 篇文档已不在 knowledge.json，保留未删（如需清理请手工删除对应 rag_document）", removed);
        }
        log.info("RAG 知识库同步完成：新增 {} 篇 / 更新 {} 篇（均生成新版本，旧版本与旧块保留）/ 跳过 {} 篇 / 本次写入 {} 个知识块",
                created, updated, skipped, chunkCount);
    }

    /**
     * 同步一篇公共知识（{@link #run} 与测试共用的单篇入口）。
     *
     * <p>不做事务：事务在 {@link RagDocumentUpsertService} 内（锁必须在事务提交后才释放）。
     *
     * @return 动作（新增/更新/跳过）+ 文档 + 本次写入的知识块数
     */
    public SyncOutcome syncDocument(String title, String city, String category, String content, String source) {
        RagDocumentUpsertService.Outcome outcome = upsertService.upsert(null, title, city, category, content, source);
        DocumentAction action = switch (outcome.action()) {
            case CREATED -> DocumentAction.CREATED;
            case UPDATED -> DocumentAction.UPDATED;
            case SKIPPED -> DocumentAction.SKIPPED;
        };
        return new SyncOutcome(action, outcome.document(), outcome.chunks());
    }

    /** sha256(title \n city \n content) 的 hex（64 字符）；版本指纹与文档指纹同算法 */
    public static String fingerprint(String title, String city, String content) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(
                    (title + "\n" + city + "\n" + content).getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte b : hash) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }
}