-- V23：会话并发控制（conversation_message.turn_seq）与会话元数据（conversation_meta）。
--
-- 背景：会话记忆此前是「按 id 顺序追加的一段流水」，一轮问答（用户消息 + 助手回复）
--   在库里没有任何成组标记。两台设备同时聊同一个 conversationId 时，
--   A 的提问、B 的提问、A 的回复、B 的回复会按完成先后交叉落库：
--   读回来是 [A问, B问, A答, B答]，下一轮做指代消解时「上一句」指向了别人的半句。
--   这不是崩溃，是悄悄写坏上下文——比报错更难发现。
--
-- turn_seq 的语义：一轮 = 一个 turn_seq（用户消息与它的助手回复共用同一个值，
--   二者用 role 区分）。分配方式是「取当前最大值 + 1 后直接插入」，
--   撞唯一键就重试（compare-and-set）：并发时后到的那一轮顺延到下一个号，
--   两轮各自的问答因此始终相邻，不会被对方劈开。
--
-- 唯一键用 CREATE UNIQUE INDEX 而不是 ALTER TABLE ... ADD CONSTRAINT：
--   H2 与 MySQL 8 对前者的语法与语义完全一致（V22 已验证同一写法）。
--   历史行 turn_seq 为 NULL，而 H2 / MySQL 的唯一索引都允许多个 NULL，
--   所以存量数据无需回填，升级即安全；读取侧用 COALESCE(turn_seq, 0) 把它们
--   排在所有新轮次之前（它们本来就是更早的数据）。
--
-- conversation_meta：会话级元数据（用户显式改的标题）。此前标题是从「首条用户消息」
--   推导出来的，没有地方存用户的改名；这里按 (user_id, conversation_id) 一行，
--   没有行 = 没改过名 = 继续用推导标题。删会话时与消息一起删。
--
-- 方言：ALTER TABLE ... ADD COLUMN / CREATE UNIQUE INDEX / CREATE TABLE IF NOT EXISTS
--   在 H2(MODE=MySQL) 与 MySQL 8 上语义一致，因此放在共享核心目录（V15 起的约定）。
ALTER TABLE conversation_message ADD COLUMN turn_seq BIGINT;

CREATE UNIQUE INDEX uk_conv_turn_role ON conversation_message (user_id, conversation_id, turn_seq, role);

CREATE TABLE IF NOT EXISTS conversation_meta (
    id              BIGINT      NOT NULL AUTO_INCREMENT,
    user_id         BIGINT      NOT NULL,
    conversation_id VARCHAR(64) NOT NULL,
    title           VARCHAR(64),
    created_at      TIMESTAMP,
    updated_at      TIMESTAMP,
    PRIMARY KEY (id)
);

-- 唯一键单独建（不写成内联 UNIQUE KEY）：H2 会给内联约束自动改名成
-- UK_..._INDEX_2，索引名对不上就无法作为「约束确实存在」的证据。
CREATE UNIQUE INDEX uk_conv_meta_user_conv ON conversation_meta (user_id, conversation_id);