package com.travel.agent.rag;

import com.travel.agent.entity.RagChunk;
import com.travel.agent.entity.RagDocument;
import com.travel.agent.repository.RagChunkRepository;
import com.travel.agent.repository.RagDocumentRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;
import java.util.stream.Collectors;

/** 内存向量库：全量加载 chunk 向量，余弦相似度 Top-K 检索 */
@Component
@RequiredArgsConstructor
public class InMemoryVectorStore implements VectorStore {

    private final RagChunkRepository chunkRepository;
    private final RagDocumentRepository documentRepository;
    private final EmbeddingCodec codec;

    private record Entry(RagChunk chunk, RagDocument document, float[] vector) {}

    private final List<Entry> entries = new CopyOnWriteArrayList<>();

    @EventListener(ApplicationReadyEvent.class)
    @Override
    public void reload() {
        Map<Long, RagDocument> docs = documentRepository.findAll().stream()
                .collect(Collectors.toMap(RagDocument::getId, Function.identity()));
        List<Entry> loaded = new ArrayList<>();
        for (RagChunk chunk : chunkRepository.findAll()) {
            RagDocument doc = docs.get(chunk.getDocumentId());
            if (doc == null || chunk.getEmbedding() == null) {
                continue;
            }
            loaded.add(new Entry(chunk, doc, codec.parse(chunk.getEmbedding())));
        }
        entries.clear();
        entries.addAll(loaded);
    }

    @Override
    public List<VectorSearchResult> search(float[] queryVector, String city, int topK) {
        List<VectorSearchResult> results = new ArrayList<>();
        for (Entry entry : entries) {
            if (city != null && !city.isBlank() && !city.equals(entry.document().getCity())) {
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
