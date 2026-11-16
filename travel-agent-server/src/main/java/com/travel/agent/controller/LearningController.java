package com.travel.agent.controller;

import com.travel.agent.dto.ApiResponse;
import com.travel.agent.entity.LearningArtifact;
import com.travel.agent.entity.RagDocument;
import com.travel.agent.entity.RagDocumentVersion;
import com.travel.agent.entity.User;
import com.travel.agent.exception.BusinessException;
import com.travel.agent.rag.RagDocumentUpsertService;
import com.travel.agent.rag.RagService;
import com.travel.agent.rag.RagVersionService;
import com.travel.agent.rag.VectorSearchResult;
import com.travel.agent.repository.LearningArtifactRepository;
import com.travel.agent.repository.RagDocumentRepository;
import com.travel.agent.repository.RagDocumentVersionRepository;
import com.travel.agent.service.AuthService;
import com.travel.agent.service.LlmAssistantService;
import com.travel.agent.util.Paging;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
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
    private final RagDocumentUpsertService ragDocumentUpsertService;
    private final RagVersionService ragVersionService;
    private final RagService ragService;

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
                                                         @RequestParam(defaultValue = "" + Paging.DEFAULT_LIMIT) int limit,
                                                         @RequestParam(defaultValue = "0") int offset,
                                                         Authentication authentication) {
        User user = authService.requireUser(authentication.getName());
        List<LearningArtifact> list = kind == null || kind.isBlank()
                ? artifactRepository.findByUserIdOrderByCreatedAtDesc(user.getId())
                : artifactRepository.findByUserIdAndKindOrderByCreatedAtDesc(user.getId(), kind);
        return ApiResponse.ok(Paging.slice(list, offset, limit));
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

    /**
     * 知识库搭建：自定义文档 → upsert（同一用户 + 同标题 + 同城市复用同一文档）→ v1/新版本 →
     * 切块 → 向量化 → 进入统一检索（仅本人可检索）。
     *
     * <p>batch10 / C：写入改为按 {@code doc_key = "<userId>|title|个人"} 的 upsert。
     * 并发/重复提交同一标题不再产生两条重复文档（内容变则追加版本），响应形态不变：
     * {@code documentId} 可能沿用上一笔的 id，{@code versionNo} 递增。
     */
    @PostMapping("/knowledge/add")
    public ApiResponse<Map<String, Object>> addKnowledge(@Valid @RequestBody KnowledgeAddRequest request,
                                                         Authentication authentication) {
        User user = authService.requireUser(authentication.getName());
        String title = request.title() == null || request.title().isBlank() ? "未命名知识" : request.title();
        String content = request.content();
        RagDocumentUpsertService.Outcome outcome = ragDocumentUpsertService.upsert(
                user.getId(), title, "个人", "personal", content, "用户知识库");
        RagDocument doc = outcome.document();
        Integer versionNo = outcome.versionNo();
        if (versionNo == null) {
            // 指纹未变（SKIPPED）：返回当前 ACTIVE 版本号，保持响应形态稳定
            versionNo = ragVersionService.versions(doc.getId()).stream()
                    .filter(RagDocumentVersion::isActive)
                    .map(RagDocumentVersion::getVersionNo)
                    .findFirst()
                    .orElse(1);
        }
        ragService.reload();
        return ApiResponse.ok(Map.of(
                "documentId", doc.getId(),
                "chunks", outcome.chunks(),
                "versionNo", versionNo));
    }

    /** 知识库调用：向量检索（公共知识 + 当前用户自己的知识，不含他人的私有文档） */
    public record KnowledgeSearchRequest(
            @NotBlank(message = "不能为空")
            @Size(max = 500, message = "长度不能超过 500 个字符") String query) {}

    @PostMapping("/knowledge/search")
    public ApiResponse<List<Map<String, Object>>> searchKnowledge(@Valid @RequestBody KnowledgeSearchRequest request,
                                                                  Authentication authentication) {
        User user = authService.requireUser(authentication.getName());
        List<VectorSearchResult> results = ragService.search(request.query(), null, user.getId(), 5);
        return ApiResponse.ok(results.stream().map(r -> Map.of(
                "title", (Object) r.getDocument().getTitle(),
                "snippet", (Object) r.getChunk().getContent(),
                "score", r.getScore())).collect(Collectors.toList()));
    }

    // ==================== 知识库版本化（batch9 / C） ====================
    //
    // 路径按契约固定在 /api/knowledge/documents 下（与 /api/learning 的其余接口分开），
    // 沿用项目统一 ApiResponse 形态与鉴权/归属校验惯例。
    // 归属规则：user_id 非空且 ≠ 当前用户 → 404（不暴露他人私有文档的存在性）；
    // 公共知识库文档（user_id 为空）任何人都能读版本，但不允许回滚——
    // 项目里没有"管理员"语义，回滚公共知识一律 400 "公共知识库文档不支持回滚"。

    /** 版本列表/详情/回滚接口 */
    @RestController
    @RequestMapping("/api/knowledge/documents")
    @RequiredArgsConstructor
    public static class KnowledgeVersionController {

        private final AuthService authService;
        private final RagDocumentRepository documentRepository;
        private final RagDocumentVersionRepository versionRepository;
        private final RagVersionService ragVersionService;
        private final RagService ragService;

        public record VersionSummary(int versionNo, String status, String fingerprint,
                                     LocalDateTime createdAt, boolean active) {}

        public record VersionDetail(int versionNo, String status, String fingerprint,
                                    LocalDateTime createdAt, boolean active, String content) {}

        public record RollbackResult(int activeVersionNo) {}

        /** 版本列表：按 versionNo 倒序 */
        @GetMapping("/{id}/versions")
        public ApiResponse<List<VersionSummary>> versions(@PathVariable Long id, Authentication authentication) {
            requireVisibleDocument(id, authService.requireUser(authentication.getName()));
            List<VersionSummary> rows = versionRepository.findByDocumentIdOrderByVersionNoDesc(id).stream()
                    .map(version -> new VersionSummary(version.getVersionNo(), version.getStatus().name(),
                            version.getFingerprint(), version.getCreatedAt(), version.isActive()))
                    .toList();
            return ApiResponse.ok(rows);
        }

        /** 指定版本详情：含正文快照 */
        @GetMapping("/{id}/versions/{versionNo}")
        public ApiResponse<VersionDetail> version(@PathVariable Long id, @PathVariable int versionNo,
                                                  Authentication authentication) {
            requireVisibleDocument(id, authService.requireUser(authentication.getName()));
            RagDocumentVersion version = versionRepository.findByDocumentIdAndVersionNo(id, versionNo)
                    .orElseThrow(() -> new BusinessException(404, "版本不存在"));
            return ApiResponse.ok(new VersionDetail(version.getVersionNo(), version.getStatus().name(),
                    version.getFingerprint(), version.getCreatedAt(), version.isActive(), version.getContent()));
        }

        /** 回滚：目标版本置 ACTIVE、其余置 INACTIVE，并立即重建向量索引 */
        @PostMapping("/{id}/rollback/{versionNo}")
        public ApiResponse<RollbackResult> rollback(@PathVariable Long id, @PathVariable int versionNo,
                                                    Authentication authentication) {
            RagDocument document = requireVisibleDocument(id, authService.requireUser(authentication.getName()));
            if (document.getUserId() == null) {
                // 没有管理员语义：公共知识（内置 knowledge.json 种子）不允许普通用户回滚
                throw new BusinessException(400, "公共知识库文档不支持回滚");
            }
            RagDocumentVersion active = ragVersionService.rollback(id, versionNo);
            ragService.reload();
            return ApiResponse.ok(new RollbackResult(active.getVersionNo()));
        }

        /**
         * 归属可见性：公共文档（user_id 为空）任何人可读；他人个人文档一律 404（不暴露存在性）。
         */
        private RagDocument requireVisibleDocument(Long id, User user) {
            RagDocument document = documentRepository.findById(id)
                    .orElseThrow(() -> new BusinessException(404, "文档不存在"));
            if (document.getUserId() != null && !document.getUserId().equals(user.getId())) {
                throw new BusinessException(404, "文档不存在");
            }
            return document;
        }
    }
}