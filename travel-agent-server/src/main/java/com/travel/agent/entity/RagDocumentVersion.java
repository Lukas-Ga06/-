package com.travel.agent.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * 知识库文档的版本快照（batch9 / C：知识库版本化）。
 *
 * <p>不变量：
 * <ol>
 *   <li>同一文档任意时刻至多一条 {@link Status#ACTIVE} 版本（写入路径统一用
 *       {@code RagDocumentVersionRepository} 的批量 UPDATE 置位，见 RagVersionService）；</li>
 *   <li>{@code (document_id, version_no)} 唯一，version_no 从 1 递增；</li>
 *   <li>内容指纹变化 = 旧 ACTIVE 置 INACTIVE + 追加新版本，<b>不删除</b>旧版本正文与知识块；</li>
 *   <li>回滚 = 目标版本置 ACTIVE、其余置 INACTIVE，并把该版本的块重新装载进向量库。</li>
 * </ol>
 *
 * <p>与 {@link RagDocument} 的关系：rag_document 保存"当前正文/指纹"（knowledge.json 同步游标），
 * 本表保存每次变更的正文快照；rag_chunk.version_id 指向本表主键，检索只认"无版本（历史数据）
 * 或 ACTIVE 版本"的块。
 *
 * <p><b>batch10 / C —— 为什么不映射 MySQL 生成列：</b>
 * MySQL 侧由 {@code db/migration-mysql/V12__rag_active_unique.sql} 给本表加了生成列
 * {@code active_document_id = CASE WHEN status='ACTIVE' THEN document_id ELSE NULL END STORED}
 * 与唯一索引 {@code uk_rag_version_active_doc}，把"每文档至多一条 ACTIVE"从应用层约定
 * 下沉为数据库约束（NULL 不参与唯一，所以 INACTIVE 可以有任意多条）。
 * <b>本实体刻意不声明该字段</b>，理由：
 * <ol>
 *   <li>生成列的值由数据库计算，实体一旦映射，Hibernate 会在 INSERT 里写它，
 *       MySQL 直接报 "The value specified for generated column 'active_document_id' is not allowed"；</li>
 *   <li>{@code ddl-auto=validate} 会按实体声明去校验表结构，映射一个"不能写"的列只会制造歧义；</li>
 *   <li>H2 侧没有该列（V12 不加载），映射它会让 H2 环境的 validate 直接失败。</li>
 * </ol>
 * 需要读它时用原生 SQL 查，不要加回实体。
 */
@Entity
@Table(name = "rag_document_version",
        uniqueConstraints = @UniqueConstraint(name = "uk_rag_version",
                columnNames = {"document_id", "version_no"}),
        indexes = @Index(name = "idx_rag_version_active", columnList = "document_id, status"))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RagDocumentVersion {

    /** 版本状态：只认 ACTIVE 的块参与检索 */
    public enum Status {
        ACTIVE,
        INACTIVE
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "document_id", nullable = false)
    private Long documentId;

    /** 同文档内从 1 递增（uk_rag_version 保证唯一） */
    @Column(name = "version_no", nullable = false)
    private Integer versionNo;

    /** 该版本的正文快照（变更时的完整内容，不是增量） */
    @Column(columnDefinition = "LONGTEXT", nullable = false)
    private String content;

    /** 该版本指纹（sha256 of title+city+content）；个人知识库文档可能为 NULL */
    @Column(length = 64)
    private String fingerprint;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 16, nullable = false)
    private Status status;

    /**
     * 复刻文档归属，便于按用户隔离/审计：NULL = 公共知识，非 NULL = 个人知识。
     * 检索时的权限过滤仍以 {@link RagDocument#getUserId()} 为准（本字段不参与鉴权）。
     */
    @Column(name = "user_id")
    private Long userId;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    public boolean isActive() {
        return status == Status.ACTIVE;
    }

    @PrePersist
    public void prePersist() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
        if (status == null) {
            status = Status.INACTIVE;
        }
    }
}