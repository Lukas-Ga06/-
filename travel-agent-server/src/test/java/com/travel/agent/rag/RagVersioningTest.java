package com.travel.agent.rag;

import com.fasterxml.jackson.databind.JsonNode;
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
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 知识库版本化（batch9 / C）验收测试。
 *
 * <p>覆盖契约 §2 的不变量与 HTTP 接口：
 * <ul>
 *   <li>版本增长：同一文档内容变更两次 → 3 个版本，只有最新 ACTIVE，旧知识块仍在库里；</li>
 *   <li>检索隔离：只有 ACTIVE 版本的块进向量库（v2 命中、v1 不命中；回滚到 v1 后相反）；</li>
 *   <li>归属：他人个人知识一律 404（不暴露存在性）；公共知识可读版本但不允许回滚（400）；</li>
 *   <li>历史兼容：version_id 为空的历史块启动回填到 v1 后仍可检索；</li>
 *   <li>batch10 / C：两线程并发「置位 + 插入」后仍恰好 1 条 ACTIVE（DB 生成列+唯一索引只在 MySQL 生效，
 *       H2 靠文档行 {@code SELECT ... FOR UPDATE} 保证）。</li>
 * </ul>
 */
class RagVersioningTest extends IntegrationTestSupport {

    @Autowired
    private RagBootstrap ragBootstrap;

    @Autowired
    private RagVersionService ragVersionService;

    @Autowired
    private RagService ragService;

    @Autowired
    private EmbeddingService embeddingService;

    @Autowired
    private EmbeddingCodec embeddingCodec;

    @Autowired
    private RagDocumentRepository documentRepository;

    @Autowired
    private RagDocumentVersionRepository versionRepository;

    @Autowired
    private RagChunkRepository chunkRepository;

    @BeforeEach
    void seedPublicKnowledge() throws Exception {
        // 公共知识（内置 knowledge.json 种子）由 RagBootstrap 同步；其他 RAG 用例也依赖同样的前置
        if (documentRepository.count() == 0) {
            ragBootstrap.run(null);
        }
        ragService.reload();
    }

    private static String uniqueMarker() {
        return "MK-" + UUID.randomUUID().toString().substring(0, 8);
    }

    /** 走 RagBootstrap 的单篇同步入口（与启动同步同一条版本化写入路径） */
    private RagDocument sync(String title, String city, String content) {
        return ragBootstrap.syncDocument(title, city, "test", content, "测试").document();
    }

    private long addPersonalKnowledge(Account account, String title, String content) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/learning/knowledge/add")
                        .header("Authorization", account.bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("title", title, "content", content))))
                .andExpect(status().isOk())
                .andReturn();
        return body(result).path("data").path("documentId").asLong();
    }

    /** 检索命中的片段（topK 放大到 200：本用例只关心"该块是否进库"，不关心排序） */
    private List<String> snippets(String query, Long currentUserId) {
        return ragService.search(query, null, currentUserId, 200).stream()
                .map(result -> result.getChunk().getContent())
                .collect(Collectors.toList());
    }

    @Test
    @DisplayName("版本增长：内容变更两次 → 3 个版本，只有 v3 ACTIVE，旧块仍然在库里")
    void contentChangesAppendVersionsAndKeepOldChunks() {
        String marker = uniqueMarker();
        String title = "版本增长-" + marker;
        String v1 = "第一版出行提示。" + marker + "-v1";
        String v2 = "第二版出行提示。" + marker + "-v2";
        String v3 = "第三版出行提示。" + marker + "-v3";

        RagDocument doc = sync(title, "通用", v1);
        sync(title, "通用", v2);
        sync(title, "通用", v3);

        List<RagDocumentVersion> versions = versionRepository.findByDocumentIdOrderByVersionNoDesc(doc.getId());
        assertThat(versions).extracting(RagDocumentVersion::getVersionNo).containsExactly(3, 2, 1);

        List<RagDocumentVersion> active = versionRepository
                .findByDocumentIdAndStatus(doc.getId(), RagDocumentVersion.Status.ACTIVE);
        assertThat(active).hasSize(1);
        assertThat(active.get(0).getVersionNo()).isEqualTo(3);

        // 三个版本各自的知识块都在库里（旧版本不再被删除）
        assertThat(chunkRepository.countByDocumentId(doc.getId())).isEqualTo(3);
        for (RagDocumentVersion version : versions) {
            assertThat(chunkRepository.countByDocumentIdAndVersionId(doc.getId(), version.getId()))
                    .as("版本 v%s 应保留自己的知识块", version.getVersionNo())
                    .isEqualTo(1);
        }

        // 检索只认 ACTIVE：v3 命中，v1/v2 不再命中
        ragService.reload();
        assertThat(snippets(v3, null)).anyMatch(snippet -> snippet.contains(marker + "-v3"));
        assertThat(snippets(v1, null)).noneMatch(snippet -> snippet.contains(marker + "-v1"));
        assertThat(snippets(v2, null)).noneMatch(snippet -> snippet.contains(marker + "-v2"));
    }

    @Test
    @DisplayName("回滚：切回 v1 后向量库立即反向命中，版本列表/详情返回正确状态")
    void rollbackSwitchesActiveVersionAndRetrieval() throws Exception {
        Account owner = registerNew();
        String marker = uniqueMarker();
        String title = "回滚测试-" + marker;
        String v1 = "回滚前正文。" + marker + "-v1";
        String v2 = "回滚后正文。" + marker + "-v2";
        long docId = addPersonalKnowledge(owner, title, v1);

        // 个人知识没有"更新"接口：直接走版本化写入路径追加 v2（与启动同步同一入口）
        RagDocument doc = documentRepository.findById(docId).orElseThrow();
        ragVersionService.appendVersion(doc, v2, RagBootstrap.fingerprint(title, "个人", v2));
        ragService.reload();

        assertThat(snippets(v2, owner.id())).anyMatch(snippet -> snippet.contains(marker + "-v2"));
        assertThat(snippets(v1, owner.id())).noneMatch(snippet -> snippet.contains(marker + "-v1"));

        // 回滚到 v1：接口内部会重建向量索引，切换立即生效
        MvcResult rollback = mockMvc.perform(
                        post("/api/knowledge/documents/{id}/rollback/{versionNo}", docId, 1)
                                .header("Authorization", owner.bearer()))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(body(rollback).path("data").path("activeVersionNo").asInt()).isEqualTo(1);

        assertThat(snippets(v1, owner.id())).anyMatch(snippet -> snippet.contains(marker + "-v1"));
        assertThat(snippets(v2, owner.id())).noneMatch(snippet -> snippet.contains(marker + "-v2"));

        // 版本列表：按 versionNo 倒序，v2 INACTIVE / v1 ACTIVE
        MvcResult list = mockMvc.perform(get("/api/knowledge/documents/{id}/versions", docId)
                        .header("Authorization", owner.bearer()))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode rows = body(list).path("data");
        assertThat(rows).hasSize(2);
        assertThat(rows.get(0).path("versionNo").asInt()).isEqualTo(2);
        assertThat(rows.get(0).path("status").asText()).isEqualTo("INACTIVE");
        assertThat(rows.get(0).path("active").asBoolean()).isFalse();
        assertThat(rows.get(1).path("versionNo").asInt()).isEqualTo(1);
        assertThat(rows.get(1).path("status").asText()).isEqualTo("ACTIVE");
        assertThat(rows.get(1).path("active").asBoolean()).isTrue();
        assertThat(rows.get(1).path("fingerprint").asText()).isNotBlank();
        assertThat(rows.get(1).path("createdAt").isMissingNode()).isFalse();

        // 版本详情含正文快照
        MvcResult detail = mockMvc.perform(get("/api/knowledge/documents/{id}/versions/{versionNo}", docId, 1)
                        .header("Authorization", owner.bearer()))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(body(detail).path("data").path("content").asText()).contains(marker + "-v1");
    }

    @Test
    @DisplayName("归属：他人个人知识 404（不暴露存在性）；公共知识可读版本但不允许回滚")
    void ownershipGuardsVersionEndpoints() throws Exception {
        Account owner = registerNew();
        Account stranger = registerNew();
        String marker = uniqueMarker();
        long docId = addPersonalKnowledge(owner, "私密知识-" + marker, "私密正文。" + marker);

        // 他人个人知识：列表/详情/回滚一律 404
        mockMvc.perform(get("/api/knowledge/documents/{id}/versions", docId)
                        .header("Authorization", stranger.bearer()))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/knowledge/documents/{id}/versions/{versionNo}", docId, 1)
                        .header("Authorization", stranger.bearer()))
                .andExpect(status().isNotFound());
        mockMvc.perform(post("/api/knowledge/documents/{id}/rollback/{versionNo}", docId, 1)
                        .header("Authorization", stranger.bearer()))
                .andExpect(status().isNotFound());

        // 归属用户自己能读
        mockMvc.perform(get("/api/knowledge/documents/{id}/versions", docId)
                        .header("Authorization", owner.bearer()))
                .andExpect(status().isOk());

        // 不存在的文档：404
        mockMvc.perform(get("/api/knowledge/documents/{id}/versions", 999_999L)
                        .header("Authorization", owner.bearer()))
                .andExpect(status().isNotFound());

        // 公共知识库文档：任何人可读版本，但没有"管理员"语义 → 回滚 400
        RagDocument publicDoc = sync("公共只读-" + marker, "通用", "公共正文。" + marker);
        mockMvc.perform(get("/api/knowledge/documents/{id}/versions", publicDoc.getId())
                        .header("Authorization", owner.bearer()))
                .andExpect(status().isOk());
        MvcResult publicRollback = mockMvc.perform(
                        post("/api/knowledge/documents/{id}/rollback/{versionNo}", publicDoc.getId(), 1)
                                .header("Authorization", owner.bearer()))
                .andExpect(status().isBadRequest())
                .andReturn();
        assertThat(body(publicRollback).path("message").asText()).contains("不支持回滚");
    }

    @Test
    @DisplayName("历史兼容：version_id 为空的历史文档回填出 v1 并挂接旧块，旧块仍可检索")
    void legacyChunksWithoutVersionAreBackfilledAndRemainSearchable() throws Exception {
        String marker = uniqueMarker();
        String content = "历史正文仍然可检索。" + marker;
        RagDocument legacy = documentRepository.save(RagDocument.builder()
                .title("历史文档-" + marker)
                .city("通用")
                .category("test")
                .content(content)
                .source("测试")
                .fingerprint("legacy-fingerprint")
                .build());
        // 模拟"版本化之前"的数据：知识块没有 version_id
        chunkRepository.save(RagChunk.builder()
                .documentId(legacy.getId())
                .content(content)
                .embedding(embeddingCodec.serialize(embeddingService.embed(content)))
                .dim(embeddingService.dimension())
                .build());

        // 启动同步会先做存量回填
        ragBootstrap.run(null);

        List<RagDocumentVersion> versions = versionRepository
                .findByDocumentIdOrderByVersionNoDesc(legacy.getId());
        assertThat(versions).hasSize(1);
        assertThat(versions.get(0).getVersionNo()).isEqualTo(1);
        assertThat(versions.get(0).getStatus()).isEqualTo(RagDocumentVersion.Status.ACTIVE);
        assertThat(versions.get(0).getUserId()).isNull();

        List<RagChunk> chunks = chunkRepository.findByDocumentId(legacy.getId());
        assertThat(chunks).isNotEmpty();
        assertThat(chunks).allMatch(chunk -> versions.get(0).getId().equals(chunk.getVersionId()));

        // 历史块挂到 v1 后仍然是可检索的
        ragService.reload();
        assertThat(snippets(content, null)).anyMatch(snippet -> snippet.contains(marker));
    }

    @Test
    @DisplayName("装载规则：version_id 为空（历史）或版本 ACTIVE 才可检索")
    void onlyActiveOrLegacyChunksAreSearchable() {
        assertThat(InMemoryVectorStore.isSearchable(null, Set.of())).isTrue();
        assertThat(InMemoryVectorStore.isSearchable(7L, Set.of(7L))).isTrue();
        assertThat(InMemoryVectorStore.isSearchable(7L, Set.of(8L))).isFalse();
        assertThat(InMemoryVectorStore.isSearchable(7L, Set.of())).isFalse();
    }

    @Test
    @DisplayName("并发置位：两线程同时「批量置 INACTIVE + 插入 ACTIVE」，最终恰好 1 条 ACTIVE")
    void concurrentAppendsLeaveExactlyOneActiveVersion() throws Exception {
        String marker = uniqueMarker();
        String title = "并发版本-" + marker;
        RagDocument doc = sync(title, "通用", "初始正文。" + marker);

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < 2; i++) {
                int index = i;
                futures.add(pool.submit(() -> {
                    start.await();
                    // 每个线程各自一个事务：文档行 SELECT ... FOR UPDATE 把两个写入串行化
                    RagDocument locked = documentRepository.findById(doc.getId()).orElseThrow();
                    String content = "并发正文-" + index + "。" + marker;
                    ragVersionService.appendVersion(locked, content,
                            RagBootstrap.fingerprint(title, "通用", content));
                    return null;
                }));
            }
            start.countDown();
            for (Future<?> future : futures) {
                // 并发写入不得抛异常（若两条 ACTIVE 同时存在，MySQL 上是唯一索引报错、H2 上是行锁超时）
                future.get(60, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }

        // v1（初始）+ 两次追加 = 3 个版本；不变量 1：ACTIVE 恰好 1 条，且是版本号最大的那条
        List<RagDocumentVersion> versions = versionRepository.findByDocumentIdOrderByVersionNoDesc(doc.getId());
        assertThat(versions).extracting(RagDocumentVersion::getVersionNo).containsExactly(3, 2, 1);
        List<RagDocumentVersion> active = versionRepository
                .findByDocumentIdAndStatus(doc.getId(), RagDocumentVersion.Status.ACTIVE);
        assertThat(active).as("DB 唯一索引只在 MySQL 生效，H2 靠文档行锁也必须恰好 1 条").hasSize(1);
        assertThat(active.get(0).getVersionNo()).isEqualTo(3);
        assertThat(versions).filteredOn(RagDocumentVersion::isActive).hasSize(1);
    }
}