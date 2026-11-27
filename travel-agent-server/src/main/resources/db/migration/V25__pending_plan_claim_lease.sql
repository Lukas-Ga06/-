-- V25：待确认方案的确认租约（P0 可靠执行链路：Confirm 崩溃恢复与并发安全）。
--
-- 背景：claim 用一条条件 UPDATE 把 PENDING 改成 CONFIRMING 抢所有权；进程若在
-- 「抢到所有权」与「提交完成」之间崩溃，行会永久停在 CONFIRMING —— 既不能被再次
-- claim（只认 PENDING），也没有调用者会 release。用户只能等 TTL 过期重来。
--
-- claim_token：本次 claim 的所有权令牌。提交事务里用「删除仍持有该令牌的行」做栅栏
--   （fence）：行若已被清扫改回 PENDING、或被别的请求重新 claim（令牌不同），删除
--   影响 0 行 → 抛出异常回滚整个提交事务，避免在旧租约上重复落库。
-- claimed_at ：claim 时间。清扫任务按 claimed_at + 配置的租约时长把过期 CONFIRMING
--   改回 PENDING 并清空令牌，让用户可以继续确认/取消，而不是被卡死。
--
-- 双方言中性 SQL（H2 / MySQL 均支持 ALTER TABLE ADD COLUMN / CREATE INDEX）。
ALTER TABLE pending_plan ADD COLUMN claim_token VARCHAR(64);
ALTER TABLE pending_plan ADD COLUMN claimed_at DATETIME;
CREATE INDEX idx_pending_plan_claim ON pending_plan (status, claimed_at);