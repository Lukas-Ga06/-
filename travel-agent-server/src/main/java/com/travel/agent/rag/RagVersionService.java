package com.travel.agent.rag;

import com.travel.agent.entity.RagChunk;
import com.travel.agent.entity.RagDocument;
import com.travel.agent.entity.RagDocumentVersion;
import com.travel.agent.exception.BusinessException;
import com.travel.agent.repository.RagChunkRepository;
import com.travel.agent.repository.RagDocumentRepository;
import com.travel.agent.repository.RagDocumentVersionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 知识库版本化写入路径（batch9 / C）。
 *
 * <p>与旧实现的差别：指纹变化时<b>不再删除</b>旧知识块，而是
 * <ul>
 *   <li>旧 ACTIVE 版本批量置 INACTIVE；</li>
 *   <li>追加 version_no = 上一版 + 1 的新版本并置 ACTIVE；</li>
 *   <li>新块挂到新版本上，旧版本的块保留（可审计、可回滚）。</li>
 * </ul>
 *
 * <p>不变量 1（同文档至多一条 ACTIVE）的保证方式：
 * <ol>
 *   <li>写入前先用 {@link RagDocumentRepository#findByIdForUpdate} 锁住文档行，
 *       同文档的版本切换在跨实例并发下串行化；</li>
 *   <li>置位只用批量 {@code UPDATE}（{@link RagDocumentVersionRepository}），
 *       不做"先查再改"的 TOCTOU 写法；置位与插入在同一事务内。</li>
 * </ol>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class RagVersionService {

    private static final RagDocumentVersion.Status ACTIVE = RagDocumentVersion.Status.ACTIVE;
    private static final RagDocumentVersion.Status INACTIVE = RagDocumentVersion.Status.INACTIVE;

    /** 单条 DELETE ... IN (...) 的版本数上限：避免 IN 列表/参数个数过大 */
    private static final int PURGE_BATCH_SIZE = 500;

    /**
     * 知识块保留窗口（batch10 / C）：除 ACTIVE 版本外，额外保留最近 N 个历史版本的块。
     * 集成方可在 yml 里补 {@code travel.rag.chunk-version-retention}（默认 2，0 = 只留 ACTIVE）。
     */
    @Value("${travel.rag.chunk-version-retention:2}")
    private int chunkVersionRetention;

    private final RagDocumentRepository documentRepository;
    private final RagDocumentVersionRepository versionRepository;
    private final RagChunkRepository chunkRepository;
    private final EmbeddingService embeddingService;
    private final EmbeddingCodec codec;

    /** 结果：新版本 + 本次写入/挂接的知识块数 */
    public record VersionWrite(RagDocumentVersion version, int chunks) {}

    /** 历史版本块清理结果：清理了多少个版本、删掉了多少个知识块 */
    public record PurgeResult(int versions, int chunks) {}

    /**
     * 追加一个新版本并设为 ACTIVE（新文档 → v1；内容变化 → 上一版 + 1）。
     * 旧版本与旧知识块都保留，不删除。
     */
    @Transactional
    public VersionWrite appendVersion(RagDocument document, String content, String fingerprint) {
        RagDocument locked = lockDocument(document.getId());
        int versionNo = nextVersionNo(locked.getId());
        return insertActiveVersion(locked, versionNo, content, fingerprint, true);
    }

    /**
     * 把 ACTIVE 切回指定版本（回滚）：目标版本置 ACTIVE、其余置 INACTIVE；
     * 该版本的块若已被清理则用版本正文重建。向量库由调用方 {@link RagService#reload()} 刷新。
     */
    @Transactional
    public RagDocumentVersion rollback(Long documentId, int versionNo) {
        lockDocument(documentId);
        RagDocumentVersion target = versionRepository.findByDocumentIdAndVersionNo(documentId, versionNo)
                .orElseThrow(() -> new BusinessException(404, "版本不存在"));
        // 不变量 1：先把其余版本批量置 INACTIVE，再把目标置 ACTIVE（同事务内完成）
        versionRepository.deactivateOtherVersions(documentId, versionNo, INACTIVE);
        if (versionRepository.markActive(documentId, versionNo, ACTIVE) == 0) {
            throw new BusinessException(404, "版本不存在");
        }
        target.setStatus(ACTIVE); // 保持返回对象的读视图与库一致
        if (chunkRepository.countByDocumentIdAndVersionId(documentId, target.getId()) == 0) {
            // 契约不变量 3：块缺失时用版本正文重建
            writeChunks(documentId, target.getId(), target.getContent());
            log.info("知识库回滚：documentId={} versionNo={} 无现存知识块，已按版本正文重建", documentId, versionNo);
        }
        log.info("知识库回滚完成：documentId={} -> activeVersionNo={}", documentId, versionNo);
        return target;
    }

    /**
     * 存量/历史数据回填（幂等，启动时调用）：
     * <ul>
     *   <li>有正文但没有版本行的文档（全新库、V10 执行前就有的数据、直接写库的旧数据）→ 补 v1 ACTIVE，
     *       并把该文档 version_id 为空的历史知识块挂到 v1（块缺失时按正文重建）；</li>
     *   <li>版本行 user_id 为空但文档有归属 → 用文档归属补齐（V10 的 SQL 回填无法在全新库写 user_id）。</li>
     * </ul>
     *
     * @return 本次新建版本行的文档数
     */
    @Transactional
    public int backfillLegacyVersions() {
        int created = 0;
        int owners = 0;
        for (RagDocument document : documentRepository.findAll()) {
            boolean hasVersion = versionRepository
                    .findFirstByDocumentIdOrderByVersionNoDesc(document.getId()).isPresent();
            if (!hasVersion) {
                RagDocument locked = lockDocument(document.getId());
                String content = locked.getContent() == null ? "" : locked.getContent();
                insertActiveVersion(locked, 1, content, locked.getFingerprint(), false);
                created++;
            } else if (document.getUserId() != null) {
                owners += versionRepository.fillOwner(document.getId(), document.getUserId());
            }
        }
        if (created > 0 || owners > 0) {
            log.info("知识库版本回填：补齐 v1 版本 {} 篇 / 补齐版本归属 {} 行", created, owners);
        }
        return created;
    }

    /** 删除文档时连带删除版本与知识块（对外语义不变：删除后不可再检索，也不再出现在版本列表） */
    @Transactional
    public void deleteDocument(Long documentId) {
        int chunks = chunkRepository.deleteByDocumentId(documentId);
        int versions = versionRepository.deleteByDocumentId(documentId);
        documentRepository.deleteById(documentId);
        log.info("删除知识库文档：documentId={} 连带删除 {} 个知识块 / {} 个版本", documentId, chunks, versions);
    }

    // ==================== 历史版本知识块清理（batch10 / C） ====================

    /**
     * 按配置的保留窗口清理历史版本的知识块（每日调度入口，见 {@link RagChunkRetentionJob}）。
     *
     * <p>这里也标 {@code @Transactional}：它内部是 <b>自调用</b> {@link #purgeOldVersionChunks(int)}，
     * Spring 代理不会为自调用开启事务，若只给带参重载加注解，调度路径会在
     * {@code @Modifying(flushAutomatically = true)} 的 DELETE 上抛
     * "No EntityManager with actual transaction available"。外部入口有事务后，
     * 自调用会加入同一个事务。
     *
     * @see #purgeOldVersionChunks(int)
     */
    @Transactional
    PurgeResult purgeOldVersionChunks() {
        return purgeOldVersionChunks(chunkVersionRetention);
    }

    /**
     * 清理「不在保留窗口内」的历史版本的知识块（包可见重载：供单测指定 retention）。
     *
     * <p>保留窗口语义（按版本序号，不看时间）：
     * <ul>
     *   <li><b>ACTIVE 版本的块永不删除</b>——它正在参与检索；</li>
     *   <li>每个文档按 {@code version_no} 倒序，保留最近 {@code retention} 个<b>非 ACTIVE</b> 版本
     *       的块（窗口内的历史版本仍可回滚，且回滚后无需重建）；</li>
     *   <li>更老的 INACTIVE 版本的块被删除；<b>版本行与正文快照保留</b>——
     *       {@link #rollback} 发现块缺失时会按版本正文重建，所以回滚能力不受影响；</li>
     *   <li>{@code version_id IS NULL} 的历史块永不删除（batch9 的兼容策略：
     *       它们是版本化上线前的数据，检索仍然装载）。</li>
     * </ul>
     *
     * <p>为什么按版本序号而不是创建时间：同一批内容变更可能在同一秒内完成（回滚/重放），
     * 时间窗会出现"保留 3 个版本"这种不确定结果；序号窗口是确定的，也便于断言。
     *
     * @param retention 额外保留的历史版本数；&lt;= 0 表示只保留 ACTIVE 版本的块
     * @return 清理的版本数与删除的块数（调用方负责打日志：不静默删除）
     */
    @Transactional
    PurgeResult purgeOldVersionChunks(int retention) {
        int keep = Math.max(0, retention);
        List<RagDocumentVersion> inactive = versionRepository
                .findByStatusOrderByDocumentIdAscVersionNoDesc(INACTIVE);
        List<Long> staleVersionIds = new ArrayList<>();
        Long currentDocumentId = null;
        int seenInDocument = 0;
        for (RagDocumentVersion version : inactive) {
            if (!version.getDocumentId().equals(currentDocumentId)) {
                currentDocumentId = version.getDocumentId();
                seenInDocument = 0;
            }
            seenInDocument++;
            if (seenInDocument > keep) {
                staleVersionIds.add(version.getId());
            }
        }
        if (staleVersionIds.isEmpty()) {
            log.debug("知识库历史版本块清理：保留窗口={}，没有需要清理的版本", keep);
            return new PurgeResult(0, 0);
        }
        int deletedChunks = 0;
        for (int from = 0; from < staleVersionIds.size(); from += PURGE_BATCH_SIZE) {
            int to = Math.min(from + PURGE_BATCH_SIZE, staleVersionIds.size());
            deletedChunks += chunkRepository.deleteByVersionIdIn(staleVersionIds.subList(from, to));
        }
        log.info("知识库历史版本块清理：保留窗口={}，清理 {} 个历史版本，删除 {} 个知识块"
                        + "（ACTIVE 版本与 version_id 为空的历史块不参与清理）",
                keep, staleVersionIds.size(), deletedChunks);
        return new PurgeResult(staleVersionIds.size(), deletedChunks);
    }

    // ==================== 内部实现 ====================

    private RagDocument lockDocument(Long documentId) {
        return documentRepository.findByIdForUpdate(documentId)
                .orElseThrow(() -> new BusinessException(404, "文档不存在"));
    }

    private int nextVersionNo(Long documentId) {
        return versionRepository.findFirstByDocumentIdOrderByVersionNoDesc(documentId)
                .map(latest -> latest.getVersionNo() + 1)
                .orElse(1);
    }

    private VersionWrite insertActiveVersion(RagDocument document, int versionNo, String content,
                                             String fingerprint, boolean writeNewChunks) {
        Long documentId = document.getId();
        // 旧 ACTIVE 批量置 INACTIVE（空操作说明该文档还没有版本，正是新建场景）
        versionRepository.deactivateActiveVersions(documentId, ACTIVE, INACTIVE);
        RagDocumentVersion version = versionRepository.save(RagDocumentVersion.builder()
                .documentId(documentId)
                .versionNo(versionNo)
                .content(content == null ? "" : content)
                .fingerprint(fingerprint)
                .status(ACTIVE)
                .userId(document.getUserId())
                .createdAt(LocalDateTime.now())
                .build());
        int chunks;
        if (writeNewChunks) {
            chunks = writeChunks(documentId, version.getId(), content);
        } else {
            // 历史数据：优先复用已有块（保持既有 embedding），只改 version_id
            chunks = chunkRepository.assignVersionToLegacyChunks(documentId, version.getId());
            if (chunks == 0) {
                chunks = writeChunks(documentId, version.getId(), content);
            }
        }
        return new VersionWrite(version, chunks);
    }

    /** 按段落切块并写入指定版本（空段落跳过） */
    private int writeChunks(Long documentId, Long versionId, String content) {
        if (content == null || content.isBlank()) {
            return 0;
        }
        int count = 0;
        for (String paragraph : content.split("\n\n")) {
            if (paragraph.isBlank()) {
                continue;
            }
            String text = paragraph.trim();
            float[] vector = embeddingService.embed(text);
            chunkRepository.save(RagChunk.builder()
                    .documentId(documentId)
                    .versionId(versionId)
                    .content(text)
                    .embedding(codec.serialize(vector))
                    .dim(embeddingService.dimension())
                    .build());
            count++;
        }
        return count;
    }

    /** 只读工具：某文档的版本列表（倒序） */
    public List<RagDocumentVersion> versions(Long documentId) {
        return versionRepository.findByDocumentIdOrderByVersionNoDesc(documentId);
    }
}