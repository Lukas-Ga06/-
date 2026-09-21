-- ============================================================
-- 多模态智能旅行 Agent —— MySQL 初始化脚本
-- 默认 H2 模式由 JPA ddl-auto 自动建表，无需执行本文件；
-- 切换 MySQL（--spring.profiles.active=mysql）时先执行本脚本建库。
-- ============================================================

CREATE DATABASE IF NOT EXISTS travel_agent
    DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
USE travel_agent;

-- 用户表（应用使用 users 表名以兼容 H2）
CREATE TABLE IF NOT EXISTS users (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    username VARCHAR(100) NOT NULL UNIQUE,
    password VARCHAR(255) NOT NULL,
    nickname VARCHAR(100),
    created_at DATETIME
);

-- 行程表
CREATE TABLE IF NOT EXISTS trip (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    name VARCHAR(200),
    destination VARCHAR(200),
    start_date DATE,
    end_date DATE,
    budget DECIMAL(10, 2),
    status VARCHAR(30),
    created_at DATETIME,
    INDEX idx_trip_user (user_id)
);

-- 行程条目表（tags 含 室内/室外 标记，供天气驱动调整使用）
CREATE TABLE IF NOT EXISTS itinerary_item (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
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
    description TEXT,
    INDEX idx_item_trip (trip_id)
);

-- 操作历史表（AI 撤销的核心：before/after 快照）
CREATE TABLE IF NOT EXISTS operation_history (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    trip_id BIGINT NOT NULL,
    user_id BIGINT,
    operation_type VARCHAR(50),
    before_state JSON,
    after_state JSON,
    agent_reason TEXT,
    status VARCHAR(20) DEFAULT 'ACTIVE',
    created_at DATETIME,
    INDEX idx_history_trip (trip_id)
);

-- 个人记账表（行程实际支出）
CREATE TABLE IF NOT EXISTS expense (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    trip_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    category VARCHAR(30) NOT NULL,
    amount DECIMAL(10, 2) NOT NULL,
    note VARCHAR(200),
    spent_at DATE NOT NULL,
    payment_method VARCHAR(20) DEFAULT '现金',
    created_at DATETIME,
    INDEX idx_expense_trip (trip_id),
    INDEX idx_expense_user (user_id)
);

-- 个人备忘/待办表
CREATE TABLE IF NOT EXISTS memo (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    content TEXT NOT NULL,
    done TINYINT(1) NOT NULL DEFAULT 0,
    created_at DATETIME,
    INDEX idx_memo_user (user_id)
);

-- 日程表（办公自动化）
CREATE TABLE IF NOT EXISTS schedule_item (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    title VARCHAR(200) NOT NULL,
    sched_date DATE NOT NULL,
    sched_time TIME,
    note VARCHAR(300),
    done TINYINT(1) NOT NULL DEFAULT 0,
    created_at DATETIME,
    INDEX idx_schedule_user (user_id, sched_date)
);

-- 日报表
CREATE TABLE IF NOT EXISTS daily_report (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    report_date DATE NOT NULL,
    content TEXT NOT NULL,
    created_at DATETIME,
    INDEX idx_report_user (user_id, report_date)
);

-- 会议纪要表
CREATE TABLE IF NOT EXISTS meeting_minutes (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    title VARCHAR(200) NOT NULL,
    raw_text TEXT,
    content TEXT,
    created_at DATETIME,
    INDEX idx_minutes_user (user_id)
);

-- 邮件分类表
CREATE TABLE IF NOT EXISTS mail_item (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    sender VARCHAR(200),
    subject VARCHAR(300),
    body TEXT,
    category VARCHAR(20),
    summary TEXT,
    suggested_reply TEXT,
    created_at DATETIME,
    INDEX idx_mail_user (user_id)
);

-- 发票表
CREATE TABLE IF NOT EXISTS invoice (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    title VARCHAR(200),
    vendor VARCHAR(200),
    category VARCHAR(20),
    amount DECIMAL(10, 2),
    invoice_date DATE,
    raw_text TEXT,
    created_at DATETIME,
    INDEX idx_invoice_user (user_id)
);

-- 健康日报表（小米手环等设备数据）
CREATE TABLE IF NOT EXISTS health_record (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    record_date DATE NOT NULL,
    steps INT,
    sleep_hours DOUBLE,
    resting_heart_rate INT,
    weight DECIMAL(5, 1),
    created_at DATETIME,
    INDEX idx_health_user (user_id, record_date)
);

-- 学习产物表（知识框架/学习路线/重点归纳）
CREATE TABLE IF NOT EXISTS learning_artifact (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    kind VARCHAR(20) NOT NULL,
    topic VARCHAR(200) NOT NULL,
    content TEXT NOT NULL,
    created_at DATETIME,
    INDEX idx_learning_user (user_id, kind)
);

-- RAG 文档表
CREATE TABLE IF NOT EXISTS rag_document (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    title VARCHAR(200),
    city VARCHAR(100),
    category VARCHAR(50),
    content TEXT,
    source VARCHAR(200),
    updated_at DATETIME
);

-- RAG 知识块表（embedding 以 JSON 数组存储；生产可切换 Pgvector 的 vector 类型）
CREATE TABLE IF NOT EXISTS rag_chunk (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    document_id BIGINT NOT NULL,
    content TEXT,
    embedding TEXT,
    dim INT,
    INDEX idx_chunk_doc (document_id)
);

-- 演示账号由应用启动时自动创建：demo / demo123
