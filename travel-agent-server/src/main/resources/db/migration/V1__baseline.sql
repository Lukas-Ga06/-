-- ============================================================================
-- V1：基线（baseline）——「V2 之前」的 schema，等价于旧手工脚本 sql/init.sql。
--
-- 为什么需要它：此前「全新库」要先手工执行 sql/init.sql 再让 Flyway 跑增量，
-- 否则 V2/V4/V6/V7/V9/V10 里那些 CREATE TABLE IF NOT EXISTS 会各自建出「残缺版」
-- 基础表（列不全），实际 schema 只能靠 ddl-auto=update 兜底 —— 即三套来源同时写库。
-- 本脚本把「V2 之前的基础表」一次性建全，让全新库只需要 `flyway migrate`：
--   V1（本文件，基础表）→ V2..V10（增量）→ V11/V12（便携/MySQL 专属）→ V13（MySQL 补齐）
-- 存量库已由 baseline-on-migrate 打在 V1（baseline-version=1），本脚本**不会重跑**。
--
-- 内容边界（重要，改这个文件前先读）：
--   * 只包含 sql/init.sql 里已经存在的基础表/列/索引（users/trip/itinerary_item/
--     operation_history/expense/memo/schedule_item/daily_report/meeting_minutes/
--     mail_item/invoice/health_record/learning_artifact/rag_document/rag_chunk）。
--   * **禁止**把 V2 之后迁移新增的东西挪进来：trip.version、
--     trip.departure_reminder_dismissed_at（V7）、rag_document.user_id（V5）、
--     rag_document.doc_key（V11）、pending_plan.*（V6）、llm_quota_usage.*（V3）、
--     conversation_message.*（V2）、rag_chunk.version_id / rag_document_version（V10）、
--     schedule_item 的重复日程列（V9）、V9 的索引。否则后续迁移会撞列/撞索引。
--     基线里多出来的列不会让 V* 的 CREATE TABLE IF NOT EXISTS 报错，却会让
--     ddl-auto=validate 与生产 schema 出现「V1 建的和 V* 该建的不一致」的分裂。
--   * 与 init.sql 的逐列差异（全部是为了让同一份脚本在 H2(MODE=MySQL) 与 MySQL 8.0
--     都能执行、且与 JPA 实体/validate 对齐，详见交付说明）：
--       - memo.done / schedule_item.done：TINYINT(1) → BOOLEAN
--         （H2 不支持带精度的 TINYINT；MySQL 的 BOOLEAN 就是 TINYINT(1)，无损）
--       - health_record.weight：DECIMAL(5,1) → DOUBLE（实体是 Double，避免 validate 类型不符）
--       - operation_history.before_state/after_state：JSON → TEXT
--         （H2 无 JSON 列语义；实体声明 columnDefinition="TEXT"）
--       - rag_document.fingerprint + rag_document.user_id：init.sql 里没有，但**必须**放在 V1：
--         V4/V5 用的是 CREATE TABLE IF NOT EXISTS（V1 建过表后整段跳过，补不了列），
--         而后续迁移直接依赖这两列：
--           * V10 的存量回填 `SELECT d.fingerprint` —— 基线不带 fingerprint，全新库 V10 必失败；
--           * V11 的 doc_key 去重 `ROW_NUMBER() OVER (PARTITION BY user_id, ...)` ——
--             基线不带 user_id，全新库 V11 报 Unknown column（实测 MySQL 8.0.46）。
--         这两列是契约示例里点名「不要放进 V1」的，但只放 V13 已经来不及（V13 在 V10/V11 之后）。
--         因为是「V1 只在全新库执行、存量库永不重跑」，这么放**不会**影响生产库升级路径。
--         代价：V1 建出的 rag_document 比 init.sql 多两列（已在交付说明列明）。
--       - 索引一律用独立 CREATE INDEX（H2 不支持 MySQL 的建表内联 INDEX 语法）；
--         Flyway 每个库只执行一次本脚本，不需要 IF NOT EXISTS。
--
-- 方言：只用 H2(MODE=MySQL) 与 MySQL 8.0 都支持的写法
-- （BIGINT AUTO_INCREMENT PRIMARY KEY / VARCHAR(n) / TEXT / DECIMAL(p,s) /
--   DATE / TIME / DATETIME / BOOLEAN / DOUBLE / CREATE TABLE IF NOT EXISTS / CREATE INDEX）。
-- ============================================================================

-- ---------------------------------------------------------------------------
-- 用户表（应用使用 users 表名以兼容 H2）
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS users (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    username VARCHAR(100) NOT NULL UNIQUE,
    password VARCHAR(255) NOT NULL,
    nickname VARCHAR(100),
    created_at DATETIME
);

-- 行程表（version 由 V7 增补；本基线保持 V2 之前的列）
CREATE TABLE IF NOT EXISTS trip (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    name VARCHAR(200),
    destination VARCHAR(200),
    start_date DATE,
    end_date DATE,
    budget DECIMAL(10, 2),
    status VARCHAR(30),
    created_at DATETIME
);

-- 行程条目表（tags 含 室内/室外 标记，供天气驱动调整使用）
CREATE TABLE IF NOT EXISTS itinerary_item (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    trip_id BIGINT NOT NULL,
    day_index INT NOT NULL,
    item_type VARCHAR(30) NOT NULL,
    title VARCHAR(200) NOT NULL,
    start_time DATETIME,
    end_time DATETIME,
    latitude DOUBLE,
    longitude DOUBLE,
    cost DECIMAL(10, 2),
    tags VARCHAR(200),
    description TEXT
);

-- 操作历史表（AI 撤销的核心：before/after 快照）
CREATE TABLE IF NOT EXISTS operation_history (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    trip_id BIGINT NOT NULL,
    user_id BIGINT,
    operation_type VARCHAR(50),
    before_state TEXT,
    after_state TEXT,
    agent_reason TEXT,
    status VARCHAR(20) DEFAULT 'ACTIVE',
    created_at DATETIME
);

-- 个人记账表（行程实际支出）
CREATE TABLE IF NOT EXISTS expense (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    trip_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    category VARCHAR(30) NOT NULL,
    amount DECIMAL(10, 2) NOT NULL,
    note VARCHAR(200),
    spent_at DATE NOT NULL,
    payment_method VARCHAR(20) DEFAULT '现金',
    created_at DATETIME
);

-- 个人备忘/待办表
CREATE TABLE IF NOT EXISTS memo (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    content TEXT NOT NULL,
    done BOOLEAN NOT NULL DEFAULT FALSE,
    created_at DATETIME
);

-- 日程表（办公自动化；重复日程相关列由 V9 / V13 增补）
CREATE TABLE IF NOT EXISTS schedule_item (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    title VARCHAR(200) NOT NULL,
    sched_date DATE NOT NULL,
    sched_time TIME,
    note VARCHAR(300),
    done BOOLEAN NOT NULL DEFAULT FALSE,
    created_at DATETIME
);

-- 日报表
CREATE TABLE IF NOT EXISTS daily_report (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    report_date DATE NOT NULL,
    content TEXT NOT NULL,
    created_at DATETIME
);

-- 会议纪要表
CREATE TABLE IF NOT EXISTS meeting_minutes (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    title VARCHAR(200) NOT NULL,
    raw_text TEXT,
    content TEXT,
    created_at DATETIME
);

-- 邮件分类表
CREATE TABLE IF NOT EXISTS mail_item (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    sender VARCHAR(200),
    subject VARCHAR(300),
    body TEXT,
    category VARCHAR(20),
    summary TEXT,
    suggested_reply TEXT,
    created_at DATETIME
);

-- 发票表（source_file/source_type/expense_id/trip_id 由 V13 补齐：
-- 它们是 ddl-auto 演进出来的列，init.sql 与 V2–V12 都没有，放在 V13 统一补最安全）
CREATE TABLE IF NOT EXISTS invoice (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    title VARCHAR(200),
    vendor VARCHAR(200),
    category VARCHAR(20),
    amount DECIMAL(10, 2),
    invoice_date DATE,
    raw_text TEXT,
    created_at DATETIME
);

-- 健康日报表（小米手环等设备数据）
CREATE TABLE IF NOT EXISTS health_record (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    record_date DATE NOT NULL,
    steps INT,
    sleep_hours DOUBLE,
    resting_heart_rate INT,
    weight DOUBLE,
    created_at DATETIME
);

-- 学习产物表（知识框架/学习路线/重点归纳）
CREATE TABLE IF NOT EXISTS learning_artifact (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    kind VARCHAR(20) NOT NULL,
    topic VARCHAR(200) NOT NULL,
    content TEXT NOT NULL,
    created_at DATETIME
);

-- RAG 文档表（user_id / fingerprint 必须在基线里，原因见文件头；
-- doc_key 由 V11 增补，不在这里出现）
CREATE TABLE IF NOT EXISTS rag_document (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    title VARCHAR(200),
    city VARCHAR(100),
    category VARCHAR(50),
    content TEXT,
    source VARCHAR(200),
    user_id BIGINT,
    fingerprint VARCHAR(64),
    updated_at DATETIME
);

-- RAG 知识块表（embedding 以 JSON 数组存储；version_id 由 V10 增补）
CREATE TABLE IF NOT EXISTS rag_chunk (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    document_id BIGINT NOT NULL,
    content TEXT,
    embedding TEXT,
    dim INT
);

-- ---------------------------------------------------------------------------
-- init.sql 里已有的索引（独立语句：H2 不支持 MySQL 的建表内联 INDEX）
-- 命名与 V9 的 idx_<table>_<cols> 系列不冲突；列上都是 init.sql 原有的访问路径。
-- ---------------------------------------------------------------------------
CREATE INDEX idx_trip_user ON trip (user_id);
CREATE INDEX idx_item_trip ON itinerary_item (trip_id);
CREATE INDEX idx_history_trip ON operation_history (trip_id);
CREATE INDEX idx_expense_trip ON expense (trip_id);
CREATE INDEX idx_expense_user ON expense (user_id);
CREATE INDEX idx_memo_user ON memo (user_id);
CREATE INDEX idx_schedule_user ON schedule_item (user_id, sched_date);
CREATE INDEX idx_report_user ON daily_report (user_id, report_date);
CREATE INDEX idx_minutes_user ON meeting_minutes (user_id);
CREATE INDEX idx_mail_user ON mail_item (user_id);
CREATE INDEX idx_invoice_user ON invoice (user_id);
CREATE INDEX idx_health_user ON health_record (user_id, record_date);
CREATE INDEX idx_learning_user ON learning_artifact (user_id, kind);
CREATE INDEX idx_chunk_doc ON rag_chunk (document_id);