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

import java.util.List;
import java.util.Map;

/** 启动时加载旅游知识库文档 → 切块 → Embedding → 向量库 */
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
    public void run(ApplicationArguments args) throws Exception {
        if (documentRepository.count() > 0) {
            log.info("RAG 知识库已存在（{} 篇文档），跳过初始化", documentRepository.count());
            return;
        }
        List<Map<String, String>> docs = objectMapper.readValue(
                new ClassPathResource("rag/knowledge.json").getInputStream(),
                new TypeReference<>() {});
        int chunkCount = 0;
        for (Map<String, String> doc : docs) {
            RagDocument saved = documentRepository.save(RagDocument.builder()
                    .title(doc.get("title"))
                    .city(doc.get("city"))
                    .category(doc.get("category"))
                    .content(doc.get("content"))
                    .source(doc.getOrDefault("source", "内置知识库"))
                    .build());
            for (String paragraph : doc.get("content").split("\n\n")) {
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
        log.info("RAG 知识库初始化完成：{} 篇文档 / {} 个知识块", docs.size(), chunkCount);
    }
}
