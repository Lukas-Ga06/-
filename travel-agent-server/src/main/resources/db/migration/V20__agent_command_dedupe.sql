-- ============================================================================
-- V20：Agent 写入类命令的幂等台账（P0-01）
--
-- 为什么需要它：SSE 是 GET + EventSource，消息走 URL，浏览器刷新 / 前进后退 /
-- 弱网重连都可能把同一条「记一下明天办签证」重放一次。前端已经在发送前消费掉 q 参数，
-- 但那只是客户端自律；真正的兜底要落在服务端：同 (user_id, request_id) 只执行一次写入，
-- 重放直接返回首次结果。
--
-- 状态机：
--   IN_PROGRESS —— 已抢占，正在执行（同一 requestId 的并发请求看到它就不再执行）；
--   COMPLETED   —— 执行完成，result_json 存首次结果，后续重放原样返回；
--   执行抛异常时删掉 IN_PROGRESS 行（fail-open），允许用户用同一个 requestId 重试。
--
-- 方言：只用 H2(MODE=MySQL) 与 MySQL 8.0 都支持的写法
-- （CREATE TABLE / VARCHAR / TEXT / DATETIME / BIGINT AUTO_INCREMENT / UNIQUE）。
-- 索引不单独建：唯一键在 DDL 里声明，其余查询都走唯一键前缀 (user_id, request_id)。
-- ============================================================================

CREATE TABLE IF NOT EXISTS agent_command_dedupe (
    id          BIGINT       NOT NULL AUTO_INCREMENT,
    user_id     BIGINT       NOT NULL,
    request_id  VARCHAR(64)  NOT NULL,
    intent      VARCHAR(32),
    status      VARCHAR(16)  NOT NULL,
    result_json TEXT,
    created_at  DATETIME,
    updated_at  DATETIME,
    PRIMARY KEY (id),
    UNIQUE KEY uk_agent_command_user_request (user_id, request_id)
);