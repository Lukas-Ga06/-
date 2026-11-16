package com.travel.agent.repository;

import com.travel.agent.entity.RagDocumentVersion;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface RagDocumentVersionRepository extends JpaRepository<RagDocumentVersion, Long> {

    /** 版本列表（倒序）：HTTP 版本列表接口与"取当前最大版本号"复用 */
    List<RagDocumentVersion> findByDocumentIdOrderByVersionNoDesc(Long documentId);

    List<RagDocumentVersion> findByDocumentIdAndStatus(Long documentId, RagDocumentVersion.Status status);

    /**
     * 某状态的全部版本，按「文档升序 + 版本号降序」：知识块保留窗口清理用
     * （{@code RagVersionService#purgeOldVersionChunks} 按文档顺序扫描，天然把同一文档的
     * 版本排成"最新→最老"，跳过窗口内的前 N 条即可）。
     */
    List<RagDocumentVersion> findByStatusOrderByDocumentIdAscVersionNoDesc(RagDocumentVersion.Status status);

    Optional<RagDocumentVersion> findByDocumentIdAndVersionNo(Long documentId, Integer versionNo);

    Optional<RagDocumentVersion> findFirstByDocumentIdOrderByVersionNoDesc(Long documentId);

    /** 向量库装载用：所有 ACTIVE 版本的 id */
    @Query("select v.id from RagDocumentVersion v where v.status = :status")
    List<Long> findIdsByStatus(@Param("status") RagDocumentVersion.Status status);

    /**
     * 把该文档当前的 ACTIVE 版本全部置为 INACTIVE（追加新版本前调用）。
     * 用批量 UPDATE 而不是"先查再逐条改"，避免 TOCTOU；与插入新 ACTIVE 版本同事务。
     */
    @Modifying(flushAutomatically = true)
    @Query("update RagDocumentVersion v set v.status = :inactive "
            + "where v.documentId = :documentId and v.status = :active")
    int deactivateActiveVersions(@Param("documentId") Long documentId,
                                 @Param("active") RagDocumentVersion.Status active,
                                 @Param("inactive") RagDocumentVersion.Status inactive);

    /** 回滚用：把该文档除目标版本外的所有版本置 INACTIVE */
    @Modifying(flushAutomatically = true)
    @Query("update RagDocumentVersion v set v.status = :inactive "
            + "where v.documentId = :documentId and v.versionNo <> :keepVersionNo")
    int deactivateOtherVersions(@Param("documentId") Long documentId,
                                @Param("keepVersionNo") Integer keepVersionNo,
                                @Param("inactive") RagDocumentVersion.Status inactive);

    /** 回滚用：把目标版本置 ACTIVE（与上面的批量置位同事务，最终恰好一条 ACTIVE） */
    @Modifying(flushAutomatically = true)
    @Query("update RagDocumentVersion v set v.status = :active "
            + "where v.documentId = :documentId and v.versionNo = :versionNo")
    int markActive(@Param("documentId") Long documentId,
                   @Param("versionNo") Integer versionNo,
                   @Param("active") RagDocumentVersion.Status active);

    /** 存量数据回填：把文档归属复刻到历史版本行（V10 的 SQL 回填无法在全新库写 user_id） */
    @Modifying(flushAutomatically = true)
    @Query("update RagDocumentVersion v set v.userId = :userId "
            + "where v.documentId = :documentId and v.userId is null")
    int fillOwner(@Param("documentId") Long documentId, @Param("userId") Long userId);

    /** 删除文档时连带删除版本（历史块由 RagChunkRepository#deleteByDocumentId 处理） */
    @Modifying(flushAutomatically = true)
    @Query("delete from RagDocumentVersion v where v.documentId = :documentId")
    int deleteByDocumentId(@Param("documentId") Long documentId);
}