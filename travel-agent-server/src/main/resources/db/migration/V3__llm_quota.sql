-- V3：LLM 每日按用户配额计数（用户名 + 日期唯一，计数原子递增；索引由实体 ddl-auto 补齐）
CREATE TABLE IF NOT EXISTS llm_quota_usage (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_name VARCHAR(64) NOT NULL,
    usage_date DATE NOT NULL,
    used_count INT NOT NULL DEFAULT 0
);
