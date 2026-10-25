package com.travel.agent.rag;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.travel.agent.entity.RagChunk;
import com.travel.agent.entity.RagDocument;
import com.travel.agent.repository.RagChunkRepository;
import com.travel.agent.repository.RagDocumentRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 启动时把 rag/knowledge.json 同步进知识库：切块 → Embedding → 向量入库。
 *
 * 同步按指纹（sha256 of title+city+content）增量进行：
 * - 新标题 → 新建文档并切块；
 * - 指纹相同 → 跳过（老行为"有文档就跳过"的精确版）；
 * - 指纹不同（内容被修改，或存量库无指纹）→ 更新文档并重建该篇知识块；
 * - 已从 knowledge.json 移除的文档保留不删（可能含用户手工添加的知识），仅日志提示。
 *
 * 注意：向量空间一致性——切换 Embedding 模型后指纹无法感知（内容没变），
 * 需手工清空 rag_document / rag_chunk 重建（见 README）。
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class RagBootstrap implements ApplicationRunner {

    private final RagDocumentRepository documentRepository;
    private final RagChunkRepository chunkRepository;
    private final EmbeddingService embeddingService;
    private final EmbeddingCodec codec;
    private final ObjectMapper objectMapper;

    @Override
    @Transactional
    public void run(ApplicationArguments args) throws Exception {
        List<Map<String, String>> docs = objectMapper.readValue(
                new ClassPathResource("rag/knowledge.json").getInputStream(),
                new TypeReference<>() {});
        Map<String, RagDocument> existing = documentRepository.findAll().stream()
                .collect(Collectors.toMap(d -> d.getTitle() + "|" + d.getCity(),
                        Function.identity(), (a, b) -> a));

        int created = 0;
        int updated = 0;
        int skipped = 0;
        int chunkCount = 0;
        for (Map<String, String> doc : docs) {
            String title = doc.get("title");
            String city = doc.get("city");
            String content = doc.get("content");
            String fingerprint = fingerprint(title, city, content);
            RagDocument current = existing.get(title + "|" + city);
            if (current != null && fingerprint.equals(current.getFingerprint())) {
                skipped++;
                continue;
            }
            RagDocument saved;
            if (current == null) {
                saved = documentRepository.save(RagDocument.builder()
                        .title(title)
                        .city(city)
                        .category(doc.get("category"))
                        .content(content)
                        .source(doc.getOrDefault("source", "内置知识库"))
                        .fingerprint(fingerprint)
                        .build());
                created++;
            } else {
                chunkRepository.deleteByDocumentId(current.getId());
                current.setCategory(doc.get("category"));
                current.setContent(content);
                current.setSource(doc.getOrDefault("source", "内置知识库"));
                current.setFingerprint(fingerprint);
                current.setUpdatedAt(LocalDateTime.now());
                saved = documentRepository.save(current);
                updated++;
            }
            for (String paragraph : content.split("\n\n")) {
                if (paragraph.isBlank()) {
                    continue;
                }
                float[] vector = embeddingService.embed(paragraph);
                chunkRepository.save(RagChunk.builder()
                        .documentId(saved.getId())
                        .content(paragraph.trim())
                        .embedding(codec.serialize(vector))
                        .dim(embeddingService.dimension())
                        .build());
                chunkCount++;
            }
        }
        long removed = existing.size() - docs.size();
        if (removed > 0) {
            log.info("知识源中有 {} 篇文档已不在 knowledge.json，保留未删（如需清理请手工删除对应 rag_document）", removed);
        }
        log.info("RAG 知识库同步完成：新增 {} 篇 / 更新 {} 篇 / 跳过 {} 篇 / 本次写入 {} 个知识块",
                created, updated, skipped, chunkCount);
    }

    /** sha256(title \n city \n content) 的 hex（64 字符） */
    static String fingerprint(String title, String city, String content) {
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
