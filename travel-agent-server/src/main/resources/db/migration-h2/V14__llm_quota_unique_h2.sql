-- ============================================================================
-- V14（H2 专属）：为 llm_quota_usage 补 (user_name, usage_date) 唯一键。
--
-- 为什么需要它（第十批文档复核时发现的真实缺口）：
--   同一约束在 MySQL 侧由 `db/migration-mysql/V8__llm_quota_unique.sql` 负责，但 V8 用了
--   information_schema + PREPARE（MySQL 方言），默认 H2 profile **不加载该目录**，
--   于是 H2 库上从来就没有这个唯一键：
--     * LlmQuotaUsageRepository.insertIfAbsent 的 `ON DUPLICATE KEY UPDATE` 无键可撞
--       → 并发首次使用时可能插入多行（放行判定仍只依赖条件 UPDATE 的 rowcount，
--         不会超额放行，但行会重复；查询侧 findOptional 可能抛 NonUniqueResultException）；
--   H2 有 `ADD CONSTRAINT IF NOT EXISTS`，所以本脚本天然幂等，放这里比改 V13 更安全
--   （V13 已被本地开发库应用过，改动它会再触发一次 checksum repair）。
--
-- 方言：H2 2.3.232，实测通过（见 CHANGELOG 第十批「验证」）。
-- 顺序：先去重（同一 user+date 只保留 used_count 最大的一行，并列时保留 id 最大者），再建约束——
--       若历史库里已有重复行，直接加唯一键会失败。
-- ============================================================================

DELETE FROM llm_quota_usage
WHERE id IN (
    SELECT victims.id FROM (
        SELECT stale.id AS id
        FROM llm_quota_usage stale
        JOIN llm_quota_usage keep
          ON stale.user_name = keep.user_name
         AND stale.usage_date = keep.usage_date
         AND (stale.used_count < keep.used_count
              OR (stale.used_count = keep.used_count AND stale.id < keep.id))
    ) victims
);

ALTER TABLE llm_quota_usage
    ADD CONSTRAINT IF NOT EXISTS uk_llm_quota_user_date UNIQUE (user_name, usage_date);