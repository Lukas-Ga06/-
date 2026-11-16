-- ============================================================================
-- V10：RAG 知识库版本化（batch9 / C）
--
-- 目标数据模型（docs/proposals/batch9-contract.md §2）：
--   rag_document_version(document_id, version_no, content, fingerprint, status, user_id, created_at)
--     - 唯一键 uk_rag_version (document_id, version_no)：同文档内版本号唯一
--     - 索引   idx_rag_version_active (document_id, status)：按"当前 ACTIVE 版本"查
--   rag_chunk.version_id BIGINT NULL → rag_document_version.id，索引 idx_rag_chunk_version
--
-- 不变量：
--   1) 同一文档任意时刻至多一条 status='ACTIVE' 版本（写入路径批量置位 + 文档行锁，见 RagVersionService）；
--   2) 内容指纹变化 → 旧 ACTIVE 置 INACTIVE、追加新版本，旧正文与旧知识块保留不删；
--   3) 检索只装载「chunk.version_id 为空的历史块」或「所属版本为 ACTIVE 的块」。
--
-- 方言与执行顺序说明（与 V2/V4/V5/V7 同一策略：脚本自足 + ddl-auto 兜底）：
--   * 全新库：Flyway 先于 Hibernate 执行，此时业务表尚不存在 → 本脚本自己建出 rag_chunk
--     （存量库上 CREATE TABLE IF NOT EXISTS 直接跳过）；
--   * MySQL 8.4 不支持 `ALTER TABLE ... ADD COLUMN IF NOT EXISTS`（那是 MariaDB 扩展），
--     所以这里用"先保证表存在、再无条件补列"的顺序，全新库/存量库都能一次通过；
--   * 本脚本每个库只执行一次（Flyway 语义）；"重跑是否幂等"属于运营判断，不在脚本内做兜底。
-- ============================================================================

-- ---------------------------------------------------------------------------
-- 1) rag_chunk 自足建表（全新库需要；存量库跳过），随后补 version_id 列与索引
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS rag_chunk (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    document_id BIGINT NOT NULL,
    content TEXT,
    embedding TEXT,
    dim INT
);

ALTER TABLE rag_chunk ADD COLUMN version_id BIGINT NULL;

CREATE INDEX idx_rag_chunk_version ON rag_chunk (version_id);

-- ---------------------------------------------------------------------------
-- 2) 版本表：正文快照 + 状态机
--    content 用 LONGTEXT：单篇正文上限 20000 字符（UTF-8 最多约 60KB），TEXT(64KB) 边界太紧
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS rag_document_version (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    document_id BIGINT NOT NULL,
    version_no INT NOT NULL,
    content LONGTEXT NOT NULL,
    fingerprint VARCHAR(64),
    status VARCHAR(16) NOT NULL,
    user_id BIGINT NULL,
    created_at TIMESTAMP NOT NULL,
    CONSTRAINT uk_rag_version UNIQUE (document_id, version_no)
);

CREATE INDEX idx_rag_version_active ON rag_document_version (document_id, status);

-- ---------------------------------------------------------------------------
-- 3) 存量回填 A：每篇已有 rag_document 补一条 version_no=1 / status='ACTIVE' 的版本快照
--
--    * 正文/指纹沿用文档当前值；created_at 用文档 updated_at（没有就取当前时间）；
--    * user_id 先写 NULL：全新库上 rag_document 由 V4 建出，此时还没有 user_id 列
--      （V5 的 CREATE TABLE IF NOT EXISTS 不会给已存在的表补列，user_id 是 ddl-auto 后补的），
--      在 SQL 里引用 user_id 会让全新库首次启动直接失败。归属由启动时的
--      RagVersionService#backfillLegacyVersions 用文档 user_id 补齐（幂等）。
--    * 用 LEFT JOIN + IS NULL 而不是 NOT EXISTS 子查询：MySQL 不允许"插入目标表同时在子查询里
--      读目标表"，FROM 子句里 join 是允许的；H2 同样支持。
-- ---------------------------------------------------------------------------
INSERT INTO rag_document_version (document_id, version_no, content, fingerprint, status, user_id, created_at)
SELECT d.id,
       1,
       COALESCE(d.content, ''),
       d.fingerprint,
       'ACTIVE',
       NULL,
       COALESCE(d.updated_at, CURRENT_TIMESTAMP)
FROM rag_document d
LEFT JOIN rag_document_version v
       ON v.document_id = d.id AND v.version_no = 1
WHERE v.id IS NULL;

-- ---------------------------------------------------------------------------
-- 4) 存量回填 B：把文档现有知识块的 version_id 指向刚建的 v1
--    （相关子查询写法，MySQL 与 H2 都支持；MySQL 的 UPDATE ... JOIN 语法 H2 不认）
-- ---------------------------------------------------------------------------
UPDATE rag_chunk
SET version_id = (
        SELECT v.id
        FROM rag_document_version v
        WHERE v.document_id = rag_chunk.document_id
          AND v.version_no = 1
    )
WHERE version_id IS NULL
  AND EXISTS (
        SELECT 1
        FROM rag_document_version v
        WHERE v.document_id = rag_chunk.document_id
          AND v.version_no = 1
    );