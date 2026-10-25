package com.travel.agent.rag;

import com.travel.agent.entity.RagDocument;
import com.travel.agent.repository.RagChunkRepository;
import com.travel.agent.repository.RagDocumentRepository;
import com.travel.agent.support.IntegrationTestSupport;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.ApplicationArguments;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 知识库指纹同步：首建 → 相同跳过 → 指纹过期（内容被改）按篇重建知识块
 */
class RagBootstrapFingerprintTest extends IntegrationTestSupport {

    @Autowired
    private RagBootstrap bootstrap;

    @Autowired
    private RagDocumentRepository documentRepository;

    @Autowired
    private RagChunkRepository chunkRepository;

    @Test
    void syncsByFingerprint() throws Exception {
        bootstrap.run((ApplicationArguments) null);
        long docsAfterFirst = documentRepository.count();
        long chunksAfterFirst = chunkRepository.count();
        assertTrue(docsAfterFirst > 0, "knowledge.json 应至少入库一篇");
        RagDocument first = documentRepository.findAll().get(0);
        assertNotNull(first.getFingerprint());

        // 第二次启动：指纹全部一致 → 零写入
        bootstrap.run((ApplicationArguments) null);
        assertEquals(docsAfterFirst, documentRepository.count());
        assertEquals(chunksAfterFirst, chunkRepository.count());

        // 模拟"知识源更新"：把库里指纹改成过期值 → 该篇被重建（先删旧块再写新块）
        first.setFingerprint("stale");
        documentRepository.save(first);
        bootstrap.run((ApplicationArguments) null);
        RagDocument refreshed = documentRepository.findById(first.getId()).orElseThrow();
        assertNotEquals("stale", refreshed.getFingerprint(), "指纹应恢复为当前内容指纹");
        var chunks = chunkRepository.findByDocumentId(first.getId());
        assertFalse(chunks.isEmpty(), "过期文档的知识块应被重建");
        assertEquals(chunksAfterFirst, chunkRepository.count(), "重建后总量不变（先删后插）");
    }
}
