package com.travel.agent.repository;

import com.travel.agent.entity.RagDocument;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface RagDocumentRepository extends JpaRepository<RagDocument, Long> {
    List<RagDocument> findByCity(String city);
}
