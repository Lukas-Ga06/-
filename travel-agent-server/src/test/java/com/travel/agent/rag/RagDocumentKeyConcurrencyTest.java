package com.travel.agent.rag;

import com.travel.agent.entity.RagDocument;
import com.travel.agent.entity.RagDocumentVersion;
import com.travel.agent.repository.RagDocumentRepository;
import com.travel.agent.repository.RagDocumentVersionRepository;
import com.travel.agent.support.IntegrationTestSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 同一新文档并发首建不重复入库（batch10 / C 契约 §3.3）。
 *
 * <p>回归背景：batch9 的写路径是「先按 title+city 查 → 查不到就 insert」。两个并发请求
 * （启动同步 + 手工添加，或用户双击上传）会同时查不到、同时插入，于是同一篇知识出现两条文档，
 * 知识块被切两遍、版本历史被拆成两条互不相干的链。
 *
 * <p>修复后写入统一走 {@link RagDocumentUpsertService}：doc_key 上的唯一索引是最终保证，
 * 应用层用「JVM 条带锁 + 撞键回读」把冲突当正常路径（不得冒 500）。
 *
 * <p>本用例覆盖两条：并发（2 线程同时首建 → 恰好 1 条文档）与串行（两次 → 复用同一 id）。
 */
class RagDocumentKeyConcurrencyTest extends IntegrationTestSupport {

    @Autowired
    private RagBootstrap ragBootstrap;

    @Autowired
    private RagDocumentRepository documentRepository;

    @Autowired
    private RagDocumentVersionRepository versionRepository;

    @Autowired
    private RagService ragService;

    @BeforeEach
    void seedPublicKnowledge() throws Exception {
        if (documentRepository.count() == 0) {
            ragBootstrap.run(null);
        }
        ragService.reload();
    }

    private static String uniqueMarker() {
        return "DK-" + UUID.randomUUID().toString().substring(0, 8);
    }

    @Test
    @DisplayName("并发首建：两线程同时 syncDocument 同一 title/city → 恰好 1 条文档、恰好 1 条 ACTIVE 版本")
    void concurrentFirstCreateLeavesSingleDocument() throws Exception {
        String marker = uniqueMarker();
        String title = "并发首建-" + marker;
        String city = "通用";
        String docKey = RagDocumentKeys.of(null, title, city);

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<RagBootstrap.SyncOutcome>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < 2; i++) {
                // 两个线程内容不同：先到的建 v1，后到的在同一行上追加 v2（这也是并发路径的一部分）
                String content = "并发正文-" + i + "。" + marker;
                futures.add(pool.submit(() -> {
                    start.await();
                    // 每次调用各自开事务（RagDocumentUpsertService 内部 TransactionTemplate）
                    return ragBootstrap.syncDocument(title, city, "test", content, "测试");
                }));
            }
            start.countDown();
            for (Future<RagBootstrap.SyncOutcome> future : futures) {
                assertThat(future.get(60, TimeUnit.SECONDS)).as("并发同步不得抛异常（撞唯一键必须当正常路径）").isNotNull();
            }
        } finally {
            pool.shutdownNow();
        }

        // 1) 只允许一条文档
        List<RagDocument> sameKey = documentRepository.findAll().stream()
                .filter(doc -> docKey.equals(doc.getDocKey()))
                .toList();
        assertThat(sameKey).as("同一 doc_key 只允许一行文档").hasSize(1);
        assertThat(documentRepository.findFirstByTitleAndCityAndUserIdIsNull(title, city)).isPresent();

        RagDocument doc = sameKey.get(0);
        assertThat(doc.getId()).isNotNull();
        assertThat(doc.getDocKey()).isEqualTo(docKey);
        assertThat(doc.getTitle()).isEqualTo(title);

        // 2) 版本链是完整的一条：ACTIVE 恰好 1 条，总数不超过 2（第二个线程要么追加、要么被指纹判重）
        List<RagDocumentVersion> versions = versionRepository.findByDocumentIdOrderByVersionNoDesc(doc.getId());
        assertThat(versions).isNotEmpty().hasSizeLessThanOrEqualTo(2);
        assertThat(versions.stream().filter(RagDocumentVersion::isActive).count())
                .as("不变量 1：同文档至多一条 ACTIVE")
                .isEqualTo(1);
        if (versions.size() == 2) {
            assertThat(versions.get(0).getVersionNo()).isEqualTo(2);
            assertThat(versions.get(1).getVersionNo()).isEqualTo(1);
            assertThat(versions.get(1).isActive()).isFalse();
        }
    }

    @Test
    @DisplayName("串行两次 syncDocument 同一 title/city → 复用同一文档（id 不变），内容变则追加版本")
    void sequentialSyncReusesSameDocument() {
        String marker = uniqueMarker();
        String title = "串行复用-" + marker;
        String city = "通用";

        RagBootstrap.SyncOutcome first = ragBootstrap.syncDocument(title, city, "test", "第一版。" + marker, "测试");
        assertThat(first.action()).isEqualTo(RagBootstrap.DocumentAction.CREATED);
        Long documentId = first.document().getId();
        assertThat(first.chunks()).isPositive();

        RagBootstrap.SyncOutcome second = ragBootstrap.syncDocument(title, city, "test", "第二版。" + marker, "测试");
        assertThat(second.action()).isEqualTo(RagBootstrap.DocumentAction.UPDATED);
        assertThat(second.document().getId()).isEqualTo(documentId);

        // 内容完全一致 → 指纹相同 → 跳过，且仍然复用同一行
        RagBootstrap.SyncOutcome third = ragBootstrap.syncDocument(title, city, "test", "第二版。" + marker, "测试");
        assertThat(third.action()).isEqualTo(RagBootstrap.DocumentAction.SKIPPED);
        assertThat(third.document().getId()).isEqualTo(documentId);
        assertThat(third.chunks()).isZero();

        assertThat(documentRepository.findByDocKey(RagDocumentKeys.of(null, title, city)))
                .as("doc_key 应能反查到同一行")
                .get()
                .extracting(RagDocument::getId)
                .isEqualTo(documentId);
        assertThat(versionRepository.findByDocumentIdOrderByVersionNoDesc(documentId))
                .as("两次不同的正文 = 两个版本；跳过不产生版本")
                .hasSize(2);
        assertThat(versionRepository.findByDocumentIdAndStatus(documentId, RagDocumentVersion.Status.ACTIVE))
                .hasSize(1);
    }

    @Test
    @DisplayName("唯一索引兜底：绕过 upsert 直接插两条同 doc_key 的文档 → 第二条被数据库拒绝")
    void uniqueIndexRejectsDirectDuplicateInsert() {
        String marker = uniqueMarker();
        String title = "唯一键-" + marker;
        RagDocument first = documentRepository.saveAndFlush(RagDocument.builder()
                .title(title).city("通用").category("test").content("a").source("测试").fingerprint("f1").build());
        assertThat(first.getDocKey()).isEqualTo(RagDocumentKeys.of(null, title, "通用"));

        // 与跨实例并发等价：另一条同 key 的插入必须撞 uk_rag_document_key（H2 侧由实体 @UniqueConstraint 建出）
        assertThatThrownBy(() -> documentRepository.saveAndFlush(RagDocument.builder()
                .title(title).city("通用").category("test").content("b").source("测试").fingerprint("f2").build()))
                .as("同一 doc_key 的第二条文档必须被唯一索引拒绝")
                .isInstanceOf(DataIntegrityViolationException.class);

        assertThat(documentRepository.findById(first.getId())).isPresent();
        assertThat(documentRepository.findAll().stream()
                .filter(doc -> RagDocumentKeys.of(null, title, "通用").equals(doc.getDocKey())))
                .hasSize(1);
    }

    @Test
    @DisplayName("doc_key 算法：归属前缀 + 标题 + 城市，个人知识与公共知识不互相命中，超长按 190 截断")
    void docKeyAlgorithmIsStable() {
        assertThat(RagDocumentKeys.of(null, "标题", "北京")).isEqualTo("public|标题|北京");
        assertThat(RagDocumentKeys.of(7L, "标题", "北京")).isEqualTo("7|标题|北京");
        assertThat(RagDocumentKeys.of(7L, "标题", "北京")).isNotEqualTo(RagDocumentKeys.of(8L, "标题", "北京"));
        // null 按空串：与 V11 的 COALESCE(title,'')/COALESCE(city,'') 对齐
        assertThat(RagDocumentKeys.of(null, null, null)).isEqualTo("public||");
        assertThat(RagDocumentKeys.of(1L, null, "上海")).isEqualTo("1||上海");
        // 超长截断，且不劈开代理对（emoji）
        String longTitle = "长".repeat(300);
        assertThat(RagDocumentKeys.of(null, longTitle, "北京")).hasSize(RagDocumentKeys.MAX_LENGTH);
        // "public|" + 182 个 a + 😀 = 恰好 190 个 code point：按 UTF-16 硬截会留下半个代理对
        String emojiTitle = "a".repeat(182) + "😀" + "尾";
        String truncated = RagDocumentKeys.of(null, emojiTitle, "");
        assertThat(truncated.codePointCount(0, truncated.length())).isEqualTo(RagDocumentKeys.MAX_LENGTH);
        assertThat(truncated).endsWith("😀");
        assertThat(truncated.charAt(truncated.length() - 1)).isNotEqualTo('\uD83D');
    }
}