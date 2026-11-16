-- V5：RAG 文档归属用户（个人知识库跨用户隔离，batch B / P0-3）。
-- user_id 为空 = 公共知识（内置 rag/knowledge.json 种子、全站可检索）；
-- 非空 = 个人知识（learning/knowledge/add 上传，仅归属用户可检索）。
--
-- 策略与 V2/V4 一致（脚本自足 + ddl-auto 兜底）：
-- - 全新库：Flyway 先于 Hibernate 执行，此时 rag_document 尚不存在 → 本脚本直接建出含 user_id 的完整表；
-- - 存量库：表已存在 → CREATE TABLE IF NOT EXISTS 跳过，user_id 列由 ddl-auto=update 补齐。
--   MySQL 5.7/8 不支持 `ALTER TABLE ... ADD COLUMN IF NOT EXISTS`，在存量库上写 ALTER 会直接语法失败，
--   所以这里不写 ALTER；索引同样交给实体 @Index（idx_rag_document_user）由 ddl-auto 补齐（理由见 V2）。
CREATE TABLE IF NOT EXISTS rag_document (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    title VARCHAR(200),
    city VARCHAR(100),
    category VARCHAR(50),
    content TEXT,
    source VARCHAR(200),
    user_id BIGINT NULL,
    fingerprint VARCHAR(64),
    updated_at DATETIME
);