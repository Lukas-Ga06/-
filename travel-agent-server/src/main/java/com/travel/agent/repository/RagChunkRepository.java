package com.travel.agent.repository;

import com.travel.agent.entity.RagChunk;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;

public interface RagChunkRepository extends JpaRepository<RagChunk, Long> {

    List<RagChunk> findByDocumentId(Long documentId);

    List<RagChunk> findByDocumentIdAndVersionId(Long documentId, Long versionId);

    long countByDocumentId(Long documentId);

    long countByDocumentIdAndVersionId(Long documentId, Long versionId);

    /**
     * 删除整篇文档的知识块（仅用于删除文档；版本化后的"内容更新"不再调用它——
     * 旧版本的块保留在库里，检索时由 version 状态过滤）。
     */
    @Modifying
    @Query("delete from RagChunk c where c.documentId = :documentId")
    int deleteByDocumentId(@Param("documentId") Long documentId);

    /**
     * 按版本批量删除知识块：只删**显式指定**的历史版本（batch10 / C 保留窗口清理用）。
     *
     * <p>永不触碰 {@code version_id IS NULL} 的历史块（batch9 兼容策略），
     * 也不会被 ACTIVE 版本命中——调用方只传非 ACTIVE 且不在保留窗口内的版本 id，
     * 见 {@code RagVersionService#purgeOldVersionChunks(int)}。
     */
    @Modifying(flushAutomatically = true)
    @Query("delete from RagChunk c where c.versionId in :versionIds")
    int deleteByVersionIdIn(@Param("versionIds") Collection<Long> versionIds);

    /**
     * 把历史（version_id 为空）的知识块挂到指定版本上：存量数据回填用。
     * 返回被挂接的块数。
     */
    @Modifying(flushAutomatically = true)
    @Query("update RagChunk c set c.versionId = :versionId "
            + "where c.documentId = :documentId and c.versionId is null")
    int assignVersionToLegacyChunks(@Param("documentId") Long documentId,
                                    @Param("versionId") Long versionId);
}