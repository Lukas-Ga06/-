-- ============================================================================
-- V19：旅行闭环数据模型（P1）
--
-- 三件事：
--   1. itinerary_item 增加执行状态：PENDING / IN_PROGRESS / COMPLETED / SKIPPED，
--      以及 started_at / completed_at / visited_at。行中调整只允许改「未开始」条目，
--      行后回顾与足迹只把 COMPLETED（或已标记 visited）的条目算作「去过」。
--   2. 新增 trip_event：天气突变、拥堵、闭馆、用户打卡、Agent 调整的可审计事件流。
--   3. schedule_item / memo 增加可空旅行关联：trip_id / itinerary_item_id / source / travel_day。
--
-- 方言：只用 H2(MODE=MySQL) 与 MySQL 8.0 都支持的写法
-- （ALTER TABLE ADD COLUMN ... DEFAULT / CREATE TABLE / CREATE INDEX / BIGINT / DATETIME / TEXT）。
-- 全新库：V1 建基础表 → 本脚本补列与新表；存量库：Flyway 跳过已应用版本，只执行本脚本。
-- 旧办公/学习/健康表不在本脚本删除：先完成代码下线，数据退役由后续 forward-only 迁移按环境决定。
-- ============================================================================

ALTER TABLE itinerary_item ADD COLUMN status VARCHAR(20) NOT NULL DEFAULT 'PENDING';
ALTER TABLE itinerary_item ADD COLUMN started_at DATETIME;
ALTER TABLE itinerary_item ADD COLUMN completed_at DATETIME;
ALTER TABLE itinerary_item ADD COLUMN visited_at DATETIME;

CREATE TABLE trip_event (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    trip_id BIGINT NOT NULL,
    itinerary_item_id BIGINT,
    event_type VARCHAR(40) NOT NULL,
    source VARCHAR(30),
    summary VARCHAR(500),
    detail TEXT,
    occurred_at DATETIME NOT NULL,
    created_at DATETIME
);

CREATE INDEX idx_trip_event_trip ON trip_event (trip_id);
CREATE INDEX idx_trip_event_item ON trip_event (itinerary_item_id);
CREATE INDEX idx_trip_event_occurred ON trip_event (trip_id, occurred_at);

ALTER TABLE schedule_item ADD COLUMN trip_id BIGINT;
ALTER TABLE schedule_item ADD COLUMN itinerary_item_id BIGINT;
ALTER TABLE schedule_item ADD COLUMN source VARCHAR(30);
ALTER TABLE schedule_item ADD COLUMN travel_day INT;

ALTER TABLE memo ADD COLUMN trip_id BIGINT;
ALTER TABLE memo ADD COLUMN itinerary_item_id BIGINT;
ALTER TABLE memo ADD COLUMN source VARCHAR(30);
ALTER TABLE memo ADD COLUMN travel_day INT;

CREATE INDEX idx_schedule_trip ON schedule_item (trip_id);
CREATE INDEX idx_memo_trip ON memo (trip_id);
