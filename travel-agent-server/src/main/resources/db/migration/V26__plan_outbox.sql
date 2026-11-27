-- V26：事务性 Outbox（P0 可靠执行链路：跨模块一致性）。
--
-- 确认提交（行程落库）与「日程 / 预算快照 / 足迹」跨模块同步必须在同一事务里
-- 写入待办事件，再由独立投递器至少一次投递、幂等消费；进程在两者之间崩溃不会
-- 丢掉同步工作。此前是提交之后进程内尽力而为，崩溃即静默丢数据。
--
-- (plan_id, module) 唯一：同一方案的同一模块只有一条投递待办，重复提交/重放不会翻倍。
-- status：PENDING（待投递）/ DISPATCHING（已被某个投递器领走）/ DONE（已确认完成）/
--         DEAD（重试耗尽，需要人工/运维介入，保留证据）。
-- next_attempt_at：指数退避后的下次可投递时间；投递器只领 status=PENDING 且已到期的行。
-- attempts / last_error / claimed_at：每次失败的累计次数、最近一次错误与领取时间（可观测）。
--
-- 双方言中性 SQL（表自足：全新库 Flyway 先于 Hibernate 建表；存量库 IF NOT EXISTS 空转）。
CREATE TABLE IF NOT EXISTS plan_outbox (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    event_id        VARCHAR(64)  NOT NULL,
    plan_id         VARCHAR(64)  NOT NULL,
    user_id         BIGINT       NOT NULL,
    trip_id         BIGINT       NOT NULL,
    module          VARCHAR(32)  NOT NULL,
    payload_json    TEXT,
    status          VARCHAR(16)  NOT NULL,
    attempts        INT          NOT NULL DEFAULT 0,
    next_attempt_at DATETIME     NOT NULL,
    last_error      VARCHAR(500),
    claimed_at      DATETIME,
    created_at      DATETIME     NOT NULL,
    updated_at      DATETIME,
    CONSTRAINT uk_plan_outbox_event UNIQUE (event_id),
    CONSTRAINT uk_plan_outbox_plan_module UNIQUE (plan_id, module)
);
CREATE INDEX idx_plan_outbox_due ON plan_outbox (status, next_attempt_at);
CREATE INDEX idx_plan_outbox_trip ON plan_outbox (trip_id);