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
 * 默认 profile（H2）的迁移链真实执行：V1..V23 在全新库上跑完，
 * 且 V22 / V23 的唯一约束真的建出来。
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

    @Test
    @DisplayName("全新 H2 库跑完默认 profile 的全部迁移，并创建 V22 / V23 的唯一约束")
    void migrationsApplyOnFreshH2Database() throws Exception {
        // 与 application.yml 的默认 datasource 保持同一模式（MODE=MySQL），内存库避免落盘
        String url = "jdbc:h2:mem:flyway-" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1";

        MigrateResult result = Flyway.configure()
                .dataSource(url, "sa", "")
                .locations("classpath:db/migration", "classpath:db/migration-h2")
                .load()
                .migrate();

        assertThat(result.success).isTrue();
        // 默认 profile 的 locations 里 H2 侧共有 21 个脚本（V13/V14 只存在于方言目录）。
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
        }
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