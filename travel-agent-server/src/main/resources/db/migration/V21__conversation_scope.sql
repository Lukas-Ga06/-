-- ============================================================================
-- V21：会话作用域（体验方案 §8.6 续聊边界）
--
-- 为什么需要它：conversation_id 由前端生成，服务端此前不知道一段会话属于
-- 「助手页」「规划器」还是「行程 12」。于是最近会话列表把三个页面的对话混在一起，
-- 在助手页继续一段行程会话时**只恢复消息、不恢复行程绑定**（下一条消息不带 tripId），
-- Agent 也就丢了「我现在在这个行程里」这个前提。
--
-- 存什么：scope 是一段会话最近一次被使用时的作用域字符串，取值约定
--   assistant | planner | trip:<id>
-- 它只是**提示**，不是权限：所有查询仍以 user_id 隔离，scope 只用于
--   1) 列表里给会话打标签、默认过滤当前作用域；
--   2) 续聊时把 tripId 恢复回来（前端只认 scope 的 trip:<id> 前缀，不采信别的来源）。
-- 因此不设唯一约束、不做外键：同一条 trip 可以被多段会话绑定，trip 被删也不影响历史可读。
--
-- 回填策略：存量行 scope 一律为 NULL。**不猜**——从 message 文本反推「这句话是不是
-- 在行程 12 里说的」属于编造数据；NULL 在对外契约里显式表示为 scope=null，
-- 前端据此把旧会话当作「未绑定行程」，行为与本次改造前完全一致。
--
-- 方言：双方言中性（H2 MODE=MySQL 与 MySQL 8.0 都支持 ADD COLUMN / CREATE INDEX）。
-- 索引名与实体 @Index 一致；MySQL 不支持 CREATE INDEX IF NOT EXISTS，但本脚本只在
-- 空库/未应用过 V21 的库上执行一次，不存在重复创建的可能。
-- ============================================================================

ALTER TABLE conversation_message ADD COLUMN scope VARCHAR(32);

CREATE INDEX idx_conv_user_scope ON conversation_message (user_id, scope);