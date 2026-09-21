package com.travel.agent.repository;

import com.travel.agent.entity.LearningArtifact;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface LearningArtifactRepository extends JpaRepository<LearningArtifact, Long> {
    List<LearningArtifact> findByUserIdAndKindOrderByCreatedAtDesc(Long userId, String kind);
    List<LearningArtifact> findByUserIdOrderByCreatedAtDesc(Long userId);
}
