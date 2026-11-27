package com.travel.agent;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.output.MigrateResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 默认 profile（H2）的迁移链真实执行：V1..V32 在全新库上跑完，
 * 且 V22 / V23 的唯一约束、V30 的记账解绑结构、V31 的日程域历史结构、V32 的照片用途列真的建出来。
 *
 * <p>为什么需要：其它测试用 {@code ddl-auto=create-drop} 且关闭 Flyway，覆盖不到迁移脚本；
 * 而默认 profile 是「不装数据库也能跑」的主路径，迁移脚本里的一句方言错误
 * 就会让所有首次启动直接失败。这里用内存 H2 + 与应用相同的 locations 跑一遍，
 * 只做结构断言，不启动 Spring。
 */
class H2FlywayMigrationTest {

    private static final String V22_INDEX = "uk_schedule_source_key";
    private static final String V23_TURN_INDEX = "uk_conv_turn_role";
    private static final String V23_META_INDEX = "uk_conv_meta_user_conv";
    private static final String V30_INDEX = "idx_expense_schedule";
    private static final String V31_INDEX = "idx_history_user_domain";

    @Test
    @DisplayName("全新 H2 库跑完默认 profile 的全部迁移，并创建 V22 / V23 唯一约束与 V30 / V31 / V32 结构")
    void migrationsApplyOnFreshH2Database() throws Exception {
        // 与 application.yml 的默认 datasource 保持同一模式（MODE=MySQL），内存库避免落盘
        String url = "jdbc:h2:mem:flyway-" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1";

        MigrateResult result = Flyway.configure()
                .dataSource(url, "sa", "")
                .locations("classpath:db/migration", "classpath:db/migration-h2")
                .load()
                .migrate();

        assertThat(result.success).isTrue();
        // 默认 profile 的 locations 里 H2 侧共有 28 个脚本（V8/V12 只在 MySQL 目录，V13/V14 只在 H2 方言目录）。
        // 这里不锁死具体数字，只保证不是「一个都没跑」；真正的结构断言在下面。
        assertThat(result.migrationsExecuted).isGreaterThanOrEqualTo(20);

        try (Connection connection = DriverManager.getConnection(url, "sa", "")) {
            assertThat(uniqueIndexNames(connection, "SCHEDULE_ITEM"))
                    .as("V22 必须创建 %s，否则同步日程的并发幂等仍然只有 check-then-act", V22_INDEX)
                    .contains(V22_INDEX);
            assertThat(uniqueIndexNames(connection, "CONVERSATION_MESSAGE"))
                    .as("V23 必须创建 %s，否则会话并发时轮次序号只有 check-then-act", V23_TURN_INDEX)
                    .contains(V23_TURN_INDEX);
            assertThat(uniqueIndexNames(connection, "CONVERSATION_META"))
                    .as("V23 必须创建 %s，否则同一会话可能写出两行标题覆盖", V23_META_INDEX)
                    .contains(V23_META_INDEX);

            assertThat(nullableOf(connection, "EXPENSE", "TRIP_ID"))
                    .as("V30 必须把 expense.trip_id 放开为可空，否则没有当前行程时写不进通用账目")
                    .isEqualTo(DatabaseMetaData.columnNullable);
            assertThat(nullableOf(connection, "EXPENSE", "SCHEDULE_ITEM_ID"))
                    .as("V30 必须新增可空的 expense.schedule_item_id（批次 3 发票报销预留）")
                    .isEqualTo(DatabaseMetaData.columnNullable);
            assertThat(indexNames(connection, "EXPENSE"))
                    .as("V30 必须创建 %s，否则按报销条目反查账单退化为全表扫描", V30_INDEX)
                    .contains(V30_INDEX);

            // V31：日程域历史（批量清空日程可撤销）——trip_id 必须可空，domain 列与撤销索引必须在
            assertThat(nullableOf(connection, "OPERATION_HISTORY", "TRIP_ID"))
                    .as("V31 必须把 operation_history.trip_id 放开为可空，否则日程清空历史写不进去")
                    .isEqualTo(DatabaseMetaData.columnNullable);
            assertThat(nullableOf(connection, "OPERATION_HISTORY", "DOMAIN"))
                    .as("V31 必须新增可空的 operation_history.domain（存量行由 DEFAULT 'ITINERARY' 回填）")
                    .isEqualTo(DatabaseMetaData.columnNullable);
            assertThat(indexNames(connection, "OPERATION_HISTORY"))
                    .as("V31 必须创建 %s，否则按用户取最近一条日程历史退化为全表扫描", V31_INDEX)
                    .contains(V31_INDEX);

            // V32：照片用途（发票报销复用照片通道）——kind 列必须建出来，存量行由 DEFAULT 'PHOTO' 回填
            assertThat(nullableOf(connection, "TRIP_PHOTO", "KIND"))
                    .as("V32 必须新增可空的 trip_photo.kind（存量行由 DEFAULT 'PHOTO' 回填）")
                    .isEqualTo(DatabaseMetaData.columnNullable);
        }
    }

    private static int nullableOf(Connection connection, String table, String column) throws Exception {
        DatabaseMetaData metaData = connection.getMetaData();
        try (ResultSet columns = metaData.getColumns(null, "PUBLIC", table, column)) {
            if (!columns.next()) {
                throw new AssertionError("列不存在: " + table + "." + column);
            }
            return columns.getInt("NULLABLE");
        }
    }

    private static java.util.Set<String> indexNames(Connection connection, String table) throws Exception {
        java.util.Set<String> names = new java.util.LinkedHashSet<>();
        DatabaseMetaData metaData = connection.getMetaData();
        try (ResultSet indexes = metaData.getIndexInfo(null, "PUBLIC", table, false, false)) {
            while (indexes.next()) {
                String name = indexes.getString("INDEX_NAME");
                if (name != null) {
                    names.add(name.toLowerCase(java.util.Locale.ROOT));
                }
            }
        }
        return names;
    }

    private static java.util.Set<String> uniqueIndexNames(Connection connection, String table) throws Exception {
        java.util.Set<String> names = new java.util.LinkedHashSet<>();
        DatabaseMetaData metaData = connection.getMetaData();
        java.util.Set<String> expected = java.util.Set.of(V22_INDEX, V23_TURN_INDEX, V23_META_INDEX);
        try (ResultSet indexes = metaData.getIndexInfo(null, "PUBLIC", table, true, false)) {
            while (indexes.next()) {
                String name = indexes.getString("INDEX_NAME");
                if (name == null) {
                    continue;
                }
                // H2 把未加引号的标识符折叠成大写（PRIMARY_KEY_694 / UK_CONV_TURN_ROLE），
                // 断言按小写比较，避免「索引明明建了却因为大小写判失败」
                String lower = name.toLowerCase(java.util.Locale.ROOT);
                names.add(expected.contains(lower) ? lower : name);
            }
        }
        return names;
    }
}