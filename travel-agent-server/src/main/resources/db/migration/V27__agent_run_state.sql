-- V27：Agent 运行状态机落库（P0 可靠执行链路：崩溃后可回答「走到哪了」）。
--
-- agent_run 新增当前状态快照：
--   stage        ：当前阶段（RECEIVE / UNDERSTAND / RESEARCH / PLAN / EXECUTE / REFLECT / PRESENT / ...）
--   heartbeat_at ：最近一次阶段推进时间；进程崩溃后按心跳超时把 RUNNING 判为 ABANDONED
--   request_id   ：客户端幂等标识；SSE 断线重连时据此找到原 run（而不是重新规划）
-- agent_run_stage：状态迁移历史（每个 run 一条单调序列），回答「这个 run 依次经过了哪些阶段」。
--
-- 双方言中性 SQL。
ALTER TABLE agent_run ADD COLUMN stage VARCHAR(32);
ALTER TABLE agent_run ADD COLUMN heartbeat_at DATETIME;
ALTER TABLE agent_run ADD COLUMN request_id VARCHAR(64);
CREATE INDEX idx_agent_run_status_heartbeat ON agent_run (status, heartbeat_at);
CREATE INDEX idx_agent_run_user_request ON agent_run (user_id, request_id);

CREATE TABLE IF NOT EXISTS agent_run_stage (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    run_id      VARCHAR(64)  NOT NULL,
    seq_no      INT          NOT NULL,
    stage       VARCHAR(32)  NOT NULL,
    entered_at  DATETIME     NOT NULL,
    detail      VARCHAR(500),
    CONSTRAINT uk_agent_run_stage UNIQUE (run_id, seq_no)
);
CREATE INDEX idx_agent_run_stage_run ON agent_run_stage (run_id, seq_no);