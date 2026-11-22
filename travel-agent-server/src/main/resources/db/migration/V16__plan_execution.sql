-- V16：确认门内容绑定（FR-08）与确认后执行记录（FR-09）。
--
-- pending_plan.content_hash：方案内容（plan_json）的 SHA-256。put 时计算，
--   确认时双校验：请求携带的指纹一致（用户确认的就是看到的内容）+ 现值仍等于存值（未被后台改动）。
--   存量行该列为 NULL → 视为「未绑定指纹」，只走既有 planRevision 校验（向后兼容，行为不变）。
-- plan_execution：一次确认提交的跨模块同步台账。plan_id 唯一 = 同一方案只产生一份逻辑执行（幂等）；
--   modules_json 记录日程/预算/足迹逐模块状态（SUCCEEDED / FAILED + 详情），失败模块可单独重试。
-- schedule_item.source_key：Agent 同步生成的日程幂等键（plan-sync:{planId}:day:{n}），
--   唯一索引兜底并发；手工创建的日程该列为 NULL（可重复为 NULL，不受唯一索引约束）。
--
-- 全部为中性 SQL（H2 / MySQL 通用），放共享核心目录；列定义与实体一一对应（两个 profile 均 ddl-auto=validate）。
ALTER TABLE pending_plan ADD COLUMN content_hash VARCHAR(64);

CREATE TABLE IF NOT EXISTS plan_execution (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    execution_id  VARCHAR(64)  NOT NULL,
    plan_id       VARCHAR(64)  NOT NULL,
    user_id       BIGINT       NOT NULL,
    trip_id       BIGINT,
    status        VARCHAR(20)  NOT NULL,
    modules_json  TEXT,
    created_at    DATETIME     NOT NULL,
    updated_at    DATETIME,
    CONSTRAINT uk_plan_execution_execution_id UNIQUE (execution_id),
    CONSTRAINT uk_plan_execution_plan_id UNIQUE (plan_id)
);
CREATE INDEX idx_plan_execution_user ON plan_execution (user_id, id);
CREATE INDEX idx_plan_execution_trip ON plan_execution (trip_id);

ALTER TABLE schedule_item ADD COLUMN source_key VARCHAR(100);
