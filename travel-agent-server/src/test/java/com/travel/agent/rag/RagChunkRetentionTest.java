package com.travel.agent.rag;

import com.travel.agent.entity.RagChunk;
import com.travel.agent.entity.RagDocument;
import com.travel.agent.entity.RagDocumentVersion;
import com.travel.agent.repository.RagChunkRepository;
import com.travel.agent.repository.RagDocumentRepository;
import com.travel.agent.repository.RagDocumentVersionRepository;
import com.travel.agent.support.IntegrationTestSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * 历史版本知识块清理（batch10 / C 契约 §3.2）。
 *
 * <p>语义：除 ACTIVE 版本外，额外保留最近 {@code travel.rag.chunk-version-retention}（默认 2）
 * 个历史版本的块；更老的 INACTIVE 版本只删块、<b>不删版本行与正文快照</b>（回滚时按正文重建），
 * {@code version_id IS NULL} 的历史块永不删除。
 *
 * <p>断言只看本用例自己造的文档：清理是全局的，共享内存库里还有其它用例留下的文档，
 * 因此对返回值只做下界断言。
 */
class RagChunkRetentionTest extends IntegrationTestSupport {

    @Autowired
    private RagBootstrap ragBootstrap;

    @Autowired
    private RagVersionService ragVersionService;

    @Autowired
    private RagChunkRetentionJob retentionJob;

    @Autowired
    private RagDocumentRepository documentRepository;

    @Autowired
    private RagDocumentVersionRepository versionRepository;

    @Autowired
    private RagChunkRepository chunkRepository;

    @Autowired
    private EmbeddingService embeddingService;

    @Autowired
    private EmbeddingCodec embeddingCodec;

    @BeforeEach
    void seedPublicKnowledge() throws Exception {
        if (documentRepository.count() == 0) {
            ragBootstrap.run(null);
        }
    }

    private static String uniqueMarker() {
        return "RT-" + UUID.randomUUID().toString().substring(0, 8);
    }

    /** 造一个有 {@code versions} 个版本的文档（v1..vN，每版 1 个知识块，vN 为 ACTIVE） */
    private RagDocument documentWithVersions(String title, int versions) {
        RagDocument doc = null;
        for (int i = 1; i <= versions; i++) {
            doc = ragBootstrap.syncDocument(title, "通用", "test", "第" + i + "版正文。" + title, "测试").document();
        }
        assertThat(doc).isNotNull();
        return doc;
    }

    /** 版本行：v4, v3, v2, v1（倒序） */
    private List<RagDocumentVersion> versionsOf(RagDocument doc) {
        return versionRepository.findByDocumentIdOrderByVersionNoDesc(doc.getId());
    }

    /** 版本化之前的历史块（version_id 为空）：清理永不触碰 */
    private RagChunk legacyChunk(RagDocument doc) {
        String text = "历史块-" + doc.getTitle();
        return chunkRepository.save(RagChunk.builder()
                .documentId(doc.getId())
                .content(text)
                .embedding(embeddingCodec.serialize(embeddingService.embed(text)))
                .dim(embeddingService.dimension())
                .build());
    }

    @Test
    @DisplayName("默认保留窗口=2：ACTIVE 与最近 2 个历史版本块保留，最老版本块被删，version_id 为空的历史块不受影响")
    void purgeKeepsActiveAndRecentHistoryByDefault() {
        RagDocument doc = documentWithVersions("保留窗口-" + uniqueMarker(), 4);
        RagChunk legacy = legacyChunk(doc);

        List<RagDocumentVersion> versions = versionsOf(doc);
        assertThat(versions).hasSize(4);
        RagDocumentVersion v4 = versions.get(0);
        RagDocumentVersion v3 = versions.get(1);
        RagDocumentVersion v2 = versions.get(2);
        RagDocumentVersion v1 = versions.get(3);
        assertThat(v4.isActive()).isTrue();
        assertThat(v1.isActive()).isFalse();
        assertThat(v1.getContent()).contains("第1版正文");

        // 无参入口 = 按配置键 travel.rag.chunk-version-retention（未配置时默认 2）
        RagVersionService.PurgeResult result = ragVersionService.purgeOldVersionChunks();
        assertThat(result.versions()).as("本用例的 v1 必须被清理").isGreaterThanOrEqualTo(1);
        assertThat(result.chunks()).as("v1 的块必须被删除").isGreaterThanOrEqualTo(1);

        // ACTIVE 版本的块一定在
        assertThat(chunkRepository.countByDocumentIdAndVersionId(doc.getId(), v4.getId())).isEqualTo(1);
        // 窗口内的两个历史版本（v3、v2）的块也在
        assertThat(chunkRepository.countByDocumentIdAndVersionId(doc.getId(), v3.getId())).isEqualTo(1);
        assertThat(chunkRepository.countByDocumentIdAndVersionId(doc.getId(), v2.getId())).isEqualTo(1);
        // 窗口外的最老版本（v1）只删块
        assertThat(chunkRepository.countByDocumentIdAndVersionId(doc.getId(), v1.getId())).isZero();
        // version_id 为空的历史块不参与清理
        assertThat(chunkRepository.findById(legacy.getId())).isPresent();
        // 版本行与正文快照必须保留：回滚靠它重建块（RagVersionService#rollback）
        assertThat(versionRepository.findById(v1.getId())).isPresent();
        assertThat(versionRepository.findById(v1.getId()).orElseThrow().getContent()).contains("第1版正文");
        // 文档本身不受影响
        assertThat(documentRepository.findById(doc.getId())).isPresent();
    }

    @Test
    @DisplayName("保留窗口=0：只保留 ACTIVE 版本的块，其余历史块全部清理（ACTIVE 永不删）")
    void purgeWithZeroRetentionKeepsOnlyActiveChunks() {
        RagDocument doc = documentWithVersions("零窗口-" + uniqueMarker(), 3);
        RagChunk legacy = legacyChunk(doc);

        List<RagDocumentVersion> versions = versionsOf(doc);
        RagDocumentVersion active = versions.get(0);
        RagDocumentVersion v2 = versions.get(1);
        RagDocumentVersion v1 = versions.get(2);
        assertThat(active.getVersionNo()).isEqualTo(3);

        RagVersionService.PurgeResult result = ragVersionService.purgeOldVersionChunks(0);
        assertThat(result.versions()).isGreaterThanOrEqualTo(2);
        assertThat(result.chunks()).isGreaterThanOrEqualTo(2);

        assertThat(chunkRepository.countByDocumentIdAndVersionId(doc.getId(), active.getId()))
                .as("ACTIVE 版本的块永不删除")
                .isEqualTo(1);
        assertThat(chunkRepository.countByDocumentIdAndVersionId(doc.getId(), v2.getId())).isZero();
        assertThat(chunkRepository.countByDocumentIdAndVersionId(doc.getId(), v1.getId())).isZero();
        assertThat(chunkRepository.findById(legacy.getId())).as("version_id 为空的历史块永不删除").isPresent();
        // 版本行仍在（正文快照保留，回滚可用）
        assertThat(versionRepository.findByDocumentIdOrderByVersionNoDesc(doc.getId())).hasSize(3);
    }

    @Test
    @DisplayName("回滚到块已被清理的历史版本：按版本正文重建块并重新可检索")
    void rollingBackToPurgedVersionRebuildsChunks() {
        RagDocument doc = documentWithVersions("回滚重建-" + uniqueMarker(), 3);
        RagDocumentVersion v1 = versionsOf(doc).get(2);

        // 窗口 0 → v1/v2 的块被删（版本行与正文保留）
        ragVersionService.purgeOldVersionChunks(0);
        assertThat(chunkRepository.countByDocumentIdAndVersionId(doc.getId(), v1.getId())).isZero();

        ragVersionService.rollback(doc.getId(), v1.getVersionNo());
        assertThat(chunkRepository.countByDocumentIdAndVersionId(doc.getId(), v1.getId()))
                .as("回滚时块缺失应按版本正文重建")
                .isEqualTo(1);
        assertThat(versionRepository.findByDocumentIdAndStatus(doc.getId(), RagDocumentVersion.Status.ACTIVE))
                .hasSize(1);
    }

    @Test
    @DisplayName("每日调度入口（@Scheduled 包装）可正常执行且不抛异常")
    void scheduledJobRunsWithoutFailing() {
        documentWithVersions("调度入口-" + uniqueMarker(), 3);
        assertThatCode(() -> retentionJob.purgeDaily()).doesNotThrowAnyException();
    }
}