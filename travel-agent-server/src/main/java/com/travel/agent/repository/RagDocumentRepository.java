package com.travel.agent.repository;

import com.travel.agent.entity.RagDocument;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface RagDocumentRepository extends JpaRepository<RagDocument, Long> {

    List<RagDocument> findByCity(String city);

    Optional<RagDocument> findFirstByTitleAndCity(String title, String city);

    /**
     * 按业务键取文档（batch10 / C：并发首建去重的唯一入口）。
     *
     * <p>doc_key = {@code LEFT(CONCAT(COALESCE(user_id,'public'),'|',title,'|',city),190)}，
     * 已经内嵌归属用户，所以个人知识与公共知识不会互相命中；库里由
     * {@code uk_rag_document_key} 保证唯一。写路径见 {@code RagDocumentUpsertService}。
     */
    Optional<RagDocument> findByDocKey(String docKey);

    /**
     * 兼容 V11 之前写入的公共文档（或 doc_key 回填值与 Java 算法在极端字符下的差异）：
     * 按「标题 + 城市 + 公共归属」回找，找到后沿用该行（不再改它的 doc_key，避免撞唯一键）。
     * 个人知识不需要这条兜底——它的 doc_key 前缀是 userId，回填表达式与 Java 算法一致。
     */
    Optional<RagDocument> findFirstByTitleAndCityAndUserIdIsNull(String title, String city);

    /**
     * 取文档行并加悲观写锁：版本切换（追加新版本 / 回滚）都先锁住该文档行，
     * 让"批量置 INACTIVE + 插入 ACTIVE"在跨实例并发下串行化，
     * 避免出现两条 ACTIVE 版本（不变量 1）。
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select d from RagDocument d where d.id = :id")
    Optional<RagDocument> findByIdForUpdate(@Param("id") Long id);
}