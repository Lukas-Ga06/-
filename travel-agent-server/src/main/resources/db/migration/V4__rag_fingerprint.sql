-- V4：RAG 知识库指纹（sha256 of title+city+content）。
-- 全新库：直接建出含 fingerprint 的完整表（Flyway 先于 Hibernate 执行，此时表尚不存在）；
-- 存量库：表已存在 → IF NOT EXISTS 跳过，fingerprint 列由 ddl-auto=update 补齐
-- （与 V2 的"自建表 + ddl-auto 兜底"策略一致）。
CREATE TABLE IF NOT EXISTS rag_document (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    title VARCHAR(200),
    city VARCHAR(100),
    category VARCHAR(50),
    content TEXT,
    source VARCHAR(200),
    fingerprint VARCHAR(64),
    updated_at DATETIME
);
