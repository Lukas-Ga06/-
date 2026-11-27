-- V28：SSE 事件持久化（P0 可靠执行链路：断线可续传）。
--
-- 每个 run 下的事件按 seq_no（从 1 开始单调递增）落库；SSE 帧的 id 形如
-- <runId>:<seq_no>。客户端断线后带 Last-Event-ID（或调用
-- GET /api/agent/runs/{runId}/events?lastEventId=N）即可补发缺失事件，
-- 不必整段重新规划（后者会重复消耗 LLM 配额）。
--
-- data_json：事件载荷的 JSON（complete 事件可能携带完整方案，故用 LONGTEXT）。
-- 事件按 run 保留，随 Agent 追踪保留期一起清理。
--
-- 双方言中性 SQL（LONGTEXT 在 H2 MySQL 模式与 MySQL 8 上均可用；
-- 已有 rag_document_version.content 使用同一类型）。
CREATE TABLE IF NOT EXISTS agent_run_event (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    run_id      VARCHAR(64)  NOT NULL,
    seq_no      INT          NOT NULL,
    event_type  VARCHAR(64)  NOT NULL,
    data_json   LONGTEXT,
    created_at  DATETIME     NOT NULL,
    CONSTRAINT uk_agent_run_event UNIQUE (run_id, seq_no)
);
CREATE INDEX idx_agent_run_event_run ON agent_run_event (run_id, seq_no);