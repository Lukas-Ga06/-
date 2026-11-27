-- ============================================================================
-- V31：操作历史支持「日程域」撤销（批量清空日程可撤销）
--
-- 背景：批量清空日程是确认门里的破坏性操作，必须可撤销；但 operation_history.trip_id
-- 一直是 NOT NULL，而日程（schedule_item）并不属于任何行程。
--
-- 为什么放开可空：trip_id 为空 + domain='SCHEDULE' = 按用户撤销的日程历史；
-- trip_id 非空 = 既有行程历史。不新增表：撤销链与状态机（ACTIVE/UNDONE）直接复用。
--
-- domain 默认 ITINERARY：存量行全部是行程历史，迁移即回填，读写两侧都不用猜。
--
-- idx_history_user_domain：撤销取「该用户最近一条 ACTIVE 的 SCHEDULE 历史」，
-- 复合索引让这条查询不必扫描全部历史；行程侧仍用既有 idx_history_trip_status。
--
-- 方言：H2(MODE=MySQL) 与 MySQL 8.0 共用同一份脚本（与 V30 同一写法）；
-- MySQL 8.0.46 已实测（临时库建表 → 执行本脚本 → 校验可空/默认值/索引 → DROP）。
-- 不加 IF NOT EXISTS（MySQL 8.0 的 ALTER ADD COLUMN / CREATE INDEX 不支持）；
-- Flyway 保证每个库只跑一次。
-- ============================================================================

ALTER TABLE operation_history MODIFY COLUMN trip_id BIGINT NULL;

ALTER TABLE operation_history ADD COLUMN domain VARCHAR(20) DEFAULT 'ITINERARY';

CREATE INDEX idx_history_user_domain ON operation_history (user_id, domain, status);