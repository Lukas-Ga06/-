-- V22：schedule_item.source_key 唯一索引。
--
-- 背景（V16 的欠账）：V16 只执行了 `ALTER TABLE schedule_item ADD COLUMN source_key`，
--   但脚本头部注释承诺的「唯一索引兜底并发」从未创建。于是 Agent 确认后的日程同步
--   只剩「先查后插」（check-then-act）：并发重试同一执行、或多实例同时同步同一方案时，
--   同一 sourceKey 会写出两条「🧳 行程 · Day N」时间块，而且
--   PlanExecutionService 里那个 `catch (DataIntegrityViolationException)` 是死代码。
--
-- 建索引前先清理历史重复：同一 source_key 只保留 id 最小的一条。
--   这些行是同步产物（source='PLAN_SYNC'），重复的本身就是这个 bug 的结果，
--   删除不会丢用户手写数据；手工日程 source_key 为 NULL，而 H2 / MySQL 的唯一索引
--   都允许多个 NULL，因此不受影响。
--
-- 可移植性：CREATE TABLE ... AS SELECT / DELETE / DROP TABLE / CREATE UNIQUE INDEX
--   在 H2 与 MySQL 8 上语义一致，因此放在共享核心目录（V15 起的约定）。
--   MySQL 不允许在 DELETE 的子查询里直接读被删表，所以先把保留集落到临时表。
CREATE TABLE schedule_item_keep AS
SELECT MIN(id) AS keep_id FROM schedule_item WHERE source_key IS NOT NULL GROUP BY source_key;

DELETE FROM schedule_item
WHERE source_key IS NOT NULL
  AND id NOT IN (SELECT keep_id FROM schedule_item_keep);

DROP TABLE schedule_item_keep;

CREATE UNIQUE INDEX uk_schedule_source_key ON schedule_item (source_key);