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
 * 默认 profile（H2）的迁移链真实执行：V1..V21+ 在全新库上跑完，且 V22 的唯一索引真的建出来。
 *
 * <p>为什么需要：其它测试用 {@code ddl-auto=create-drop} 且关闭 Flyway，覆盖不到迁移脚本；
 * 而默认 profile 是「不装数据库也能跑」的主路径，迁移脚本里的一句方言错误
 * 就会让所有首次启动直接失败。这里用内存 H2 + 与应用相同的 locations 跑一遍，
 * 只做结构断言，不启动 Spring。
 */
class H2FlywayMigrationTest {

    private static final String INDEX_NAME = "uk_schedule_source_key";

    @Test
    @DisplayName("全新 H2 库跑完默认 profile 的全部迁移，并创建 schedule_item.source_key 唯一索引")
    void migrationsApplyOnFreshH2Database() throws Exception {
        // 与 application.yml 的默认 datasource 保持同一模式（MODE=MySQL），内存库避免落盘
        String url = "jdbc:h2:mem:flyway-" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1";

        MigrateResult result = Flyway.configure()
                .dataSource(url, "sa", "")
                .locations("classpath:db/migration", "classpath:db/migration-h2")
                .load()
                .migrate();

        assertThat(result.success).isTrue();
        // 默认 profile 的 locations 里 H2 侧共有 20 个脚本（V13/V14 只存在于方言目录）。
        // 这里不锁死具体数字，只保证不是「一个都没跑」；真正的结构断言在下面。
        assertThat(result.migrationsExecuted).isGreaterThanOrEqualTo(20);

        try (Connection connection = DriverManager.getConnection(url, "sa", "");
             ResultSet indexes = uniqueIndexes(connection)) {
            boolean found = false;
            while (indexes.next()) {
                if (INDEX_NAME.equalsIgnoreCase(indexes.getString("INDEX_NAME"))) {
                    found = true;
                }
            }
            assertThat(found)
                    .as("V22 必须创建 %s，否则同步日程的并发幂等仍然只有 check-then-act", INDEX_NAME)
                    .isTrue();
        }
    }

    private static ResultSet uniqueIndexes(Connection connection) throws Exception {
        DatabaseMetaData metaData = connection.getMetaData();
        return metaData.getIndexInfo(null, "PUBLIC", "SCHEDULE_ITEM", true, false);
    }
}