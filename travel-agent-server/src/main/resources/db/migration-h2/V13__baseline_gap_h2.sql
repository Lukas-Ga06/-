-- ============================================================================
-- V13（H2 专属）：补齐「ddl-auto=update 曾经建出、但 V1..V12 没有任何脚本负责」的
-- 列/表/索引 —— 与 db/migration-mysql/V13__baseline_gap.sql **同一份缺口**，只是换 H2 方言。
--
-- 为什么按方言分成两份、版本号都是 V13：
--   Flyway 只对「当前 profile 的 locations 里同时存在的重复版本号」报错。默认 profile 的
--   locations 是 classpath:db/migration + classpath:db/migration-h2；mysql profile 是
--   classpath:db/migration + classpath:db/migration-mysql。两套各自只看到一个 V13，不冲突。
--   （MySQL 8.0 没有 `ALTER TABLE ... ADD COLUMN IF NOT EXISTS`，只能 information_schema
--    + PREPARE；H2 原生支持 IF NOT EXISTS，所以两边写法不同、语义一致。）
--
-- 为什么需要补（与 MySQL 版同因）：
--   V1 是 sql/init.sql 的等价基线；V4/V5/V7/V9 的 CREATE TABLE IF NOT EXISTS 在「V1 已建表」
--   的全新库上整段跳过、补不了列。ddl-auto 退化为 validate 后 Hibernate 不再补，于是必须由
--   本脚本补：trip.version / trip.departure_reminder_dismissed_at、schedule_item 的重复日程列、
--   invoice 的来源/联动列、trip_photo 表，以及实体 @Index 声明但 V1/V9 未建的索引。
--   存量 H2 开发库上这些对象早已由 ddl-auto=update 建过 → 每条 IF NOT EXISTS 都空转，
--   所以「现有 data/travel-agent.mv.db 平滑升级」不会丢数据、不会报 Duplicate。
--
-- 方言：H2 2.3.232（MODE=MySQL）实测；本文件只被默认 profile 加载，MySQL 不读它。
-- 列/类型/默认值与实体逐字对齐（Trip / ScheduleItem / Invoice / TripPhoto）。
-- ============================================================================

-- ---------------------------------------------------------------------------
-- 1) trip：乐观锁版本 + 出发提醒忽略时间
--    version 默认 0（实体 columnDefinition "bigint default 0"），存量行补列后是 0 不是 NULL
-- ---------------------------------------------------------------------------
ALTER TABLE trip ADD COLUMN IF NOT EXISTS version BIGINT DEFAULT 0;
ALTER TABLE trip ADD COLUMN IF NOT EXISTS departure_reminder_dismissed_at DATETIME;

-- ---------------------------------------------------------------------------
-- 2) schedule_item：重复日程（next_occurrence 在实体上是 @Transient，不是数据库列）
-- ---------------------------------------------------------------------------
ALTER TABLE schedule_item ADD COLUMN IF NOT EXISTS recurrence VARCHAR(10);
ALTER TABLE schedule_item ADD COLUMN IF NOT EXISTS repeat_until DATE;
ALTER TABLE schedule_item ADD COLUMN IF NOT EXISTS reminder_dismissed_at DATETIME;

-- ---------------------------------------------------------------------------
-- 3) invoice：发票→记账联动（init.sql 与 V2–V12 都没有这四列）
-- ---------------------------------------------------------------------------
ALTER TABLE invoice ADD COLUMN IF NOT EXISTS source_file VARCHAR(120);
ALTER TABLE invoice ADD COLUMN IF NOT EXISTS source_type VARCHAR(20);
ALTER TABLE invoice ADD COLUMN IF NOT EXISTS expense_id BIGINT;
ALTER TABLE invoice ADD COLUMN IF NOT EXISTS trip_id BIGINT;

-- ---------------------------------------------------------------------------
-- 4) trip_photo 建表 + 索引（H2 不支持 MySQL 的建表内联 INDEX，所以分开写）
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS trip_photo (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    trip_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    stored_name VARCHAR(120) NOT NULL,
    original_name VARCHAR(200),
    content_type VARCHAR(80),
    size_bytes BIGINT,
    created_at DATETIME
);

CREATE INDEX IF NOT EXISTS idx_trip_photo_trip ON trip_photo (trip_id);
CREATE INDEX IF NOT EXISTS idx_trip_photo_user ON trip_photo (user_id);

-- ---------------------------------------------------------------------------
-- 5) 实体 @Index 声明、但 V1 基线与 V9 都没建出来的索引
--    （同列不同名的重复索引不建：idx_report_user≡idx_daily_report_user 等，与 MySQL 版一致）
-- ---------------------------------------------------------------------------
CREATE INDEX IF NOT EXISTS idx_rag_document_user ON rag_document (user_id);
CREATE INDEX IF NOT EXISTS idx_pending_plan_user ON pending_plan (user_id);
CREATE INDEX IF NOT EXISTS idx_pending_plan_created ON pending_plan (created_at);
CREATE INDEX IF NOT EXISTS idx_llm_quota_date ON llm_quota_usage (usage_date);
CREATE INDEX IF NOT EXISTS idx_conv_created_at ON conversation_message (created_at);
CREATE INDEX IF NOT EXISTS idx_history_trip_status ON operation_history (trip_id, status);