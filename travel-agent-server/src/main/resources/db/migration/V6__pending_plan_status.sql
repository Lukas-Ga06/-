-- V6：待确认方案的状态与乐观锁版本（确认原子化，batch B / P0-1）。
-- status = 'PENDING'（可确认）/'CONFIRMING'（已被某个请求抢到所有权）。
-- confirm/cancel 用一条条件 UPDATE `SET status='CONFIRMING' WHERE status='PENDING'` 抢所有权，
-- 影响行数 = 1 的请求才允许提交，并发确认同一 plan 不会再重复落库。
--
-- 策略与 V2/V4 一致（脚本自足 + ddl-auto 兜底）：
-- - 全新库：Flyway 先于 Hibernate，pending_plan 尚不存在 → 本脚本建出完整表（含 status/version）；
-- - 存量库：表已存在 → IF NOT EXISTS 跳过，status/version 列由 ddl-auto=update 补齐
--   （MySQL 5.7/8 不支持 ADD COLUMN IF NOT EXISTS，脚本内不写 ALTER）。
--   实体上的 columnDefinition 带 DEFAULT（'PENDING' / 0），保证补列时能给已有行回填、不会因 NOT NULL 失败。
CREATE TABLE IF NOT EXISTS pending_plan (
    plan_id      VARCHAR(64)  NOT NULL,
    user_id      BIGINT       NOT NULL,
    trip_id      BIGINT,
    plan_json    TEXT         NOT NULL,
    preview_json TEXT         NOT NULL,
    plan_revision VARCHAR(64),
    status       VARCHAR(16)  NOT NULL DEFAULT 'PENDING',
    version      BIGINT       DEFAULT 0,
    created_at   DATETIME     NOT NULL,
    PRIMARY KEY (plan_id)
);