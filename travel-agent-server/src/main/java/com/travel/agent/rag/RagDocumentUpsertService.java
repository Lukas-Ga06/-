package com.travel.agent.rag;

import com.travel.agent.entity.RagDocument;
import com.travel.agent.repository.RagDocumentRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.Objects;

/**
 * 知识库文档写入的唯一入口（batch10 / C）：把「首建/更新文档 + 追加版本」做成幂等的 upsert。
 *
 * <h2>为什么需要它</h2>
 * batch9 的写路径是「先查 title+city，查不到就 insert」。两个并发请求（启动同步 + 手工添加、
 * 或同一用户双击上传）会同时查不到、同时插入，产生两条同标题同城市的文档，知识块也被重复
 * 切一遍——查询结果重复、版本历史被拆成两条互不相干的链。
 *
 * <h2>三层保证</h2>
 * <ol>
 *   <li><b>DB 唯一索引</b> {@code uk_rag_document_key}(doc_key)：同一 (归属, 标题, 城市) 只能有一行；</li>
 *   <li><b>JVM 内条带锁</b>：同一 doc_key 的「查 → 插 → 追加版本」在本进程内串行，正常情况下不走到撞键；</li>
 *   <li><b>撞键回读</b>：唯一键冲突（跨实例并发，或本进程内被其它入口抢先）被当作<b>正常路径</b>，
 *       捕获 {@link DataIntegrityViolationException} 后重试一次 upsert —— 此时行已存在，走更新分支。
 *       不会冒泡成 500。</li>
 * </ol>
 *
 * <h2>为什么锁在事务外面</h2>
 * 锁必须在事务<b>提交之后</b>才释放，否则另一个线程刚拿到锁就查不到上一笔还没提交的行，
 * 仍会重复插入。因此本类的方法自身不是 {@code @Transactional}，事务由
 * {@link TransactionTemplate} 在锁内显式开启/提交；内部的 {@link RagVersionService#appendVersion}
 * 以 REQUIRED 加入同一事务。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class RagDocumentUpsertService {

    /** 本次写入对文档做了什么（SKIPPED = 指纹未变，什么都没写） */
    public enum Action {
        CREATED,
        UPDATED,
        SKIPPED
    }

    /**
     * @param document  写入后的文档行（SKIPPED 时为命中的现有行）
     * @param chunks    本次新写入（或挂接）的知识块数
     * @param versionNo 写入后的 ACTIVE 版本号；SKIPPED 时为 {@code null}（调用方按需回查）
     */
    public record Outcome(Action action, RagDocument document, int chunks, Integer versionNo) {}

    /** 撞唯一键后的尝试次数：第 2 次必然能读到已提交的行（InnoDB 的重复键检查会等对方提交） */
    private static final int MAX_ATTEMPTS = 3;

    /** JVM 内条带锁：只为「同一文档首建」串行化，固定数组避免按 doc_key 缓存锁对象造成泄漏 */
    private static final int LOCK_STRIPES = 64;
    private static final Object[] LOCKS = new Object[LOCK_STRIPES];

    static {
        for (int i = 0; i < LOCK_STRIPES; i++) {
            LOCKS[i] = new Object();
        }
    }

    private final RagDocumentRepository documentRepository;
    private final RagVersionService versionService;
    private final TransactionTemplate transactionTemplate;

    /**
     * 幂等写入一篇知识：命中 doc_key 则复用（必要时更新正文/指纹并追加新版本），未命中则新建。
     *
     * @param userId 归属用户；{@code null} = 公共知识（内置 knowledge.json）
     * @param source 来源描述（展示用）
     */
    public Outcome upsert(Long userId, String title, String city, String category, String content, String source) {
        String docKey = RagDocumentKeys.of(userId, title, city);
        synchronized (LOCKS[Math.floorMod(docKey.hashCode(), LOCK_STRIPES)]) {
            for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
                try {
                    return Objects.requireNonNull(transactionTemplate.execute(status ->
                            doUpsert(userId, docKey, title, city, category, content, source)));
                } catch (DataIntegrityViolationException e) {
                    // 并发首建的正常路径：另一个实例先插入了同一 doc_key。
                    // 事务边界在 TransactionTemplate 里，异常已回滚，重试时重新查库即可读到对方的行。
                    if (attempt == MAX_ATTEMPTS) {
                        log.warn("知识库文档并发首建连续 {} 次撞唯一键，放弃并抛出：docKey={}", attempt, docKey, e);
                        throw e;
                    }
                    log.info("知识库文档并发首建撞唯一键，回读后重试（第 {}/{} 次）：docKey={}",
                            attempt, MAX_ATTEMPTS, docKey);
                }
            }
            throw new IllegalStateException("unreachable");
        }
    }

    // ==================== 内部实现（必须运行在事务内） ====================

    private Outcome doUpsert(Long userId, String docKey, String title, String city,
                             String category, String content, String source) {
        String fingerprint = RagBootstrap.fingerprint(title, city, content);
        RagDocument current = findExisting(userId, docKey, title, city);
        if (current != null && fingerprint.equals(current.getFingerprint())) {
            return new Outcome(Action.SKIPPED, current, 0, null);
        }
        String effectiveSource = source == null ? "内置知识库" : source;
        RagDocument saved;
        Action action;
        if (current == null) {
            saved = documentRepository.save(RagDocument.builder()
                    .docKey(docKey)
                    .userId(userId)
                    .title(title)
                    .city(city)
                    .category(category)
                    .content(content)
                    .source(effectiveSource)
                    .fingerprint(fingerprint)
                    .build());
            // 先落库拿到 id：版本写入要按 document_id 加锁/批量置位
            documentRepository.flush();
            action = Action.CREATED;
        } else {
            current.setCategory(category);
            current.setContent(content);
            current.setSource(effectiveSource);
            current.setFingerprint(fingerprint);
            current.setUpdatedAt(LocalDateTime.now());
            saved = documentRepository.save(current);
            documentRepository.flush();
            action = Action.UPDATED;
        }
        RagVersionService.VersionWrite write = versionService.appendVersion(saved, content, fingerprint);
        return new Outcome(action, saved, write.chunks(), write.version().getVersionNo());
    }

    /**
     * 先按 doc_key 查；公共知识再兜底按「标题 + 城市」找（兼容 V11 之前写入、或 doc_key
     * 回填值与 Java 算法在极端字符下不同的历史行）。个人知识不兜底：它的 doc_key 内嵌 userId，
     * 回填表达式与 Java 算法一致，按标题兜底反而可能命中别人的文档。
     */
    private RagDocument findExisting(Long userId, String docKey, String title, String city) {
        RagDocument byKey = documentRepository.findByDocKey(docKey).orElse(null);
        if (byKey != null) {
            return byKey;
        }
        if (userId != null) {
            return null;
        }
        return documentRepository.findFirstByTitleAndCityAndUserIdIsNull(title, city).orElse(null);
    }
}