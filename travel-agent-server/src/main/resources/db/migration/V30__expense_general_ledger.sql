-- ============================================================================
-- V30：记账与行程解绑（通用记账）+ 为发票报销预留 schedule_item_id
--
-- 背景：expense.trip_id 一直是 NOT NULL，没有当前行程时「今天买咖啡花了 150」
-- 只能被拒绝。记账是独立于行程的日常行为，不应该以「先建行程」为前提。
--
-- 为什么放开可空：trip_id 为空 = 未关联行程的通用账目；有值 = 原有行程账单。
-- 这是唯一的状态区分，不新增「类型」列——空/非空本身就是语义。
-- 预算聚合口径不变：BudgetService / TripReviewService 只按 trip_id 统计，
-- 通用账目不进任何行程预算；单行程汇总仍是走 trip_id 的 SUM 查询，不会退化成扫描。
--
-- schedule_item_id：为批次 3 的发票报销预留（指向 schedule_item.id，一次报销对应
-- 一条账期/日程条目）。暂无写入方；不建外键，与库内其他逻辑外键一致。
-- 需要索引是因为报销与审批查询会按该列反查账单，而当前该列全 NULL、索引代价极低。
--
-- 方言：H2(MODE=MySQL) 与 MySQL 8.0 共用同一份脚本。MODIFY COLUMN 是 MySQL 语法，
-- H2 在 MODE=MySQL 下接受同样的写法（由 H2FlywayMigrationTest 实测覆盖）；
-- 不加 IF NOT EXISTS（MySQL 8.0 的 ALTER ADD COLUMN 不支持），Flyway 保证每库只跑一次。
-- ============================================================================

ALTER TABLE expense MODIFY COLUMN trip_id BIGINT NULL;
ALTER TABLE expense ADD COLUMN schedule_item_id BIGINT NULL;

CREATE INDEX idx_expense_schedule ON expense (schedule_item_id);