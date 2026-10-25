package com.travel.agent.repository;

import com.travel.agent.entity.RagChunk;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface RagChunkRepository extends JpaRepository<RagChunk, Long> {
    List<RagChunk> findByDocumentId(Long documentId);

    /** 指纹变化重建知识块时使用（先删后插，与文档更新同事务） */
    void deleteByDocumentId(Long documentId);
}
