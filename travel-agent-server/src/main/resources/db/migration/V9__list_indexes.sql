-- V9：列表查询索引（配合分页/聚合下推后新增的访问路径）。
--
-- 策略与 V2/V4/V6/V7 完全一致（**脚本自足 + ddl-auto 兜底**）：
-- - 全新库：Flyway 先于 Hibernate，expense/memo/health_record/operation_history/schedule_item
--   这些基础表还不存在 → 本脚本用 IF NOT EXISTS 建出完整表，再建索引；
-- - 存量库（生产 MySQL 8.4）：表已存在 → 建表语句跳过；索引由本脚本补齐，
--   其余列/索引由 ddl-auto=update 兜底。
-- 表定义与实体 @Column 逐一对照（列名、长度、精度、可空性），不要在这里写实体里没有的列。
--
-- 命名与冲突（为什么不会 "Duplicate key name"）：
-- 索引名一律 idx_<table>_<cols>，与实体 @Index 已有的名字（idx_expense_user、idx_expense_trip、
-- idx_memo_user、idx_health_user_date、idx_schedule_user、idx_conv_user_session、
-- idx_history_trip_status …）**全部不同名**；复合索引在列上是既有单列索引的前缀扩展
-- （等值列在前、排序/范围列在后），覆盖 user_id=? ORDER BY spent_at / record_date 这类访问路径。
-- 刻意**不建** health_record(user_id, record_date)：实体已有 idx_health_user_date 完全覆盖同两列，
-- 再建一条只是给写入放大成本。
--
-- MySQL 8.4 兼容性：MySQL 不支持 CREATE INDEX IF NOT EXISTS（MariaDB 才有），
-- Flyway 每个版本只执行一次，无需重复执行保护；若某库已由运维手工建过同名索引，
-- 需先删除或改名再执行（报 Duplicate key name 属"无需变更"而非数据错误）。

-- 被索引但历史迁移脚本未建出的基础表：仅为全新库自足（存量库自动跳过）
CREATE TABLE IF NOT EXISTS expense (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    trip_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    category VARCHAR(30) NOT NULL,
    amount DECIMAL(10, 2) NOT NULL,
    note VARCHAR(200),
    spent_at DATE NOT NULL,
    payment_method VARCHAR(20),
    created_at DATETIME
);

CREATE TABLE IF NOT EXISTS memo (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    content TEXT NOT NULL,
    done BOOLEAN NOT NULL DEFAULT FALSE,
    created_at DATETIME
);

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

CREATE TABLE IF NOT EXISTS operation_history (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    trip_id BIGINT NOT NULL,
    user_id BIGINT,
    operation_type VARCHAR(50),
    before_state TEXT,
    after_state TEXT,
    agent_reason TEXT,
    status VARCHAR(20),
    created_at DATETIME
);

CREATE TABLE IF NOT EXISTS schedule_item (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    title VARCHAR(200) NOT NULL,
    sched_date DATE NOT NULL,
    sched_time TIME,
    note VARCHAR(300),
    done BOOLEAN NOT NULL DEFAULT FALSE,
    recurrence VARCHAR(10),
    repeat_until DATE,
    next_occurrence DATE,
    reminder_dismissed_at DATETIME,
    created_at DATETIME
);

-- 账单：总览按 user_id + spent_at 排序/区间 SUM；行程账单按 trip_id + spent_at 排序
CREATE INDEX idx_expense_user_spent_at ON expense (user_id, spent_at);
CREATE INDEX idx_expense_trip_spent_at ON expense (trip_id, spent_at);

-- 备忘：COUNT(user_id, done) 算待办数；列表 = 该用户下未完成在前 + 时间倒序
CREATE INDEX idx_memo_user_done ON memo (user_id, done);

-- 行程：列表按 user_id 过滤、start_date 排序（含足迹/出发提醒取最近一条）
CREATE INDEX idx_trip_user_start_date ON trip (user_id, start_date);

-- 日程：提醒板取该用户全部日程，时间窗口在应用层算
CREATE INDEX idx_schedule_item_user_sched_date ON schedule_item (user_id, sched_date);

-- 会话记忆：recent() 的 (user_id, conversation_id) 过滤 + created_at 倒序取前 N 条
CREATE INDEX idx_conversation_message_user_conv_created
    ON conversation_message (user_id, conversation_id, created_at);

-- 操作历史：撤销链按行程取最近一条
CREATE INDEX idx_operation_history_trip_created ON operation_history (trip_id, created_at);