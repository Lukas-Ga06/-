-- V29：每轮 Agent 花费落库（第二阶段：花费口径可查询）。
--
-- 背景：花费预算（AgentSpend）此前只写一行 INFO 日志与一个低基数指标，
-- 能回答「这一轮花了多少」但没法从执行台账回查——日志会滚动、指标不带 runId
-- （也不应该带：runId 进 tag 是基数炸弹）。本表把每轮收尾时的快照落一行，
-- 供 /api/agent/runs/{runId} 与运维 SQL 直接回答「这次运行花了多少、有没有到顶、卡在哪一项」。
--
-- run_id 唯一：一次运行只有一条花费快照；正常路径只写一次，重复收尾按后写覆盖（快照只会更完整）。
-- user_id 冗余存一份：按用户/时间查花费时不必回表 join agent_run。
-- exhausted：触顶的花费项枚举（llm_calls/tool_calls/retries/tokens/cost），未触顶为 NULL。
-- 本表随 Agent 追踪保留期一起清理（默认 14 天，见 AgentTraceService.purgeExpired）。
--
-- 双方言中性 SQL（H2 / MySQL 8 均支持 DECIMAL、DATETIME、AUTO_INCREMENT 与唯一约束）。
CREATE TABLE IF NOT EXISTS agent_run_spend (
    id                BIGINT AUTO_INCREMENT PRIMARY KEY,
    run_id            VARCHAR(64)    NOT NULL,
    user_id           BIGINT,
    llm_calls         INT            NOT NULL DEFAULT 0,
    tool_calls        INT            NOT NULL DEFAULT 0,
    retries           INT            NOT NULL DEFAULT 0,
    prompt_tokens     BIGINT         NOT NULL DEFAULT 0,
    completion_tokens BIGINT         NOT NULL DEFAULT 0,
    cost_usd          DECIMAL(18, 6) NOT NULL DEFAULT 0,
    exhausted         VARCHAR(20),
    created_at        DATETIME       NOT NULL,
    CONSTRAINT uk_agent_run_spend_run UNIQUE (run_id)
);
CREATE INDEX idx_agent_run_spend_created ON agent_run_spend (created_at);
CREATE INDEX idx_agent_run_spend_user ON agent_run_spend (user_id, created_at);