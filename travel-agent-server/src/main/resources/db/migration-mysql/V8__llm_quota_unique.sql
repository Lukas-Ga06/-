-- V8（MySQL 专属）：为 llm_quota_usage 补 (user_name, usage_date) 唯一键，幂等。
--
-- 为什么放在 db/migration-mysql 而不是 db/migration：
-- 本脚本用到 information_schema.statistics 探测、用户变量与 PREPARE 动态语句，
-- 这些是 MySQL 方言；H2（本地默认 profile）无法解析，会让 H2 环境直接启动失败。
-- 因此 application-mysql.yml 把该目录追加进 flyway.locations，默认 profile 不加载。
-- H2 侧同一约束由 ddl-auto 依实体 @UniqueConstraint 创建，不需要本脚本。
-- 副作用：H2 库的 flyway_schema_history 会缺 V8（Flyway 允许版本号跳空）。
--
-- V8：为 llm_quota_usage 补上 (user_name, usage_date) 唯一键（幂等：唯一键已存在则整段跳过）。
--
-- 背景：V3 建表时没有写 UNIQUE，只有实体 LlmQuotaUsage 上声明了
-- uk_llm_quota_user_date。Flyway + ddl-auto=validate 的库里 Hibernate 不会补索引，
-- 于是表上实际没有唯一约束：
--   * incrementIfBelow 的 UPDATE ... WHERE user_name=? AND usage_date=? 可能命中多行，
--     计数被多计/静默错算；
--   * findByUserNameAndUsageDate 命中多行时直接抛 NonUniqueResultException（500）。
--
-- 为什么必须幂等：存量 MySQL 生产库用 ddl-auto=update，Hibernate 很可能已经按实体声明
-- 建出了同名唯一键。若无条件 ADD CONSTRAINT，Flyway 会抛 Duplicate key name 并让整个
-- 应用启动失败；靠运维手工标记已应用不可接受。所以这里先查 information_schema：
--   * 约束已存在 → 去重与建约束都编译成 'SELECT 1'（空转），迁移本身仍然成功；
--   * 约束不存在 → 先物理去重（同一 (user_name, usage_date) 只保留 used_count 最大的一行，
--     并列时保留 id 最大即最新的一行），再 ADD CONSTRAINT。
-- 去重也放在同一分支里：唯一键已存在时表里不可能有重复行，没必要在大表上跑自连接。
--
-- 约束名 uk_llm_quota_user_date 与实体 LlmQuotaUsage 的
-- @UniqueConstraint(name = "uk_llm_quota_user_date", columnNames = {"user_name", "usage_date"})
-- 逐字一致（ddl-auto=validate 校验的是同一份元数据）。
--
-- 实现约束：Flyway 把同一迁移的所有语句放在**同一个连接**上按 ';' 顺序执行，
-- 因此会话变量 @uk_exists / @ddl / @dedupe 与 PREPARE 句柄在语句之间有效；
-- Flyway 自身的 schema-history 锁保证多实例并发启动时本脚本只会真正执行一次。
-- 每条语句内部不含 ';'（字符串字面量里也没有），所以 Flyway 的切分不会把一条语句劈开。
-- 语法以 MySQL 8.4 为准；测试库 H2 关闭了 Flyway，不会执行本脚本。
SET @uk_exists := (
    SELECT COUNT(*) FROM information_schema.statistics
    WHERE table_schema = DATABASE()
      AND table_name = 'llm_quota_usage'
      AND index_name = 'uk_llm_quota_user_date');

SET @dedupe := IF(@uk_exists = 0,
    'DELETE stale FROM llm_quota_usage stale JOIN llm_quota_usage keep ON stale.user_name = keep.user_name AND stale.usage_date = keep.usage_date AND (stale.used_count < keep.used_count OR (stale.used_count = keep.used_count AND stale.id < keep.id))',
    'SELECT 1');
PREPARE dedupe_stmt FROM @dedupe;
EXECUTE dedupe_stmt;
DEALLOCATE PREPARE dedupe_stmt;

SET @ddl := IF(@uk_exists = 0,
    'ALTER TABLE llm_quota_usage ADD CONSTRAINT uk_llm_quota_user_date UNIQUE (user_name, usage_date)',
    'SELECT 1');
PREPARE add_uk_stmt FROM @ddl;
EXECUTE add_uk_stmt;
DEALLOCATE PREPARE add_uk_stmt;