package com.travel.agent.entity;

import jakarta.persistence.*;
import lombok.*;

import com.travel.agent.rag.RagDocumentKeys;

import java.time.LocalDateTime;

/**
 * 知识库文档（"当前正文"视图）。
 *
 * <p>版本化（batch9 / C）后，本行只保存"最新同步到的正文/指纹"，作为 knowledge.json 的同步游标；
 * 每次内容变更的正文快照在 {@link RagDocumentVersion}，检索只认"版本为 ACTIVE 或 version_id 为空"
 * 的知识块。{@code content}/{@code fingerprint} 保持原语义（RagBootstrap 靠 fingerprint 判断跳过），
 * 回滚不会改写它们——回滚只切换 ACTIVE 版本，源文件再次变更才会产生新版本。
 */
@Entity
@Table(name = "rag_document",
        uniqueConstraints = @UniqueConstraint(name = "uk_rag_document_key", columnNames = {"doc_key"}),
        indexes = {
                @Index(name = "idx_rag_document_user", columnList = "user_id")
        })
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RagDocument {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(length = 200)
    private String title;

    @Column(length = 100)
    private String city;

    @Column(length = 50)
    private String category;

    @Column(columnDefinition = "TEXT")
    private String content;

    @Column(length = 200)
    private String source;

    /**
     * 归属用户：NULL = 公共知识（内置知识库 / 种子数据，所有人可见），
     * 非 NULL = 个人知识（仅归属用户可检索，避免 A 上传的私有文本被 B 搜到）。
     */
    @Column(name = "user_id")
    private Long userId;

    /** knowledge.json 内容指纹（sha256 of title+city+content）：启动同步时比对决定跳过/更新 */
    @Column(length = 64)
    private String fingerprint;

    /**
     * 业务键（batch10 / C）：{@code RagDocumentKeys#of(userId,title,city)}，
     * 即 {@code LEFT(CONCAT(COALESCE(user_id,'public'),'|',title,'|',city),190)}（V11 回填同算法）。
     *
     * <p>{@code uk_rag_document_key} 唯一索引保证「同一归属 + 同标题 + 同城市」只有一行文档；
     * 并发首建时后插入的一方撞键，由 {@code RagDocumentUpsertService} 捕获后回读，
     * 不同步成 500。列在库里是 NOT NULL（V11 收紧），实体侧用 {@code updatable=false}：
     * 标题/城市/归属在文档生命周期里不变，键也就不该被 UPDATE 改写。
     */
    @Column(name = "doc_key", length = 190, nullable = false, updatable = false)
    private String docKey;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @PrePersist
    public void prePersist() {
        if (updatedAt == null) {
            updatedAt = LocalDateTime.now();
        }
        if (docKey == null) {
            // 兜底：所有插入路径（RagDocumentUpsertService / 测试 / 未来的新写入方）都必须有键，
            // 否则 uk_rag_document_key 与 ddl-auto=validate 都会失败
            docKey = RagDocumentKeys.of(userId, title, city);
        }
    }
}