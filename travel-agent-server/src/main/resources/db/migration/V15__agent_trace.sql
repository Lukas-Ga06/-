-- V15：Agent 运行 / 任务 / 工具调用追踪表（V2.2 FR-02 / FR-04 / FR-10）。
-- agent_run  ：一次对话轮次（chat / SSE stream）的生命周期与结果状态。
-- agent_task ：run 内的阶段任务（intent-agent / research-agent / planner-agent / ...），任务协议的最小落库形态。
-- tool_call_trace：每次工具调用的参数/结果摘要、成功标记、耗时（SSE 事件易失，落库才可查询）。
--
-- 三张都是新表：与 V2/V4/V6 策略一致，本脚本自足（全新库 Flyway 先于 Hibernate 建全表）；
-- 存量库同样按 IF NOT EXISTS 空转，列定义与实体一一对应（两个 profile 均 ddl-auto=validate）。
-- SQL 不使用任何方言特性，MySQL / H2 通用，因此放在共享目录 db/migration。
CREATE TABLE IF NOT EXISTS agent_run (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    run_id      VARCHAR(64)  NOT NULL,
    user_id     BIGINT       NOT NULL,
    trip_id     BIGINT,
    message     VARCHAR(500),
    intent      VARCHAR(50),
    status      VARCHAR(16)  NOT NULL,
    error       VARCHAR(500),
    started_at  DATETIME     NOT NULL,
    ended_at    DATETIME,
    CONSTRAINT uk_agent_run_run_id UNIQUE (run_id)
);
CREATE INDEX idx_agent_run_user ON agent_run (user_id, id);

CREATE TABLE IF NOT EXISTS agent_task (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    task_id     VARCHAR(64)  NOT NULL,
    run_id      VARCHAR(64)  NOT NULL,
    user_id     BIGINT       NOT NULL,
    receiver    VARCHAR(50)  NOT NULL,
    task_type   VARCHAR(50)  NOT NULL,
    status      VARCHAR(16)  NOT NULL,
    summary     VARCHAR(1000),
    error       VARCHAR(500),
    started_at  DATETIME     NOT NULL,
    ended_at    DATETIME,
    CONSTRAINT uk_agent_task_task_id UNIQUE (task_id)
);
CREATE INDEX idx_agent_task_run ON agent_task (run_id, id);

CREATE TABLE IF NOT EXISTS tool_call_trace (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    run_id        VARCHAR(64),
    task_id       VARCHAR(64),
    user_id       BIGINT       NOT NULL,
    tool_name     VARCHAR(100) NOT NULL,
    args_digest   VARCHAR(1000),
    result_digest VARCHAR(1000),
    success       BOOLEAN      NOT NULL,
    error_code    VARCHAR(200),
    latency_ms    BIGINT,
    started_at    DATETIME     NOT NULL
);
CREATE INDEX idx_tool_trace_run ON tool_call_trace (run_id, id);
CREATE INDEX idx_tool_trace_user ON tool_call_trace (user_id, id);
