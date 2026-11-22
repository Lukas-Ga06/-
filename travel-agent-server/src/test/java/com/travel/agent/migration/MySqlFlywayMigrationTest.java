package com.travel.agent.migration;

import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.metamodel.EntityType;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import javax.sql.DataSource;
import java.io.IOException;
import java.io.InputStream;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 真实 MySQL（8.4）上的 Flyway 全量迁移 + Hibernate {@code ddl-auto=validate} 集成验证。
 *
 * <p>这个测试回答一个 H2 单测回答不了的问题：<b>「db/migration + db/migration-mysql 的脚本，
 * 在真实 MySQL 上从空库一路跑到最新版本，并且与 21 个 JPA 实体完全对齐吗？」</b>
 * 测试 profile（H2 + {@code create-drop} + Flyway 关闭）永远覆盖不到方言相关的 DDL：
 * {@code information_schema} 探测、{@code PREPARE} 动态语句、生成列（V12）、
 * {@code ADD COLUMN ... DEFAULT} 回填（V17/V18/V19）等。
 *
 * <p><b>启用方式</b>：只有 {@code MYSQL_IT=true} 时才执行；普通 {@code mvn test} 下被 JUnit
 * 跳过（skipped，不是失败），因此不会拖累无 MySQL 的开发者与默认 CI。
 *
 * <p><b>隔离方式</b>：上下文启动前用 JDBC 管理员连接在目标实例上创建一次性库
 * {@code travel_agent_it_<yyyyMMddHHmmss>}（若同秒已存在则追加随机后缀），随后
 * {@code mysql} profile + {@link DynamicPropertySource} 把数据源指向该库。测试结束
 * <b>不删除</b>该库，保留 {@code flyway_schema_history} 与全部表作为证据；清理由
 * {@code scripts/mysql-it.ps1} 的 24 小时保留策略负责。绝不触碰实例上的其他库。
 *
 * <p><b>MySQL 版本链</b>：{@code mysql} profile 的 locations 是
 * {@code classpath:db/migration,classpath:db/migration-mysql}，看到的是
 * V1–V13（V8/V12/V13 为 MySQL 方言版）+ V15–V23，共 22 个迁移；
 * <b>没有 V14</b>（{@code V14__llm_quota_unique_h2.sql} 是 H2 专属）。
 * 实际版本集合从 classpath 动态枚举；{@link #DOCUMENTED_MYSQL_VERSIONS} 记录链尾，
 * 新增迁移时两处一起改，避免清单与脚本各说各话。
 *
 * <p><b>环境变量</b>：
 * <ul>
 *   <li>{@code MYSQL_IT=true}：总开关（必填，大小写敏感）；</li>
 *   <li>{@code MYSQL_IT_HOST}：默认 {@code 127.0.0.1}；</li>
 *   <li>{@code MYSQL_IT_PORT}：默认 {@code 3307}（本机便携实例；CI service container 常用 3306）；</li>
 *   <li>{@code MYSQL_IT_USERNAME} / {@code MYSQL_IT_PASSWORD}：默认 {@code root}/{@code 123456}，
 *       需要 CREATE DATABASE 与建表权限（业务账号通常不够）。</li>
 * </ul>
 *
 * <p>失败信息一律带对象名（schema / 表 / 列 / 索引 / 迁移版本），便于直接定位是哪个迁移漏了什么。
 */
@EnabledIfEnvironmentVariable(named = "MYSQL_IT", matches = "true")
@SpringBootTest
@ActiveProfiles("mysql")
class MySqlFlywayMigrationTest {

    /** MySQL 迁移链的版本集合：V1–V13 + V15–V23（V14 是 H2 专属）。 */
    private static final List<String> DOCUMENTED_MYSQL_VERSIONS = documentedMysqlVersions();

    /** 21 个 JPA 实体映射的表（@Table(name=...) 逐字摘自 entity/，新增实体时同步维护）。 */
    private static final List<String> MAPPED_TABLES = List.of(
            "agent_command_dedupe", "agent_run", "agent_task", "conversation_message", "conversation_meta",
            "expense", "itinerary_item",
            "llm_quota_usage", "memo", "operation_history", "pending_plan", "plan_execution",
            "rag_chunk", "rag_document", "rag_document_version", "schedule_item", "tool_call_trace",
            "trip", "trip_event", "trip_photo", "users");

    /** 21 个 JPA 实体的简单类名（新增实体时同步维护，防止「实体删了但迁移还在」静默通过）。 */
    private static final List<String> MAPPED_ENTITIES = List.of(
            "AgentCommandDedupe",
            "AgentRun", "AgentTask", "ConversationMessage", "ConversationMeta", "Expense", "ItineraryItem",
            "LlmQuotaUsage", "Memo", "OperationHistory", "PendingPlanRecord", "PlanExecution",
            "RagChunk", "RagDocument", "RagDocumentVersion", "ScheduleItem", "ToolCallTrace",
            "Trip", "TripEvent", "TripPhoto", "User");

    /** 迁移脚本必须建出的关键列（表 -> 列），按版本分组，失败信息带列名。 */
    private static final Map<String, List<String>> REQUIRED_COLUMNS = requiredColumns();

    /** 迁移脚本必须建出的关键索引（表 -> 索引名）。 */
    private static final Map<String, List<String>> REQUIRED_INDEXES = requiredIndexes();

    private static final String HOST = env("MYSQL_IT_HOST", "127.0.0.1");
    private static final String PORT = env("MYSQL_IT_PORT", "3307");
    private static final String ADMIN_USER = env("MYSQL_IT_USERNAME", "root");
    private static final String ADMIN_PASSWORD = env("MYSQL_IT_PASSWORD", "123456");

    private static final DateTimeFormatter DB_NAME_TIME = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");
    private static final Pattern MIGRATION_FILE = Pattern.compile("^V(\\d+(?:\\.\\d+)*)__.+\\.sql$");
    private static final SecureRandom RANDOM = new SecureRandom();

    /** 上下文（含 Flyway migrate）开始前的墙钟，用于报告启动 + 迁移总耗时。 */
    private static final long START_NANOS = System.nanoTime();

    /** 一次性测试库名；在上下文装配阶段（{@link DynamicPropertySource}）确定。 */
    private static volatile String databaseName;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private EntityManagerFactory entityManagerFactory;

    // ------------------------------------------------------------------
    // 上下文装配：建一次性库 + 把 mysql profile 的数据源指过去
    // ------------------------------------------------------------------

    @DynamicPropertySource
    static void mysqlProperties(DynamicPropertyRegistry registry) {
        createDatabase();

        registry.add("spring.datasource.url", MySqlFlywayMigrationTest::jdbcUrl);
        registry.add("spring.datasource.username", () -> ADMIN_USER);
        registry.add("spring.datasource.password", () -> ADMIN_PASSWORD);
        registry.add("spring.datasource.driver-class-name", () -> "com.mysql.cj.jdbc.Driver");

        // 显式声明本测试依赖的契约，不依赖 profile 继承关系被后人改坏：
        registry.add("spring.flyway.enabled", () -> "true");
        registry.add("spring.flyway.locations",
                () -> "classpath:db/migration,classpath:db/migration-mysql");
        registry.add("spring.flyway.baseline-on-migrate", () -> "true");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
    }

    /**
     * 创建一个唯一的一次性库。库创建失败时直接把「连不上 / 认证失败 / 权限不足」的事实
     * 带环境变量名抛出，而不是留一个看不懂的 Hikari 超时。
     */
    private static void createDatabase() {
        String base = "travel_agent_it_" + LocalDateTime.now().format(DB_NAME_TIME);
        String candidate = base;
        try (Connection connection = DriverManager.getConnection(serverUrl(), ADMIN_USER, ADMIN_PASSWORD)) {
            // 同一秒并发跑两次时避免互相砸库：只追加后缀，绝不 DROP 别人的库
            for (int attempt = 0; attempt < 5 && schemaExists(connection, candidate); attempt++) {
                candidate = base + "_" + String.format("%04x", RANDOM.nextInt(0x10000));
            }
            if (schemaExists(connection, candidate)) {
                throw new IllegalStateException("无法获得唯一的一次性库名（连续 5 次重名）: " + candidate);
            }
            try (Statement statement = connection.createStatement()) {
                statement.execute("CREATE DATABASE `" + candidate + "` "
                        + "CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci");
            }
            databaseName = candidate;
        } catch (SQLException e) {
            throw new IllegalStateException(
                    "无法在 MySQL 实例 " + HOST + ":" + PORT + " 上创建一次性库。"
                            + "请确认实例已启动、MYSQL_IT_HOST/MYSQL_IT_PORT 正确、"
                            + "MYSQL_IT_USERNAME（" + ADMIN_USER + "）具备 CREATE DATABASE 权限。"
                            + "原始错误: " + e.getMessage(), e);
        }
    }

    private static boolean schemaExists(Connection connection, String schema) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT COUNT(*) FROM information_schema.schemata WHERE schema_name = ?")) {
            ps.setString(1, schema);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() && rs.getInt(1) > 0;
            }
        }
    }

    private static String serverUrl() {
        return "jdbc:mysql://" + HOST + ":" + PORT + "/mysql"
                + "?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai"
                + "&useSSL=false&allowPublicKeyRetrieval=true";
    }

    private static String jdbcUrl() {
        String name = databaseName;
        if (name == null) {
            throw new IllegalStateException("一次性库尚未创建：@DynamicPropertySource 未执行？");
        }
        return "jdbc:mysql://" + HOST + ":" + PORT + "/" + name
                + "?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai"
                + "&useSSL=false&allowPublicKeyRetrieval=true";
    }

    // ------------------------------------------------------------------
    // 唯一的测试方法：一次跑完迁移断言、结构断言、实体校验
    // ------------------------------------------------------------------

    @Test
    @DisplayName("空库全量 Flyway 迁移（V1..V23 MySQL 链）+ Hibernate validate 21 个实体")
    void freshDatabaseAppliesEveryMigrationAndMatchesEntities() throws Exception {
        String schema = jdbc.queryForObject("SELECT DATABASE()", String.class);
        assertThat(schema)
                .as("测试必须运行在本次创建的一次性库上（实际 schema=%s）", schema)
                .isEqualTo(databaseName);

        String server = serverVersion();
        assertThat(server)
                .as("目标数据库应为真实 MySQL（H2 上跑本测试没有意义）")
                .containsIgnoringCase("MySQL");

        // 1) flyway_schema_history：版本全覆盖、全部 success、无 failed
        List<AppliedMigration> history = jdbc.query(
                "SELECT version, description, execution_time, success "
                        + "FROM flyway_schema_history ORDER BY installed_rank",
                (rs, rowNum) -> new AppliedMigration(
                        rs.getString("version"),
                        rs.getString("description"),
                        rs.getLong("execution_time"),
                        rs.getInt("success") == 1));
        assertThat(history)
                .as("flyway_schema_history 为空：Flyway 没有执行（检查 spring.flyway.enabled / locations）")
                .isNotEmpty();

        List<AppliedMigration> failed = history.stream().filter(m -> !m.success()).toList();
        assertThat(failed)
                .as("存在 success=0 的失败迁移: %s", failed)
                .isEmpty();

        Set<String> expectedVersions = expectedMigrationVersions();
        Set<String> appliedVersions = history.stream()
                .map(AppliedMigration::version)
                .filter(v -> v != null && !v.isBlank())
                .collect(Collectors.toCollection(TreeSet::new));

        assertThat(expectedVersions)
                .as("classpath 上 MySQL 迁移链的版本集合（V1..V13 + V15..V23，V14 仅 H2）")
                .containsAll(DOCUMENTED_MYSQL_VERSIONS);
        assertThat(expectedVersions)
                .as("V14 是 H2 专属脚本，MySQL locations 不应包含它")
                .doesNotContain("14");

        assertThat(appliedVersions)
                .as("已应用迁移版本与 classpath 脚本不一致；history=%s expected=%s",
                        appliedVersions, expectedVersions)
                .isEqualTo(expectedVersions);
        assertThat(history)
                .as("迁移行数应等于 classpath 上的迁移脚本数（同一版本在多个 location 出现会被 Flyway 拒绝）")
                .hasSize(expectedVersions.size());

        Integer failedCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM flyway_schema_history WHERE success = 0", Integer.class);
        assertThat(failedCount)
                .as("flyway_schema_history 中 success=0 的行数")
                .isZero();

        // 2) 关键表：21 个实体映射的表必须全部存在
        Set<String> tables = jdbc.queryForList(
                        "SELECT table_name FROM information_schema.tables "
                                + "WHERE table_schema = ? AND table_type = 'BASE TABLE'",
                        String.class, schema)
                .stream().map(String::toLowerCase).collect(Collectors.toCollection(TreeSet::new));
        for (String table : MAPPED_TABLES) {
            assertThat(tables)
                    .as("表 `%s` 应存在（schema=%s，由 db/migration* 建出）", table, schema)
                    .contains(table);
        }

        // 3) 关键列：V13（MySQL 方言补齐）/ V16 / V17 / V18 / V19 / V21 / V23 的产物（V22 只加唯一索引）
        REQUIRED_COLUMNS.forEach((table, columns) -> {
            for (String column : columns) {
                assertColumnExists(schema, table, column);
            }
        });

        // 4) 关键索引：V13 的索引补齐 + V15/V19/V21/V22/V23 的新索引 + V8/V11 的唯一键
        REQUIRED_INDEXES.forEach((table, indexes) -> {
            for (String index : indexes) {
                assertIndexExists(schema, table, index);
            }
        });

        // 5) Hibernate 元模型：21 个实体；上下文启动本身已由 ddl-auto=validate 证明结构一致
        Set<String> entityNames = entityManagerFactory.getMetamodel().getEntities().stream()
                .map(EntityType::getName)
                .collect(Collectors.toCollection(TreeSet::new));
        assertThat(entityNames)
                .as("Hibernate 元模型实体数（上下文启动时 validate 已逐列校验）")
                .hasSize(MAPPED_ENTITIES.size());
        assertThat(entityNames)
                .as("实体清单应与 entity/ 目录一致（新增实体时同步维护本测试）")
                .containsExactlyInAnyOrderElementsOf(MAPPED_ENTITIES);

        // 6) 证据输出：mvn / CI 控制台可直接留档
        long migrationMillis = history.stream().mapToLong(AppliedMigration::executionTime).sum();
        long contextMillis = (System.nanoTime() - START_NANOS) / 1_000_000;
        System.out.println("[mysql-it] schema=" + schema);
        System.out.println("[mysql-it] server=" + server);
        System.out.println("[mysql-it] flyway=" + flywayVersion());
        System.out.println("[mysql-it] applied=" + history.size() + " migrations, "
                + "sum(execution_time)=" + migrationMillis + "ms, "
                + "context startup incl. migrate=" + contextMillis + "ms");
        for (AppliedMigration migration : history) {
            System.out.printf("[mysql-it]   V%-3s %-42s %5dms success=%s%n",
                    migration.version(), migration.description(),
                    migration.executionTime(), migration.success());
        }
        System.out.println("[mysql-it] tables=" + tables.size() + " " + tables);
        System.out.println("[mysql-it] entities=" + entityNames.size());
        System.out.println("[mysql-it] 证据库保留（不 DROP）: " + schema);
    }

    // ------------------------------------------------------------------
    // 断言辅助
    // ------------------------------------------------------------------

    private void assertColumnExists(String schema, String table, String column) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM information_schema.columns "
                        + "WHERE table_schema = ? AND table_name = ? AND column_name = ?",
                Integer.class, schema, table, column);
        assertThat(count)
                .as("列 `%s.%s` 应存在（schema=%s；检查对应 V* 迁移是否漏建该列）",
                        table, column, schema)
                .isPositive();
    }

    private void assertIndexExists(String schema, String table, String index) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM information_schema.statistics "
                        + "WHERE table_schema = ? AND table_name = ? AND index_name = ?",
                Integer.class, schema, table, index);
        assertThat(count)
                .as("索引 `%s`（表 `%s`，schema=%s）应存在（检查 V13 索引补齐或 V15/V19/V21 建表脚本）",
                        index, table, schema)
                .isPositive();
    }

    private String serverVersion() throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            DatabaseMetaData metaData = connection.getMetaData();
            return metaData.getDatabaseProductName() + " " + metaData.getDatabaseProductVersion();
        }
    }

    private static String flywayVersion() {
        Package pkg = Flyway.class.getPackage();
        String version = pkg == null ? null : pkg.getImplementationVersion();
        if (version != null && !version.isBlank()) {
            return version;
        }
        // flyway-core 的普通 jar 没有 Implementation-Version，改读 Maven 元数据（Boot BOM 解析后的实际版本）
        try (InputStream in = Flyway.class.getResourceAsStream(
                "/META-INF/maven/org.flywaydb/flyway-core/pom.properties")) {
            if (in != null) {
                Properties properties = new Properties();
                properties.load(in);
                String fromPom = properties.getProperty("version");
                if (fromPom != null && !fromPom.isBlank()) {
                    return fromPom;
                }
            }
        } catch (IOException ignored) {
            // 读不到就落到 unknown，不影响测试结论
        }
        return "unknown";
    }

    /**
     * 从 classpath 枚举 MySQL 迁移链上的脚本版本。
     *
     * <p>用 Spring 的 {@link PathMatchingResourcePatternResolver} 同时支持
     * {@code target/classes}（目录）与打好的 jar；只匹配 {@code V<digits>__*.sql}，
     * 天然排除 {@code R__repeatable.sql} 与 H2 目录。
     */
    private static Set<String> expectedMigrationVersions() {
        PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
        Set<String> versions = new TreeSet<>();
        for (String pattern : List.of(
                "classpath*:db/migration/V*__*.sql",
                "classpath*:db/migration-mysql/V*__*.sql")) {
            Resource[] resources;
            try {
                resources = resolver.getResources(pattern);
            } catch (IOException e) {
                throw new IllegalStateException("无法枚举迁移脚本: " + pattern, e);
            }
            for (Resource resource : resources) {
                String filename = resource.getFilename();
                Matcher matcher = MIGRATION_FILE.matcher(filename == null ? "" : filename);
                if (matcher.matches()) {
                    versions.add(matcher.group(1));
                }
            }
        }
        return versions;
    }

    private static List<String> documentedMysqlVersions() {
        List<String> versions = new ArrayList<>();
        for (int version = 1; version <= 23; version++) {
            if (version != 14) { // V14 是 H2 专属（llm_quota_usage 唯一键）
                versions.add(String.valueOf(version));
            }
        }
        return versions;
    }

    private static Map<String, List<String>> requiredColumns() {
        Map<String, List<String>> required = new LinkedHashMap<>();
        // V13（MySQL 方言补丁）
        addRequired(required, "trip", "version", "departure_reminder_dismissed_at");
        addRequired(required, "schedule_item",
                "recurrence", "repeat_until", "reminder_dismissed_at", "sched_date");
        addRequired(required, "trip_photo",
                "trip_id", "user_id", "stored_name", "original_name", "content_type", "size_bytes");
        // V15 追踪表
        addRequired(required, "agent_run", "run_id", "user_id", "status");
        addRequired(required, "agent_task",
                "task_id", "run_id", "depends_on", "attempt", "replaces_task_id");
        addRequired(required, "tool_call_trace", "run_id", "tool_name", "success");
        // V16 计划执行台账
        addRequired(required, "pending_plan", "content_hash", "status", "version");
        addRequired(required, "plan_execution",
                "execution_id", "plan_id", "status", "modules_json");
        addRequired(required, "schedule_item", "source_key");
        // V17 同行人数
        addRequired(required, "trip", "party_size");
        // V19 旅行闭环
        addRequired(required, "itinerary_item",
                "status", "started_at", "completed_at", "visited_at");
        addRequired(required, "trip_event",
                "trip_id", "itinerary_item_id", "event_type", "source", "summary", "detail",
                "occurred_at", "created_at");
        addRequired(required, "schedule_item",
                "trip_id", "itinerary_item_id", "source", "travel_day");
        addRequired(required, "memo", "trip_id", "itinerary_item_id", "source", "travel_day");
        // V21 会话作用域
        addRequired(required, "conversation_message", "scope");
        // V23 会话轮次序号（并发成组）与会话元数据
        addRequired(required, "conversation_message", "turn_seq");
        addRequired(required, "conversation_meta", "user_id", "conversation_id", "title", "updated_at");
        // 其他实体基线列（证明表不是空壳）
        addRequired(required, "users", "username", "password", "nickname");
        addRequired(required, "rag_document", "doc_key", "fingerprint", "user_id");
        addRequired(required, "rag_document_version", "document_id", "version_no", "status");
        addRequired(required, "rag_chunk", "document_id", "version_id", "content");
        addRequired(required, "llm_quota_usage", "user_name", "usage_date", "used_count");
        addRequired(required, "conversation_message", "user_id", "conversation_id", "created_at");
        addRequired(required, "expense", "user_id", "trip_id", "amount");
        addRequired(required, "memo", "user_id", "done");
        addRequired(required, "operation_history", "trip_id", "status");
        return required;
    }

    private static Map<String, List<String>> requiredIndexes() {
        Map<String, List<String>> required = new LinkedHashMap<>();
        // V13 索引补齐
        addRequired(required, "trip_photo", "idx_trip_photo_trip", "idx_trip_photo_user");
        addRequired(required, "rag_document", "idx_rag_document_user", "uk_rag_document_key");
        addRequired(required, "pending_plan", "idx_pending_plan_user", "idx_pending_plan_created");
        addRequired(required, "llm_quota_usage", "idx_llm_quota_date", "uk_llm_quota_user_date");
        addRequired(required, "conversation_message", "idx_conv_created_at", "idx_conv_user_scope");
        addRequired(required, "operation_history", "idx_history_trip_status");
        // V9 列表查询复合索引
        addRequired(required, "trip", "idx_trip_user_start_date");
        addRequired(required, "expense", "idx_expense_user_spent_at");
        // V15 追踪索引
        addRequired(required, "agent_run", "idx_agent_run_user");
        addRequired(required, "agent_task", "idx_agent_task_run");
        addRequired(required, "tool_call_trace", "idx_tool_trace_run", "idx_tool_trace_user");
        // V16 计划执行索引
        addRequired(required, "plan_execution",
                "idx_plan_execution_user", "idx_plan_execution_trip");
        // V19 事件流与旅行关联索引
        addRequired(required, "trip_event",
                "idx_trip_event_trip", "idx_trip_event_item", "idx_trip_event_occurred");
        addRequired(required, "schedule_item", "idx_schedule_trip");
        // V22 同步日程幂等唯一键（建索引前按 source_key 去重历史重复）
        addRequired(required, "schedule_item", "uk_schedule_source_key");
        // V23 会话轮次唯一键（并发 compare-and-set 的兜底）与会话元数据唯一键
        addRequired(required, "conversation_message", "uk_conv_turn_role");
        addRequired(required, "conversation_meta", "uk_conv_meta_user_conv");
        addRequired(required, "memo", "idx_memo_trip");
        return required;
    }

    /** 同名表分多条迁移补列/补索引时累加，而不是后写覆盖前写。 */
    private static void addRequired(Map<String, List<String>> target, String table, String... names) {
        target.computeIfAbsent(table, ignored -> new ArrayList<>()).addAll(List.of(names));
    }

    private static String env(String name, String defaultValue) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? defaultValue : value.trim();
    }

    /** flyway_schema_history 的一行（只取本测试关心的列）。 */
    private record AppliedMigration(String version, String description,
                                    long executionTime, boolean success) {
    }
}