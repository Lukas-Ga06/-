-- ============================================================================
-- V12（MySQL 专属）：rag_document_version 至多一条 ACTIVE —— 生成列 + 唯一索引（幂等）
--
-- 契约：docs/proposals/batch10-contract.md §1（V12 归 C / 知识库不变量）、§3.1。
--
-- 为什么放在 db/migration-mysql：
--   本脚本用到 information_schema 探测、会话变量、PREPARE 动态语句与 STORED 生成列，
--   全是 MySQL 方言；默认 H2 profile 只加载 db/migration，不读本目录（application-mysql.yml
--   才把 classpath:db/migration-mysql 追加进 flyway.locations），所以 H2 启动不受影响。
--   H2 侧不存在该 DB 约束：不变量 1 由应用层保证（RagVersionService 用文档行
--   SELECT ... FOR UPDATE + 批量置 INACTIVE + 同事务插入 ACTIVE），
--   并由 RagVersioningTest#concurrentAppendsLeaveExactlyOneActiveVersion 并发断言。
--
-- 为什么必须幂等（与 V8 同一理由）：
--   存量 MySQL 生产库用 ddl-auto=update，Hibernate 可能已经按实体建过列/索引；
--   无条件 ADD COLUMN / CREATE INDEX 会抛 Duplicate column / Duplicate key name，
--   让整个应用启动失败。所以先查 information_schema，已存在就编译成 'SELECT 1' 空转。
--     * 表不存在（异常库）   → 三个分支全部空转（正常顺序下 V10 已建出该表）；
--     * 生成列不存在         → 加 STORED 生成列；
--     * 唯一索引不存在       → 先去重，再建唯一索引。
--   去重只在"要建索引"时执行：唯一索引已存在时表里不可能有并列 ACTIVE。
--
-- 生成列语义：
--   active_document_id = CASE WHEN status='ACTIVE' THEN document_id ELSE NULL END (STORED)
--   MySQL 的唯一索引允许多个 NULL，所以同一文档只可能有一行 active_document_id 非 NULL，
--   即**至多一条 ACTIVE**；INACTIVE 行该列为 NULL，可以有任意多条。
--
-- 实体侧决策：RagDocumentVersion **不映射** active_document_id。
--   生成列由数据库计算，若映射进实体，Hibernate 会在 INSERT 时把 NULL 写进生成列
--   （MySQL 报 "The value specified for generated column ... is not allowed"），
--   且 ddl-auto=validate 会要求表里有"普通列"，与生成列语义冲突。
--   → 只在 SQL 里存在，实体完全不声明（见 RagDocumentVersion 类注释）。
--
-- 去重语义（历史脏数据）：同一 document_id 有多条 ACTIVE 时，保留 version_no 最大的一条，
--   其余置 INACTIVE。只改状态，不删除任何版本正文或知识块；被降级的版本仍可回滚。
--
-- 实现约束（与 V8 一致）：Flyway 把同一迁移的所有语句放在**同一个连接**上按 ';' 顺序执行，
--   因此会话变量与 PREPARE 句柄在语句之间有效；每条语句内部不含 ';'（字符串字面量里也没有），
--   Flyway 的切分不会把语句劈开。语法以 MySQL 8.0.46（本机实测版本）为准。
-- ============================================================================

-- ---------------------------------------------------------------------------
-- 1) 探测：表 / 生成列 / 唯一索引是否已存在（同一连接内共享）
-- ---------------------------------------------------------------------------
SET @tbl_exists := (
    SELECT COUNT(*) FROM information_schema.tables
    WHERE table_schema = DATABASE()
      AND table_name = 'rag_document_version');

SET @col_exists := (
    SELECT COUNT(*) FROM information_schema.columns
    WHERE table_schema = DATABASE()
      AND table_name = 'rag_document_version'
      AND column_name = 'active_document_id');

SET @idx_exists := (
    SELECT COUNT(*) FROM information_schema.statistics
    WHERE table_schema = DATABASE()
      AND table_name = 'rag_document_version'
      AND index_name = 'uk_rag_version_active_doc');

-- ---------------------------------------------------------------------------
-- 2) 去重：同文档多条 ACTIVE → 只留 version_no 最大的一条，其余置 INACTIVE
--    （只在需要新建唯一索引时执行；派生表包一层以规避 MySQL "不能在子查询里读被更新表"）
-- ---------------------------------------------------------------------------
SET @dedupe := IF(@tbl_exists > 0 AND @idx_exists = 0,
    'UPDATE rag_document_version v JOIN (SELECT document_id, MAX(version_no) AS keep_no FROM rag_document_version WHERE status = ''ACTIVE'' GROUP BY document_id HAVING COUNT(*) > 1) keep ON keep.document_id = v.document_id SET v.status = ''INACTIVE'' WHERE v.status = ''ACTIVE'' AND v.version_no <> keep.keep_no',
    'SELECT 1');
PREPARE dedupe_stmt FROM @dedupe;
EXECUTE dedupe_stmt;
DEALLOCATE PREPARE dedupe_stmt;

-- ---------------------------------------------------------------------------
-- 3) 生成列（STORED）：ACTIVE 行带 document_id，其余为 NULL
-- ---------------------------------------------------------------------------
SET @add_col := IF(@tbl_exists > 0 AND @col_exists = 0,
    'ALTER TABLE rag_document_version ADD COLUMN active_document_id BIGINT GENERATED ALWAYS AS (CASE WHEN status = ''ACTIVE'' THEN document_id ELSE NULL END) STORED',
    'SELECT 1');
PREPARE add_col_stmt FROM @add_col;
EXECUTE add_col_stmt;
DEALLOCATE PREPARE add_col_stmt;

-- ---------------------------------------------------------------------------
-- 4) 唯一索引：NULL 不参与唯一 → INACTIVE 任意多条，ACTIVE 每文档至多一条
-- ---------------------------------------------------------------------------
SET @add_uk := IF(@tbl_exists > 0 AND @idx_exists = 0,
    'CREATE UNIQUE INDEX uk_rag_version_active_doc ON rag_document_version (active_document_id)',
    'SELECT 1');
PREPARE add_uk_stmt FROM @add_uk;
EXECUTE add_uk_stmt;
DEALLOCATE PREPARE add_uk_stmt;