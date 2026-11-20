package com.travel.agent.controller;

import com.travel.agent.dto.ApiResponse;
import com.travel.agent.entity.RagDocument;
import com.travel.agent.entity.RagDocumentVersion;
import com.travel.agent.entity.User;
import com.travel.agent.exception.BusinessException;
import com.travel.agent.rag.RagCitation;
import com.travel.agent.rag.RagDocumentUpsertService;
import com.travel.agent.rag.RagService;
import com.travel.agent.rag.RagVersionService;
import com.travel.agent.repository.RagDocumentRepository;
import com.travel.agent.repository.RagDocumentVersionRepository;
import com.travel.agent.service.AuthService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 旅行知识库：RAG 文档的搭建、检索、版本与回滚。
 *
 * <p>P0 从原 {@code LearningController} 拆出：学习产物业务已退役，
 * RAG 文档能力保留并归入旅行域——它是 Planner 研究链路的静态知识来源，
 * 不再是独立的「学习辅助」模块。
 */
@RestController
@RequestMapping("/api/knowledge")
@RequiredArgsConstructor
public class TravelKnowledgeController {

    private final AuthService authService;
    private final RagDocumentUpsertService ragDocumentUpsertService;
    private final RagVersionService ragVersionService;
    private final RagService ragService;
    private final RagDocumentRepository documentRepository;
    private final RagDocumentVersionRepository versionRepository;

    public record KnowledgeAddRequest(
            @Size(max = 200, message = "长度不能超过 200 个字符") String title,
            @NotBlank(message = "不能为空")
            @Size(max = 20000, message = "长度不能超过 20000 个字符") String content) {}

    public record KnowledgeSearchRequest(
            @NotBlank(message = "不能为空")
            @Size(max = 500, message = "长度不能超过 500 个字符") String query) {}

    public record VersionSummary(int versionNo, String status, String fingerprint,
                                 LocalDateTime createdAt, boolean active) {}

    public record VersionDetail(int versionNo, String status, String fingerprint,
                                LocalDateTime createdAt, boolean active, String content) {}

    public record RollbackResult(int activeVersionNo) {}

    /**
     * 知识库搭建：自定义文档 → upsert（同一用户 + 同标题 + 同城市复用同一文档）→ v1/新版本 →
     * 切块 → 向量化 → 进入统一检索（仅本人可检索）。
     */
    @PostMapping("/add")
    public ApiResponse<Map<String, Object>> addKnowledge(@Valid @RequestBody KnowledgeAddRequest request,
                                                         Authentication authentication) {
        User user = authService.requireUser(authentication.getName());
        String title = request.title() == null || request.title().isBlank() ? "未命名知识" : request.title();
        RagDocumentUpsertService.Outcome outcome = ragDocumentUpsertService.upsert(
                user.getId(), title, "个人", "personal", request.content(), "用户知识库");
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

    /**
     * 知识库检索：公共知识 + 当前用户自己的知识（不含他人的私有文档）。
     * 每条结果都带 documentId/version/chunkId/score/source，前端与 Planner 可追溯到原文。
     */
    @PostMapping("/search")
    public ApiResponse<List<Map<String, Object>>> searchKnowledge(@Valid @RequestBody KnowledgeSearchRequest request,
                                                                  Authentication authentication) {
        User user = authService.requireUser(authentication.getName());
        List<RagCitation> results = ragService.searchWithCitations(request.query(), null, user.getId(), 5);
        // 用 LinkedHashMap 而不是 Map.of：版本化之前的历史知识块 version 合法为 null，
        // Map.of 不接受 null 值，会把一次正常检索变成 500。
        return ApiResponse.ok(results.stream().map(c -> {
            Map<String, Object> row = new java.util.LinkedHashMap<>();
            row.put("documentId", c.documentId());
            row.put("version", c.version());
            row.put("chunkId", c.chunkId());
            row.put("title", c.title());
            row.put("snippet", c.snippet());
            row.put("source", c.source());
            row.put("score", c.score());
            return row;
        }).toList());
    }

    /** 版本列表：按 versionNo 倒序 */
    @GetMapping("/documents/{id}/versions")
    public ApiResponse<List<VersionSummary>> versions(@PathVariable Long id, Authentication authentication) {
        requireVisibleDocument(id, authService.requireUser(authentication.getName()));
        List<VersionSummary> rows = versionRepository.findByDocumentIdOrderByVersionNoDesc(id).stream()
                .map(version -> new VersionSummary(version.getVersionNo(), version.getStatus().name(),
                        version.getFingerprint(), version.getCreatedAt(), version.isActive()))
                .toList();
        return ApiResponse.ok(rows);
    }

    /** 指定版本详情：含正文快照 */
    @GetMapping("/documents/{id}/versions/{versionNo}")
    public ApiResponse<VersionDetail> version(@PathVariable Long id, @PathVariable int versionNo,
                                              Authentication authentication) {
        requireVisibleDocument(id, authService.requireUser(authentication.getName()));
        RagDocumentVersion version = versionRepository.findByDocumentIdAndVersionNo(id, versionNo)
                .orElseThrow(() -> new BusinessException(404, "版本不存在"));
        return ApiResponse.ok(new VersionDetail(version.getVersionNo(), version.getStatus().name(),
                version.getFingerprint(), version.getCreatedAt(), version.isActive(), version.getContent()));
    }

    /** 回滚：目标版本置 ACTIVE、其余置 INACTIVE，并立即重建向量索引 */
    @PostMapping("/documents/{id}/rollback/{versionNo}")
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

    /** 归属可见性：公共文档（user_id 为空）任何人可读；他人个人文档一律 404（不暴露存在性）。 */
    private RagDocument requireVisibleDocument(Long id, User user) {
        RagDocument document = documentRepository.findById(id)
                .orElseThrow(() -> new BusinessException(404, "文档不存在"));
        if (document.getUserId() != null && !document.getUserId().equals(user.getId())) {
            throw new BusinessException(404, "文档不存在");
        }
        return document;
    }
}
