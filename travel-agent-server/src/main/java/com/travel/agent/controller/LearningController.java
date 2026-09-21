package com.travel.agent.controller;

import com.travel.agent.dto.ApiResponse;
import com.travel.agent.entity.LearningArtifact;
import com.travel.agent.entity.RagChunk;
import com.travel.agent.entity.RagDocument;
import com.travel.agent.entity.User;
import com.travel.agent.rag.EmbeddingCodec;
import com.travel.agent.rag.EmbeddingService;
import com.travel.agent.rag.RagService;
import com.travel.agent.rag.VectorSearchResult;
import com.travel.agent.rag.VectorStore;
import com.travel.agent.repository.LearningArtifactRepository;
import com.travel.agent.repository.RagChunkRepository;
import com.travel.agent.repository.RagDocumentRepository;
import com.travel.agent.service.AuthService;
import com.travel.agent.service.LlmAssistantService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/** 学习辅助：知识框架 / 学习路线 / 重点归纳 / 知识库搭建与调用（复用 RAG 管线） */
@RestController
@RequestMapping("/api/learning")
@RequiredArgsConstructor
public class LearningController {

    private final AuthService authService;
    private final LlmAssistantService llmAssistantService;
    private final LearningArtifactRepository artifactRepository;
    private final RagDocumentRepository ragDocumentRepository;
    private final RagChunkRepository ragChunkRepository;
    private final EmbeddingService embeddingService;
    private final EmbeddingCodec embeddingCodec;
    private final RagService ragService;
    private final VectorStore vectorStore;

    public record GenerateRequest(
            @Size(max = 30, message = "长度不能超过 30 个字符") String kind,
            @Size(max = 200, message = "长度不能超过 200 个字符") String topic,
            @Size(max = 20000, message = "长度不能超过 20000 个字符") String text) {}

    @PostMapping("/generate")
    public ApiResponse<LearningArtifact> generate(@Valid @RequestBody GenerateRequest request, Authentication authentication) {
        User user = authService.requireUser(authentication.getName());
        String kind = request.kind() == null ? LearningArtifact.KIND_SUMMARY : request.kind();
        String topic = request.topic() == null || request.topic().isBlank()
                ? (request.text() == null ? "未命名" : request.text().substring(0, Math.min(30, request.text().length())))
                : request.topic();
        String content = switch (kind) {
            case LearningArtifact.KIND_FRAMEWORK -> llmAssistantService.generateFramework(request.topic());
            case LearningArtifact.KIND_PATH -> llmAssistantService.generatePath(request.topic());
            default -> llmAssistantService.generateSummary(
                    request.text() == null || request.text().isBlank() ? request.topic() : request.text());
        };
        LearningArtifact saved = artifactRepository.save(LearningArtifact.builder()
                .userId(user.getId()).kind(kind).topic(topic).content(content).build());
        return ApiResponse.ok(saved);
    }

    @GetMapping("/artifacts")
    public ApiResponse<List<LearningArtifact>> artifacts(@RequestParam(required = false) String kind,
                                                         Authentication authentication) {
        User user = authService.requireUser(authentication.getName());
        List<LearningArtifact> list = kind == null || kind.isBlank()
                ? artifactRepository.findByUserIdOrderByCreatedAtDesc(user.getId())
                : artifactRepository.findByUserIdAndKindOrderByCreatedAtDesc(user.getId(), kind);
        return ApiResponse.ok(list);
    }

    @DeleteMapping("/artifacts/{id}")
    public ApiResponse<Void> delete(@PathVariable Long id, Authentication authentication) {
        User user = authService.requireUser(authentication.getName());
        artifactRepository.findById(id)
                .filter(a -> a.getUserId().equals(user.getId()))
                .ifPresent(artifactRepository::delete);
        return ApiResponse.ok(null);
    }

    // ==================== 知识库搭建与调用（复用 RAG 管线） ====================

    public record KnowledgeAddRequest(
            @Size(max = 200, message = "长度不能超过 200 个字符") String title,
            @NotBlank(message = "不能为空")
            @Size(max = 20000, message = "长度不能超过 20000 个字符") String content) {}

    /** 知识库搭建：自定义文档 → 切块 → 向量化 → 进入统一检索 */
    @PostMapping("/knowledge/add")
    public ApiResponse<Map<String, Object>> addKnowledge(@Valid @RequestBody KnowledgeAddRequest request,
                                                         Authentication authentication) {
        User user = authService.requireUser(authentication.getName());
        RagDocument doc = ragDocumentRepository.save(RagDocument.builder()
                .title(request.title() == null || request.title().isBlank() ? "未命名知识" : request.title())
                .city("个人")
                .category("personal")
                .content(request.content())
                .source("用户知识库")
                .build());
        int chunks = 0;
        for (String paragraph : request.content().split("\n\n")) {
            if (paragraph.isBlank()) continue;
            String trimmed = paragraph.trim();
            float[] vector = embeddingService.embed(trimmed);
            ragChunkRepository.save(com.travel.agent.entity.RagChunk.builder()
                    .documentId(doc.getId())
                    .content(trimmed)
                    .embedding(embeddingCodec.serialize(vector))
                    .dim(embeddingService.dimension())
                    .build());
            chunks++;
        }
        ragDocumentRepository.flush();
        vectorStore.reload();
        return ApiResponse.ok(Map.of("documentId", doc.getId(), "chunks", chunks));
    }

    /** 知识库调用：向量检索 */
    public record KnowledgeSearchRequest(
            @NotBlank(message = "不能为空")
            @Size(max = 500, message = "长度不能超过 500 个字符") String query) {}

    @PostMapping("/knowledge/search")
    public ApiResponse<List<Map<String, Object>>> searchKnowledge(@Valid @RequestBody KnowledgeSearchRequest request,
                                                                  Authentication authentication) {
        authService.requireUser(authentication.getName());
        List<VectorSearchResult> results = ragService.search(request.query(), null, 5);
        return ApiResponse.ok(results.stream().map(r -> Map.of(
                "title", (Object) r.getDocument().getTitle(),
                "snippet", (Object) r.getChunk().getContent(),
                "score", r.getScore())).collect(Collectors.toList()));
    }
}
