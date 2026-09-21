package com.travel.agent.repository;

import com.travel.agent.entity.RagChunk;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface RagChunkRepository extends JpaRepository<RagChunk, Long> {
    List<RagChunk> findByDocumentId(Long documentId);
}
