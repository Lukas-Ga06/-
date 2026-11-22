-- V7：行程乐观锁版本（提交/撤销一致性，batch B / P0-4）。
-- Trip.version 由 Hibernate @Version 维护：并发修改同一行程时后提交的事务拿到版本冲突，
-- 统一由 GlobalExceptionHandler 转成 409「数据已被其他操作修改，请刷新后重试」，
-- 而不是静默覆盖（例如两个标签页同时改日期 / 同时提交 Agent 方案）。
--
-- 策略与 V2/V4/V6 一致（脚本自足 + ddl-auto 兜底）：
-- - 全新库：Flyway 先于 Hibernate，trip 尚不存在 → 本脚本建出完整表（含 version）；
-- - 存量库：表已存在 → IF NOT EXISTS 跳过，version 列由 ddl-auto=update 补齐
--   （MySQL 5.7/8 不支持 ADD COLUMN IF NOT EXISTS，脚本内不写 ALTER）。
--   实体列定义带 DEFAULT 0，补列时会给已有行回填，避免 NOT NULL 加列失败。
CREATE TABLE IF NOT EXISTS trip (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    name VARCHAR(200),
    destination VARCHAR(200),
    start_date DATE,
    end_date DATE,
    budget DECIMAL(10, 2),
    status VARCHAR(30),
    departure_reminder_dismissed_at DATETIME,
    version BIGINT DEFAULT 0,
    created_at DATETIME
);