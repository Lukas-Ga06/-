-- ============================================================================
-- V18：任务图（FR-02 / FR-04，批次 15）
--
-- agent_task 从「阶段记录」升级为「任务图节点」：
--   * depends_on      —— 前置任务 id 列表（逗号分隔）。全部前置 SUCCEEDED 才允许启动，
--                        否则记一条 CONFLICT 任务并拒绝执行 body（Coordinator 强制校验）；
--   * attempt         —— 第几次尝试。0 = 首次；1、2… = 回收重派（与「第 N 次重规划」同口径）；
--   * replaces_task_id—— 本次重派替换掉的旧任务 id（旧行保留为失败/冲突证据，不覆盖）。
--
-- 方言：H2(MODE=MySQL) 与 MySQL 8.0 都支持 ADD COLUMN ... INT NOT NULL DEFAULT。
--   全新库：V15 建出 agent_task（不含这三列）→ V18 补列；存量库：Flyway 跳过 V1..V17，
--   只跑本脚本。实体列定义与 validate 对齐（attempt 在实体里是 Integer，prePersist 兜底 0）。
-- ============================================================================
ALTER TABLE agent_task ADD COLUMN depends_on VARCHAR(1000);
ALTER TABLE agent_task ADD COLUMN attempt INT NOT NULL DEFAULT 0;
ALTER TABLE agent_task ADD COLUMN replaces_task_id VARCHAR(64);