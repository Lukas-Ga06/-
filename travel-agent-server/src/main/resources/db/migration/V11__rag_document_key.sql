-- ============================================================================
-- V11：rag_document.doc_key（batch10 / C：同一新文档并发首建不重复入库）
--
-- 目标（docs/proposals/batch10-contract.md §3.3）：
--   doc_key = LEFT(CONCAT(COALESCE(user_id,'public'),'|',title,'|',city), 190)
--   doc_key NOT NULL + 唯一索引 uk_rag_document_key
--   → 同一 (归属用户, 标题, 城市) 只可能有一行文档；并发首建时后插入的一方撞唯一键，
--     应用层（RagDocumentUpsertService）把 DataIntegrityViolationException 当正常路径回读，不冒 500。
--
-- 【为什么用 CASE 而不是 COALESCE(user_id, 'public')】
--   两个引擎对 COALESCE(BIGINT, 'public') 的类型推断不一致（实测 H2 2.3.232 MODE=MySQL / MySQL 8.0.46）：
--     * H2   ：把 'public' 隐式转成 BIGINT → "Data conversion error converting "public""，脚本直接失败；
--     * MySQL：把数字转字符串，结果 "public" / "7"。
--   改用 CASE WHEN user_id IS NULL THEN 'public' ELSE CONCAT('', user_id) END：
--   两个引擎都得到 "public" 与十进制字符串 "7"（CONCAT('', 数字) 两边都按十进制转换）。
--   另外 H2 的 CONCAT 把 NULL 参数当空串（MySQL 返回 NULL），所以 NULL 必须显式 CASE，
--   不能靠 COALESCE(CONCAT(...), 'public')（实测 H2 会得到空串）。
--   title/city 上再套 COALESCE(...,'')：否则 MySQL 的 CONCAT 遇 NULL 整串变 NULL，
--   会让 NOT NULL 收紧失败（历史脏数据里 title/city 可能为空）。
--
-- 【去重必须在建唯一索引之前】历史库里同一 (user_id,title,city) 可能已有多行。
--   策略：保留 id 最小的一行，其余把 title 改成 CONCAT(LEFT(title,170),'#dup-',id)，
--   让它们的 doc_key 自然变唯一——**不删任何文档、正文或知识块**。
--   170 的来历：title 是 VARCHAR(200)，'#dup-' 5 字符 + 自增 id 最多 19 位 = 194 ≤ 200，
--   不会因列宽溢出被截断（若用 LEFT(title,200) 拼后缀，超长标题会被截回原值、去重失效）。
--
-- 【前置依赖：rag_document.user_id 必须存在】
--   第 2 步的分区与第 3 步的回填都直接引用 user_id。它由 **V1 基线**负责建出
--   （V1__baseline.sql 的 rag_document 同时带 user_id + fingerprint：V4/V5 都是
--   CREATE TABLE IF NOT EXISTS，表已存在时补不了列；V10 也依赖 fingerprint）。
--   存量库路径不受影响：V1 已被 baseline 跳过，而 user_id 早在 batch5 之后由
--   ddl-auto=update 补过。若将来有人把 V1 改回"严格基线"，本脚本会在空库上报
--   Unknown column 'user_id' —— 需要同时调整 V1 或本脚本，二者必须一起改。
--
-- 【方言说明】本脚本刻意保持便携（H2 + MySQL 都能跑），因此留在 db/migration 而不是
--   db/migration-mysql：ADD COLUMN / MODIFY ... NOT NULL / CREATE UNIQUE INDEX /
--   窗口函数 ROW_NUMBER() OVER / LEFT() / CONCAT() 在 H2(MODE=MySQL) 与 MySQL 8.0 上都实测通过。
--   注意 MySQL 不支持 ADD COLUMN IF NOT EXISTS，本脚本按 Flyway 语义每个库只执行一次，
--   不做重跑幂等（与 V4/V5/V7/V10 同一策略；重跑前需人工确认列/索引是否已存在）。
-- ============================================================================

-- 1) 先加可空列：存量行必须先回填才能收紧为 NOT NULL
ALTER TABLE rag_document ADD COLUMN doc_key VARCHAR(190) NULL;

-- 2) 去重：同 (user_id,title,city) 只保留 id 最小的一行，其余标题加 #dup-<id> 后缀使其唯一
UPDATE rag_document d
SET d.title = CONCAT(LEFT(COALESCE(d.title, ''), 170), '#dup-', d.id)
WHERE d.id IN (
    SELECT id FROM (
        SELECT id, ROW_NUMBER() OVER (
                   PARTITION BY user_id, COALESCE(title, ''), COALESCE(city, '')
                   ORDER BY id) AS rn
        FROM rag_document
    ) ranked
    WHERE ranked.rn > 1
);

-- 3) 回填 doc_key（表达式与 Java 侧 RagDocumentKeys#of 逐字对应）
UPDATE rag_document
SET doc_key = LEFT(CONCAT(
        CASE WHEN user_id IS NULL THEN 'public' ELSE CONCAT('', user_id) END,
        '|', COALESCE(title, ''), '|', COALESCE(city, '')), 190)
WHERE doc_key IS NULL;

-- 4) 收紧为 NOT NULL：与实体 @Column(name="doc_key", nullable=false) 一致，
--    否则 ddl-auto=validate 会报 "is nullable but should not be"
ALTER TABLE rag_document MODIFY doc_key VARCHAR(190) NOT NULL;

-- 5) 唯一索引：并发插入撞它 → 应用层捕获并回读（不冒 500）
CREATE UNIQUE INDEX uk_rag_document_key ON rag_document (doc_key);