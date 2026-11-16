package com.travel.agent.rag;

import com.travel.agent.entity.RagChunk;
import com.travel.agent.entity.RagDocument;
import com.travel.agent.entity.RagDocumentVersion;
import com.travel.agent.repository.RagChunkRepository;
import com.travel.agent.repository.RagDocumentRepository;
import com.travel.agent.repository.RagDocumentVersionRepository;
import com.travel.agent.support.IntegrationTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.ApplicationArguments;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 知识库指纹同步：首建 → 相同跳过 → 指纹过期（内容被改）时<b>追加版本</b>。
 *
 * <p>语义变更（batch9 / C 知识库版本化）：本用例原来断言"指纹变化会先删旧块再插新块、
 * 知识块总量不变"。版本化之后指纹变化不再删除任何数据——旧 ACTIVE 版本置 INACTIVE、
 * 追加 version_no+1 的新版本（ACTIVE），旧版本正文与旧知识块全部保留，用于审计与回滚。
 * 因此断言改为：版本数量 +1、只有最新版本 ACTIVE、旧块 id 全部仍在库里、总量只增不减。
 */
class RagBootstrapFingerprintTest extends IntegrationTestSupport {

    @Autowired
    private RagBootstrap bootstrap;

    @Autowired
    private RagDocumentRepository documentRepository;

    @Autowired
    private RagChunkRepository chunkRepository;

    @Autowired
    private RagDocumentVersionRepository versionRepository;

    @Test
    @DisplayName("指纹变化只追加新版本，不删除旧版本与旧知识块")
    void syncsByFingerprintAppendingVersions() throws Exception {
        bootstrap.run((ApplicationArguments) null);
        long docsAfterFirst = documentRepository.count();
        long chunksAfterFirst = chunkRepository.count();
        long versionsAfterFirst = versionRepository.count();
        assertTrue(docsAfterFirst > 0, "knowledge.json 应至少入库一篇");
        RagDocument first = documentRepository.findAll().get(0);
        assertNotNull(first.getFingerprint());
        List<RagDocumentVersion> versionsBefore = versionRepository
                .findByDocumentIdOrderByVersionNoDesc(first.getId());
        assertFalse(versionsBefore.isEmpty(), "RagBootstrap 首建文档必须同步建出 v1 版本");
        RagDocumentVersion latestBefore = versionsBefore.get(0);
        assertEquals(RagDocumentVersion.Status.ACTIVE, latestBefore.getStatus());
        Set<Long> chunkIdsBefore = chunkRepository.findByDocumentId(first.getId()).stream()
                .map(RagChunk::getId).collect(Collectors.toSet());
        assertFalse(chunkIdsBefore.isEmpty(), "文档应有知识块");

        // 第二次启动：指纹全部一致 → 零写入（文档/知识块/版本都不变）
        bootstrap.run((ApplicationArguments) null);
        assertEquals(docsAfterFirst, documentRepository.count());
        assertEquals(chunksAfterFirst, chunkRepository.count());
        assertEquals(versionsAfterFirst, versionRepository.count());

        // 模拟"知识源更新"：把库里指纹改成过期值 → 追加新版本（旧版本与旧块保留）
        first.setFingerprint("stale");
        documentRepository.save(first);
        bootstrap.run((ApplicationArguments) null);
        RagDocument refreshed = documentRepository.findById(first.getId()).orElseThrow();
        assertNotEquals("stale", refreshed.getFingerprint(), "指纹应恢复为当前内容指纹");

        List<RagDocumentVersion> versionsAfter = versionRepository
                .findByDocumentIdOrderByVersionNoDesc(first.getId());
        assertEquals(versionsBefore.size() + 1, versionsAfter.size(), "指纹变化应追加恰好一个新版本");
        RagDocumentVersion newest = versionsAfter.get(0);
        assertEquals(latestBefore.getVersionNo() + 1, newest.getVersionNo(), "版本号应在上一版基础上 +1");
        assertEquals(RagDocumentVersion.Status.ACTIVE, newest.getStatus());
        // 不变量 1：同文档任意时刻至多一条 ACTIVE
        assertEquals(1, versionRepository
                .findByDocumentIdAndStatus(first.getId(), RagDocumentVersion.Status.ACTIVE).size());
        // 旧版本被置为 INACTIVE，但仍在库里（重新查库，避免读到内存里的旧状态）
        assertEquals(RagDocumentVersion.Status.INACTIVE,
                versionRepository.findById(latestBefore.getId()).orElseThrow().getStatus());
        assertTrue(chunkRepository.countByDocumentIdAndVersionId(first.getId(), newest.getId()) > 0,
                "新版本应写入自己的知识块");
        assertTrue(chunkRepository.countByDocumentIdAndVersionId(first.getId(), latestBefore.getId()) > 0,
                "旧版本的知识块必须保留（版本化后不再删旧块）");
        // 旧块 id 全部仍在库里，且总量只增不减
        Set<Long> chunkIdsAfter = chunkRepository.findByDocumentId(first.getId()).stream()
                .map(RagChunk::getId).collect(Collectors.toSet());
        assertTrue(chunkIdsAfter.containsAll(chunkIdsBefore), "旧知识块不得被删除");
        assertTrue(chunkIdsAfter.size() > chunkIdsBefore.size(), "内容变更应新增知识块");
        assertTrue(chunkRepository.count() > chunksAfterFirst, "知识块总量只增不减");
    }
}