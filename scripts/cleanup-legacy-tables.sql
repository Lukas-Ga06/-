-- ============================================================================
-- 退役旧表数据清理（forward-only / opt-in / 默认只读）
--
-- 背景：daily_report / mail_item / meeting_minutes / invoice / learning_artifact /
--   health_record 已无实体与接口，Flyway 也**永远不会**删除它们（schema forward-only）。
--   历史库若仍有某个用户的数据，账号注销 API 不会覆盖，只能由运维在备份后手工清理。
--
-- 安全约定：
--   1) 本文件不会自动执行；执行前必须先备份并双人复核（见 docs/operations/backup-restore.md）。
--   2) 默认只跑 STEP 1 的只读盘点；STEP 2 的 DELETE 默认被注释，确认后再取消注释。
--   3) 只按 @uid 精确删除，不按用户名/模糊匹配；未注入 @uid 时所有语句匹配 0 行（安全失败）。
--   4) 所有删除在同一事务内；COMMIT 前可 ROLLBACK 复核。
--
-- 用法（示例；口令用受控 defaults-extra-file，不要写进命令行历史）：
--   mysql --defaults-extra-file=<受控 cnf> --database=travel_agent \
--     --init-command="SET @uid = 123" < scripts/cleanup-legacy-tables.sql
--
-- 文档：docs/legal/data-deletion.md §4
-- ============================================================================

-- STEP 1：只读盘点（确认每张表是否真的有该用户的数据；行数即审计证据）
SELECT 'daily_report'       AS table_name, COUNT(*) AS rows_for_user FROM daily_report       WHERE user_id = @uid
UNION ALL SELECT 'mail_item',         COUNT(*) FROM mail_item         WHERE user_id = @uid
UNION ALL SELECT 'meeting_minutes',   COUNT(*) FROM meeting_minutes   WHERE user_id = @uid
UNION ALL SELECT 'invoice',           COUNT(*) FROM invoice           WHERE user_id = @uid
UNION ALL SELECT 'learning_artifact', COUNT(*) FROM learning_artifact WHERE user_id = @uid
UNION ALL SELECT 'health_record',     COUNT(*) FROM health_record     WHERE user_id = @uid;

-- STEP 2：事务内删除（默认注释；确认 STEP 1 行数、已完成备份、且双人复核后再取消注释）
-- START TRANSACTION;
--
-- DELETE FROM daily_report       WHERE user_id = @uid;
-- DELETE FROM mail_item          WHERE user_id = @uid;
-- DELETE FROM meeting_minutes    WHERE user_id = @uid;
-- DELETE FROM invoice            WHERE user_id = @uid;
-- DELETE FROM learning_artifact  WHERE user_id = @uid;
-- DELETE FROM health_record      WHERE user_id = @uid;
--
-- -- 提交前复核：每张表应为 0
-- SELECT 'daily_report' AS table_name, COUNT(*) AS remaining FROM daily_report       WHERE user_id = @uid
-- UNION ALL SELECT 'mail_item',         COUNT(*) FROM mail_item         WHERE user_id = @uid
-- UNION ALL SELECT 'meeting_minutes',   COUNT(*) FROM meeting_minutes   WHERE user_id = @uid
-- UNION ALL SELECT 'invoice',           COUNT(*) FROM invoice           WHERE user_id = @uid
-- UNION ALL SELECT 'learning_artifact', COUNT(*) FROM learning_artifact WHERE user_id = @uid
-- UNION ALL SELECT 'health_record',     COUNT(*) FROM health_record     WHERE user_id = @uid;
--
-- COMMIT;
-- -- 如需放弃：ROLLBACK;