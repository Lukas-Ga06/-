package com.travel.agent;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * 迁移脚本防回退测试（batch10 schema 单一权威）。
 *
 * <p>不启动 Spring 上下文（纯文件断言，毫秒级），守住不能靠人工 review 保证的规则：
 * <ol>
 *   <li>每个 profile 的 locations 内版本号唯一、V1 基线存在；
 *       默认 profile = db/migration + db/migration-h2，mysql profile = db/migration + db/migration-mysql，
 *       两边各有一个方言版的 V13（Flyway 只对同一 locations 内的重复版本报错）；</li>
 *   <li>V1 绝不包含 V2+ 迁移才引入的对象（防止把新表/新列塞回基线，
 *       导致「基线建了 V7/V9 该建的列 → 后续 CREATE TABLE IF NOT EXISTS 跳过 → validate 分裂」）；</li>
 *   <li>任何方言下 `--` 注释都必须带空白（V4 曾因 `--（` 在 MySQL 上不是注释、
 *       把整条 CREATE TABLE 变成 SQL 语法错误；H2 能容忍，所以只在 MySQL 空库上爆炸）；</li>
 *   <li>MySQL 版 V13 用 information_schema + PREPARE 幂等；H2 版 V13 用
 *       ADD COLUMN / CREATE INDEX IF NOT EXISTS 幂等 —— 否则存量库启动就报 Duplicate；</li>
 *   <li>两个 profile 的 ddl-auto 都是 validate（schema 只能由 Flyway 改）。</li>
 * </ol>
 */
class MigrationBaselineTest {

    private static final String CORE_DIR = "db/migration";
    private static final String MYSQL_DIR = "db/migration-mysql";
    private static final String H2_DIR = "db/migration-h2";

    private static final Pattern VERSIONED_FILE = Pattern.compile("^V(\\d+)__([A-Za-z0-9_]+)\\.sql$");

    /** sql/init.sql 里的基础表 —— V1 基线的唯一内容（+ 注释里解释的必要偏离）。 */
    private static final Set<String> INIT_SQL_TABLES = new LinkedHashSet<>(List.of(
            "users", "trip", "itinerary_item", "operation_history", "expense", "memo",
            "schedule_item", "daily_report", "meeting_minutes", "mail_item", "invoice",
            "health_record", "learning_artifact", "rag_document", "rag_chunk"));

    /** init.sql 里已有的索引（V1 必须带上；名字与 V9 的 idx_<table>_<cols> 系列不冲突）。 */
    private static final Set<String> INIT_SQL_INDEXES = new LinkedHashSet<>(List.of(
            "idx_trip_user", "idx_item_trip", "idx_history_trip", "idx_expense_trip",
            "idx_expense_user", "idx_memo_user", "idx_schedule_user", "idx_report_user",
            "idx_minutes_user", "idx_mail_user", "idx_invoice_user", "idx_health_user",
            "idx_learning_user", "idx_chunk_doc"));

    /**
     * 这些名字是 V2+ 的迁移（或 ddl-auto 时代）才引入的，绝不能出现在 V1 的 SQL 里。
     * 特例（有意允许、已在 V1 文件头与交付说明写明）：rag_document 的 user_id / fingerprint ——
     * V4/V5 是 CREATE TABLE IF NOT EXISTS，V1 建过表后无法补列，而 V10 回填与 V11 去重直接依赖它们。
     */
    private static final List<String> V2_PLUS_ONLY_TOKENS = List.of(
            "doc_key", "version_id", "active_document_id", "rag_document_version",
            "pending_plan", "llm_quota_usage", "conversation_message", "trip_photo",
            "departure_reminder_dismissed_at", "next_occurrence", "recurrence",
            "repeat_until", "reminder_dismissed_at",
            "uk_rag_document_key", "uk_rag_version_active_doc", "uk_llm_quota_user_date",
            "idx_expense_user_spent_at", "idx_expense_trip_spent_at", "idx_memo_user_done",
            "idx_trip_user_start_date", "idx_schedule_item_user_sched_date",
            "idx_conversation_message_user_conv_created", "idx_operation_history_trip_created",
            "idx_pending_plan_user", "idx_rag_document_user");

    /** V13 两个方言版补的缺口列（同一份清单，防止两边漂移）。 */
    private static final List<String> GAP_COLUMNS = List.of(
            "version", "departure_reminder_dismissed_at", "recurrence", "repeat_until",
            "reminder_dismissed_at", "source_file", "source_type", "expense_id", "trip_id");

    @Test
    void eachProfileScopesHaveUniqueVersionsAndV1() throws Exception {
        // 默认 profile 与 mysql profile 各自是一个 locations 集合；版本号只需在集合内唯一
        List<List<String>> profileDirs = List.of(
                List.of(CORE_DIR, H2_DIR),
                List.of(CORE_DIR, MYSQL_DIR));

        // 便携迁移：V1..V11，其中 V8（MySQL 专属）不在核心目录 → 核心版本面是 1..7,9,10,11
        Set<Integer> core = new LinkedHashSet<>(List.of(1, 2, 3, 4, 5, 6, 7, 9, 10, 11));
        // V15（agent_run/agent_task/tool_call_trace）与 V16（content_hash/plan_execution/source_key）
        // 均为双方言中性 SQL，放在共享核心目录；V17（trip.party_size，FR-01）同样是中性 ALTER
        core.add(15);
        core.add(16);
        core.add(17);
        Set<Integer> mysqlExpected = new LinkedHashSet<>(core);
        mysqlExpected.addAll(List.of(8, 12, 13));
        Set<Integer> h2Expected = new LinkedHashSet<>(core);
        // V13/V14 是 H2 方言专属（V13 缺口补齐、V14 配额唯一键——同约束在 MySQL 侧由 V8 负责）
        h2Expected.addAll(List.of(13, 14));

        for (int i = 0; i < profileDirs.size(); i++) {
            List<String> dirs = profileDirs.get(i);
            Set<Integer> expected = i == 0 ? h2Expected : mysqlExpected;

            Map<String, String> versionToFile = new LinkedHashMap<>();
            Set<Integer> numbers = new TreeSet<>();
            for (String rel : dirs) {
                for (String name : sqlFiles(classpathDir(rel))) {
                    Matcher m = VERSIONED_FILE.matcher(name);
                    assertTrue(m.matches(), "迁移文件名不符合 V<数字>__<描述>.sql: " + name);
                    String version = m.group(1);
                    String previous = versionToFile.put(version, name);
                    assertEquals(null, previous,
                            "locations " + dirs + " 内版本 V" + version + " 重复: " + previous + " / " + name);
                    numbers.add(Integer.parseInt(version));
                }
            }
            assertEquals(expected, numbers, "locations " + dirs + " 的版本面不对");
        }

        // 方言目录只放各自的版本（每个 profile 各挂一套，否则同一 locations 里版本重复）
        assertEquals(List.of("V13__baseline_gap.sql"),
                sqlFiles(classpathDir(MYSQL_DIR)).stream().filter(n -> n.startsWith("V13")).toList());
        assertEquals(List.of("V13__baseline_gap_h2.sql", "V14__llm_quota_unique_h2.sql"),
                sqlFiles(classpathDir(H2_DIR)));
    }

    @Test
    void baselineDoesNotContainObjectsIntroducedAfterV1() throws Exception {
        String v1 = read(classpathDir(CORE_DIR).resolve("V1__baseline.sql"));
        String v1Sql = stripSqlComments(v1);

        // 1) 不许出现 V2+ 才引入的表/列/索引
        for (String token : V2_PLUS_ONLY_TOKENS) {
            assertFalse(v1Sql.toLowerCase(Locale.ROOT).contains(token.toLowerCase(Locale.ROOT)),
                    "V1 里出现了 V2+ 迁移才引入的对象 '" + token
                            + "'。基线必须停在 V2 之前；新对象只能由后续 V*__ 脚本引入。");
        }

        // 2) 不许出现库级语句（Flyway 在目标库里执行，CREATE DATABASE/USE 会切错库/失败）
        assertFalse(v1Sql.toUpperCase(Locale.ROOT).contains("CREATE DATABASE"), "V1 不能建库");
        assertFalse(Pattern.compile("(?im)^\\s*USE\\s").matcher(v1Sql).find(), "V1 不能 USE 库");

        // 3) 表清单必须与 sql/init.sql 一致（V1 的语义就是「init.sql 的等价基线」）
        Set<String> created = createdTables(v1Sql);
        assertEquals(INIT_SQL_TABLES, created,
                "V1 建出的表集合必须与 sql/init.sql 的基础表一致（新增表请加 V*__ 迁移，不要塞进 V1）");

        // 4) init.sql 里的索引必须都在（全新库不能缺基础索引）
        Set<String> indexes = createdIndexes(v1Sql);
        for (String idx : INIT_SQL_INDEXES) {
            assertTrue(indexes.contains(idx), "V1 缺少 sql/init.sql 里的索引 " + idx);
        }
    }

    @Test
    void noMigrationUsesMysqlInvalidDashComment() throws Exception {
        // MySQL 的 `--` 注释要求第二个 '-' 后是空白/控制字符；`--（` 不是注释。
        // H2 能容忍，于是这种写法只在「全新 MySQL 库」上炸（V4 曾因此让 Flyway 停在 V4）。
        Pattern bad = Pattern.compile("(?m)^--[^ \\t\\r\\n]");
        for (String rel : List.of(CORE_DIR, MYSQL_DIR, H2_DIR)) {
            for (String name : sqlFiles(classpathDir(rel))) {
                String text = read(classpathDir(rel).resolve(name));
                int lineNo = 1;
                for (String line : text.split("\\R", -1)) {
                    if (bad.matcher(line).find()) {
                        fail(rel + "/" + name + " 第 " + lineNo
                                + " 行是 `--` 后不带空白的注释：MySQL 要求 `-- `（带空白），"
                                + "否则整行会被当成 SQL 执行（V4 曾因此让全新 MySQL 库停在 V4）。行内容: " + line.trim());
                    }
                    lineNo++;
                }
            }
        }
    }

    @Test
    void gapScriptsAreIdempotentInBothDialects() throws Exception {
        // ---- MySQL 版：information_schema 探测 + PREPARE 条件执行 ----
        String mysql = stripSqlComments(read(classpathDir(MYSQL_DIR).resolve("V13__baseline_gap.sql")));
        assertTrue(mysql.contains("information_schema.columns"), "MySQL V13 必须探测列");
        assertTrue(mysql.contains("information_schema.tables"), "MySQL V13 必须探测表");
        assertTrue(mysql.contains("information_schema.statistics"), "MySQL V13 必须探测索引");
        assertTrue(mysql.contains("PREPARE"), "MySQL V13 必须用 PREPARE（MySQL 无 IF NOT EXISTS DDL）");
        for (String line : mysql.split("\\R")) {
            String t = line.trim().toUpperCase(Locale.ROOT);
            assertFalse(t.startsWith("ALTER TABLE") || t.startsWith("CREATE INDEX")
                            || t.startsWith("CREATE TABLE") || t.startsWith("DROP "),
                    "MySQL V13 出现无条件 DDL（存量库会 Duplicate column / Duplicate key name）: " + line.trim());
        }

        // ---- H2 版：原生 IF NOT EXISTS；不得混入 MySQL 方言 ----
        String h2 = stripSqlComments(read(classpathDir(H2_DIR).resolve("V13__baseline_gap_h2.sql")));
        assertFalse(h2.contains("information_schema") || h2.contains("PREPARE"),
                "H2 V13 不能出现 MySQL 方言（H2 会解析失败）");
        for (String line : h2.split("\\R")) {
            String t = line.trim();
            if (t.isEmpty()) {
                continue;
            }
            String upper = t.toUpperCase(Locale.ROOT);
            if (upper.startsWith("ALTER TABLE")) {
                assertTrue(upper.contains("ADD COLUMN IF NOT EXISTS"),
                        "H2 V13 的 ALTER TABLE 必须带 ADD COLUMN IF NOT EXISTS: " + t);
            } else if (upper.startsWith("CREATE INDEX")) {
                assertTrue(upper.contains("IF NOT EXISTS"), "H2 V13 的 CREATE INDEX 必须带 IF NOT EXISTS: " + t);
            } else if (upper.startsWith("CREATE TABLE")) {
                assertTrue(upper.contains("IF NOT EXISTS"), "H2 V13 的 CREATE TABLE 必须带 IF NOT EXISTS: " + t);
            }
        }

        // 两个方言补的是同一份缺口（逐列检查，防止一边改了一边忘了）
        for (String column : GAP_COLUMNS) {
            assertTrue(mysql.toLowerCase(Locale.ROOT).contains(column.toLowerCase(Locale.ROOT)),
                    "MySQL V13 缺少缺口列 " + column);
            assertTrue(h2.toLowerCase(Locale.ROOT).contains(column.toLowerCase(Locale.ROOT)),
                    "H2 V13 缺少缺口列 " + column);
        }
    }

    @Test
    void bothProfilesValidateAndLoadTheirDialectScripts() throws Exception {
        // 生产 MySQL：Flyway 是唯一权威，Hibernate 只校验
        String mysqlProfile = read(classpathFile("application-mysql.yml"));
        assertTrue(Pattern.compile("(?m)^\\s*ddl-auto:\\s*validate\\s*$").matcher(mysqlProfile).find(),
                "application-mysql.yml 必须 ddl-auto=validate");
        String mysqlLocations = locationsLine(mysqlProfile, "application-mysql.yml");
        assertTrue(mysqlLocations.contains("classpath:db/migration-mysql"),
                "mysql profile 必须加载 db/migration-mysql: " + mysqlLocations);
        assertFalse(mysqlLocations.contains("db/migration-h2"),
                "mysql profile 不能加载 H2 方言目录: " + mysqlLocations);

        // 本地默认 H2：同样 validate，缺口由 H2 方言的 V13 补齐
        String defaultProfile = read(classpathFile("application.yml"));
        assertTrue(Pattern.compile("(?m)^\\s*ddl-auto:\\s*validate\\s*$").matcher(defaultProfile).find(),
                "application.yml（H2）也必须 ddl-auto=validate");
        String h2Locations = locationsLine(defaultProfile, "application.yml");
        assertTrue(h2Locations.contains("classpath:db/migration-h2"),
                "默认 profile 必须加载 db/migration-h2: " + h2Locations);
        assertFalse(h2Locations.contains("db/migration-mysql"),
                "默认 profile 不能加载 MySQL 方言目录: " + h2Locations);
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    private static Path classpathDir(String relative) throws URISyntaxException {
        var url = Objects.requireNonNull(MigrationBaselineTest.class.getClassLoader().getResource(relative),
                "classpath 上找不到目录: " + relative);
        assertEquals("file", url.getProtocol(), "迁移脚本应从 target/classes 的目录读取");
        return Paths.get(url.toURI());
    }

    private static Path classpathFile(String name) throws URISyntaxException {
        var url = Objects.requireNonNull(MigrationBaselineTest.class.getClassLoader().getResource(name),
                "classpath 上找不到: " + name);
        return Paths.get(url.toURI());
    }

    /** 取 flyway.locations 那一行的值（避免 yml 注释里提到的目录名造成误判）。 */
    private static String locationsLine(String yml, String file) {
        Matcher m = Pattern.compile("(?m)^\\s*locations:\\s*(.+)$").matcher(yml);
        assertTrue(m.find(), file + " 缺少 flyway.locations");
        return m.group(1).trim();
    }

    private static List<String> sqlFiles(Path dir) {
        try (Stream<Path> files = Files.list(dir)) {
            return files.filter(p -> p.getFileName().toString().endsWith(".sql"))
                    .map(p -> p.getFileName().toString())
                    .sorted()
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String read(Path path) throws IOException {
        return Files.readString(path, StandardCharsets.UTF_8);
    }

    /** 去掉 `--` 行注释，只留真正会被执行的 SQL（避免注释里提到的表名触发误判）。 */
    private static String stripSqlComments(String sql) {
        StringBuilder sb = new StringBuilder();
        for (String line : sql.split("\\R", -1)) {
            if (line.trim().startsWith("--")) {
                continue;
            }
            int idx = line.indexOf("--");
            sb.append(idx >= 0 ? line.substring(0, idx) : line).append('\n');
        }
        return sb.toString();
    }

    private static Set<String> createdTables(String sql) {
        Set<String> out = new LinkedHashSet<>();
        Matcher m = Pattern.compile("(?i)CREATE\\s+TABLE\\s+(?:IF\\s+NOT\\s+EXISTS\\s+)?([A-Za-z0-9_]+)")
                .matcher(sql);
        while (m.find()) {
            out.add(m.group(1).toLowerCase(Locale.ROOT));
        }
        return out;
    }

    private static Set<String> createdIndexes(String sql) {
        Set<String> out = new LinkedHashSet<>();
        Matcher m = Pattern.compile("(?i)CREATE\\s+(?:UNIQUE\\s+)?INDEX\\s+([A-Za-z0-9_]+)").matcher(sql);
        while (m.find()) {
            out.add(m.group(1).toLowerCase(Locale.ROOT));
        }
        return out;
    }
}