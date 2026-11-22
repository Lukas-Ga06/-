-- ============================================================================
-- V13（MySQL 专属）：补齐「ddl-auto=update 曾经建出、但 V1..V12 没有任何脚本负责」的列/表/索引。
-- 必须幂等：存量 MySQL 生产库用 ddl-auto=update，这些列/索引很可能已经存在，
-- 无条件 ADD COLUMN / CREATE INDEX 会抛 Duplicate column / Duplicate key name 并让启动失败。
--
-- 为什么需要本脚本（不是"再补一遍 V1"）：
--   V1 是 sql/init.sql 的等价基线；V4/V5/V7/V9 里那些 CREATE TABLE IF NOT EXISTS 在
--   「V1 已建表」的全新库上会整段跳过，**补不了列**。于是全新库跑完 V1..V12 仍缺：
--     trip.version / trip.departure_reminder_dismissed_at      （V7 只建表、不 ALTER）
--     schedule_item.recurrence / repeat_until / reminder_dismissed_at（V9 同上）
--     invoice.source_file / source_type / expense_id / trip_id （init.sql 与 V2–V12 都没有）
--     trip_photo 整张表                                       （init.sql 与 V2–V12 都没有）
--   ddl-auto 退化为 validate 后 Hibernate 不再补这些对象 → validate 直接报
--   missing table / missing column。本脚本就是那句「只补真正缺的」。
--
-- 方言与执行约束（与 V8/V12 完全一致）：
--   * 用 information_schema 探测 + 会话变量 + PREPARE 动态语句，这是 MySQL 方言；
--     默认 H2 profile 不加载 db/migration-mysql，所以 H2 启动不受影响
--     （H2 侧维持 ddl-auto=update，理由见 application.yml 与交付说明）。
--   * Flyway 把同一迁移的所有语句放在**同一个连接**上按 ';' 顺序执行，
--     会话变量与 PREPARE 句柄在语句之间有效；每条语句内部不含 ';'
--     （字符串字面量里也没有），Flyway 切分不会把语句劈开。
--   * 语法以 MySQL 8.0.46 实测为准（本机版本；8.4 语法差异见交付说明）。
--   * 表不存在时相关分支只会空转（'SELECT 1'），不会误建半套 schema。
-- ============================================================================

-- ---------------------------------------------------------------------------
-- 0) 表存在性探测（trip / schedule_item / invoice 由 V1 基线保证；这里仍先探测，
--    异常库上宁可不补也不报错）
-- ---------------------------------------------------------------------------
SET @has_trip := (SELECT COUNT(*) FROM information_schema.tables
                  WHERE table_schema = DATABASE() AND table_name = 'trip');
SET @has_schedule := (SELECT COUNT(*) FROM information_schema.tables
                      WHERE table_schema = DATABASE() AND table_name = 'schedule_item');
SET @has_invoice := (SELECT COUNT(*) FROM information_schema.tables
                     WHERE table_schema = DATABASE() AND table_name = 'invoice');
SET @has_photo := (SELECT COUNT(*) FROM information_schema.tables
                   WHERE table_schema = DATABASE() AND table_name = 'trip_photo');

-- ---------------------------------------------------------------------------
-- 1) trip：乐观锁版本（V7 引入的 @Version）+ 出发提醒忽略时间
--    version 默认 0：与实体 columnDefinition "bigint default 0" 对齐，
--    存量行补列后立刻是 0（不是 NULL），避免 @Version 读到 null。
-- ---------------------------------------------------------------------------
SET @c := (SELECT COUNT(*) FROM information_schema.columns
           WHERE table_schema = DATABASE() AND table_name = 'trip' AND column_name = 'version');
SET @ddl := IF(@has_trip > 0 AND @c = 0,
    'ALTER TABLE trip ADD COLUMN version BIGINT NULL DEFAULT 0',
    'SELECT 1');
PREPARE s1 FROM @ddl;
EXECUTE s1;
DEALLOCATE PREPARE s1;

SET @c := (SELECT COUNT(*) FROM information_schema.columns
           WHERE table_schema = DATABASE() AND table_name = 'trip'
             AND column_name = 'departure_reminder_dismissed_at');
SET @ddl := IF(@has_trip > 0 AND @c = 0,
    'ALTER TABLE trip ADD COLUMN departure_reminder_dismissed_at DATETIME NULL',
    'SELECT 1');
PREPARE s2 FROM @ddl;
EXECUTE s2;
DEALLOCATE PREPARE s2;

-- ---------------------------------------------------------------------------
-- 2) schedule_item：重复日程（V9 引入的 recurrence/repeat_until/reminder_dismissed_at）
--    注意 next_occurrence 在实体上是 @Transient，**不是**数据库列，这里不补。
-- ---------------------------------------------------------------------------
SET @c := (SELECT COUNT(*) FROM information_schema.columns
           WHERE table_schema = DATABASE() AND table_name = 'schedule_item'
             AND column_name = 'recurrence');
SET @ddl := IF(@has_schedule > 0 AND @c = 0,
    'ALTER TABLE schedule_item ADD COLUMN recurrence VARCHAR(10) NULL',
    'SELECT 1');
PREPARE s3 FROM @ddl;
EXECUTE s3;
DEALLOCATE PREPARE s3;

SET @c := (SELECT COUNT(*) FROM information_schema.columns
           WHERE table_schema = DATABASE() AND table_name = 'schedule_item'
             AND column_name = 'repeat_until');
SET @ddl := IF(@has_schedule > 0 AND @c = 0,
    'ALTER TABLE schedule_item ADD COLUMN repeat_until DATE NULL',
    'SELECT 1');
PREPARE s4 FROM @ddl;
EXECUTE s4;
DEALLOCATE PREPARE s4;

SET @c := (SELECT COUNT(*) FROM information_schema.columns
           WHERE table_schema = DATABASE() AND table_name = 'schedule_item'
             AND column_name = 'reminder_dismissed_at');
SET @ddl := IF(@has_schedule > 0 AND @c = 0,
    'ALTER TABLE schedule_item ADD COLUMN reminder_dismissed_at DATETIME NULL',
    'SELECT 1');
PREPARE s5 FROM @ddl;
EXECUTE s5;
DEALLOCATE PREPARE s5;

-- ---------------------------------------------------------------------------
-- 3) invoice：发票→记账联动（source_file/source_type/expense_id/trip_id）
--    这四列在 init.sql 里没有，也没有任何迁移负责，只有 ddl-auto 时代的库才有。
-- ---------------------------------------------------------------------------
SET @c := (SELECT COUNT(*) FROM information_schema.columns
           WHERE table_schema = DATABASE() AND table_name = 'invoice'
             AND column_name = 'source_file');
SET @ddl := IF(@has_invoice > 0 AND @c = 0,
    'ALTER TABLE invoice ADD COLUMN source_file VARCHAR(120) NULL',
    'SELECT 1');
PREPARE s6 FROM @ddl;
EXECUTE s6;
DEALLOCATE PREPARE s6;

SET @c := (SELECT COUNT(*) FROM information_schema.columns
           WHERE table_schema = DATABASE() AND table_name = 'invoice'
             AND column_name = 'source_type');
SET @ddl := IF(@has_invoice > 0 AND @c = 0,
    'ALTER TABLE invoice ADD COLUMN source_type VARCHAR(20) NULL',
    'SELECT 1');
PREPARE s7 FROM @ddl;
EXECUTE s7;
DEALLOCATE PREPARE s7;

SET @c := (SELECT COUNT(*) FROM information_schema.columns
           WHERE table_schema = DATABASE() AND table_name = 'invoice'
             AND column_name = 'expense_id');
SET @ddl := IF(@has_invoice > 0 AND @c = 0,
    'ALTER TABLE invoice ADD COLUMN expense_id BIGINT NULL',
    'SELECT 1');
PREPARE s8 FROM @ddl;
EXECUTE s8;
DEALLOCATE PREPARE s8;

SET @c := (SELECT COUNT(*) FROM information_schema.columns
           WHERE table_schema = DATABASE() AND table_name = 'invoice'
             AND column_name = 'trip_id');
SET @ddl := IF(@has_invoice > 0 AND @c = 0,
    'ALTER TABLE invoice ADD COLUMN trip_id BIGINT NULL',
    'SELECT 1');
PREPARE s9 FROM @ddl;
EXECUTE s9;
DEALLOCATE PREPARE s9;

-- ---------------------------------------------------------------------------
-- 4) trip_photo 建表（init.sql 与 V2–V12 都没有；只有 ddl-auto 时代建过）
--    列与实体 TripPhoto 逐列对齐；索引名与实体 @Index 一致。
-- ---------------------------------------------------------------------------
SET @ddl := IF(@has_photo = 0,
    'CREATE TABLE trip_photo (id BIGINT NOT NULL AUTO_INCREMENT, trip_id BIGINT NOT NULL, user_id BIGINT NOT NULL, stored_name VARCHAR(120) NOT NULL, original_name VARCHAR(200) NULL, content_type VARCHAR(80) NULL, size_bytes BIGINT NULL, created_at DATETIME NULL, PRIMARY KEY (id), INDEX idx_trip_photo_trip (trip_id), INDEX idx_trip_photo_user (user_id))',
    'SELECT 1');
PREPARE s10 FROM @ddl;
EXECUTE s10;
DEALLOCATE PREPARE s10;

-- ---------------------------------------------------------------------------
-- 5) 索引补齐：实体 @Index 声明、但 V1 基线与 V9 都没建出来的索引
--    （全新库与 ddl-auto 时代对齐；存量库已存在同名索引 → 空转）
--    刻意不建的重复索引：idx_report_user≡idx_daily_report_user、
--    idx_health_user≡idx_health_user_date、idx_item_trip≡idx_itinerary_trip、
--    idx_learning_user≡idx_artifact_user_kind、idx_chunk_doc≡idx_rag_chunk_document
--    （同表同列、只是命名不同，再建一条只会放大写入成本）。
-- ---------------------------------------------------------------------------
SET @i := (SELECT COUNT(*) FROM information_schema.statistics
           WHERE table_schema = DATABASE() AND table_name = 'rag_document'
             AND index_name = 'idx_rag_document_user');
SET @ddl := IF(@i = 0,
    'CREATE INDEX idx_rag_document_user ON rag_document (user_id)',
    'SELECT 1');
PREPARE s11 FROM @ddl;
EXECUTE s11;
DEALLOCATE PREPARE s11;

SET @i := (SELECT COUNT(*) FROM information_schema.statistics
           WHERE table_schema = DATABASE() AND table_name = 'pending_plan'
             AND index_name = 'idx_pending_plan_user');
SET @ddl := IF(@i = 0,
    'CREATE INDEX idx_pending_plan_user ON pending_plan (user_id)',
    'SELECT 1');
PREPARE s12 FROM @ddl;
EXECUTE s12;
DEALLOCATE PREPARE s12;

SET @i := (SELECT COUNT(*) FROM information_schema.statistics
           WHERE table_schema = DATABASE() AND table_name = 'pending_plan'
             AND index_name = 'idx_pending_plan_created');
SET @ddl := IF(@i = 0,
    'CREATE INDEX idx_pending_plan_created ON pending_plan (created_at)',
    'SELECT 1');
PREPARE s13 FROM @ddl;
EXECUTE s13;
DEALLOCATE PREPARE s13;

SET @i := (SELECT COUNT(*) FROM information_schema.statistics
           WHERE table_schema = DATABASE() AND table_name = 'llm_quota_usage'
             AND index_name = 'idx_llm_quota_date');
SET @ddl := IF(@i = 0,
    'CREATE INDEX idx_llm_quota_date ON llm_quota_usage (usage_date)',
    'SELECT 1');
PREPARE s14 FROM @ddl;
EXECUTE s14;
DEALLOCATE PREPARE s14;

SET @i := (SELECT COUNT(*) FROM information_schema.statistics
           WHERE table_schema = DATABASE() AND table_name = 'conversation_message'
             AND index_name = 'idx_conv_created_at');
SET @ddl := IF(@i = 0,
    'CREATE INDEX idx_conv_created_at ON conversation_message (created_at)',
    'SELECT 1');
PREPARE s15 FROM @ddl;
EXECUTE s15;
DEALLOCATE PREPARE s15;

SET @i := (SELECT COUNT(*) FROM information_schema.statistics
           WHERE table_schema = DATABASE() AND table_name = 'operation_history'
             AND index_name = 'idx_history_trip_status');
SET @ddl := IF(@i = 0,
    'CREATE INDEX idx_history_trip_status ON operation_history (trip_id, status)',
    'SELECT 1');
PREPARE s16 FROM @ddl;
EXECUTE s16;
DEALLOCATE PREPARE s16;

-- trip_photo 若已存在（存量库）但缺索引，同样补齐
SET @i := (SELECT COUNT(*) FROM information_schema.statistics
           WHERE table_schema = DATABASE() AND table_name = 'trip_photo'
             AND index_name = 'idx_trip_photo_trip');
SET @ddl := IF(@i = 0,
    'CREATE INDEX idx_trip_photo_trip ON trip_photo (trip_id)',
    'SELECT 1');
PREPARE s17 FROM @ddl;
EXECUTE s17;
DEALLOCATE PREPARE s17;

SET @i := (SELECT COUNT(*) FROM information_schema.statistics
           WHERE table_schema = DATABASE() AND table_name = 'trip_photo'
             AND index_name = 'idx_trip_photo_user');
SET @ddl := IF(@i = 0,
    'CREATE INDEX idx_trip_photo_user ON trip_photo (user_id)',
    'SELECT 1');
PREPARE s18 FROM @ddl;
EXECUTE s18;
DEALLOCATE PREPARE s18;