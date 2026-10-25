-- V2：会话记忆表（conversation_message）。
-- 存量库由 Flyway baseline-on-migrate 打基线后执行本脚本（此前没有这张表）；
-- 全新库由本脚本直接建出。索引不在脚本里建——MySQL 不支持 CREATE INDEX IF NOT EXISTS，
-- 而实体 @Index 会让 Hibernate 的 ddl-auto 把索引补齐，两边方言都能安全通过。
CREATE TABLE IF NOT EXISTS conversation_message (
    id              BIGINT       NOT NULL AUTO_INCREMENT,
    user_id         BIGINT       NOT NULL,
    conversation_id VARCHAR(64)  NOT NULL,
    role            VARCHAR(16)  NOT NULL,
    content         TEXT         NOT NULL,
    intent          VARCHAR(32),
    created_at      TIMESTAMP,
    PRIMARY KEY (id)
);
