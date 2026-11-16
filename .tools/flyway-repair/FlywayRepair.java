import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.flywaydb.core.api.MigrationInfoService;
import org.flywaydb.core.api.MigrationVersion;

/**
 * 独立 Flyway 运维小工具（batch10 schema 单一权威）——不属于 Spring Boot 应用，不参与 mvn 构建。
 *
 * 用途：在应用之外执行 info / validate / repair / migrate。
 * 典型场景：某个已发布的迁移脚本发生了「语法错误修复」（只改注释/空白，语义不变），
 *          已应用过它的库会出现 "Migration checksum mismatch"，此时用 repair 把
 *          flyway_schema_history 里记录的 checksum 对齐到当前脚本（不重跑、不动数据）。
 *
 * 用法（classpath 由 run.ps1 组装；也可直接 java -cp ... FlywayRepair.java ...）：
 *   FlywayRepair info     <jdbcUrl> <user> <password> <locations>
 *   FlywayRepair validate <jdbcUrl> <user> <password> <locations>
 *   FlywayRepair repair   <jdbcUrl> <user> <password> <locations>
 *   FlywayRepair migrate  <jdbcUrl> <user> <password> <locations> [targetVersion]
 *
 * locations 用逗号分隔，例如
 *   filesystem:D:/travel-agent/travel-agent-server/src/main/resources/db/migration,filesystem:.../db/migration-mysql
 * 或（打包后）classpath:db/migration,classpath:db/migration-mysql
 *
 * 说明：本工具只调用 Flyway 公开 API；repair 只重写 schema history 元数据（checksum/状态），
 *       不执行 DDL、不改业务数据。执行前请确认脚本差异确实只是注释/空白这类无语义变更。
 */
public class FlywayRepair {

    public static void main(String[] args) {
        if (args.length < 5) {
            System.err.println("usage: FlywayRepair <info|validate|repair|migrate> <jdbcUrl> <user> <password> <locations> [targetVersion]");
            System.exit(2);
        }
        String command = args[0];
        String url = args[1];
        String user = args[2];
        // "-" means an empty password (easier to pass through PowerShell/CI wrappers than "")
        String password = "-".equals(args[3]) ? "" : args[3];
        String[] locations = args[4].split(",");

        try {
            org.flywaydb.core.api.configuration.FluentConfiguration cfg = Flyway.configure()
                    .dataSource(url, user, password)
                    .locations(locations)
                    .cleanDisabled(true);

            if ("migrate".equals(command) && args.length >= 6 && !args[5].isBlank()) {
                cfg = cfg.target(MigrationVersion.fromVersion(args[5]));
            }
            Flyway flyway = cfg.load();

            switch (command) {
                case "info" -> printInfo(flyway);
                case "validate" -> {
                    flyway.validate();
                    System.out.println("[OK] validate passed - schema history matches the resolved migrations");
                    printInfo(flyway);
                }
                case "repair" -> {
                    flyway.repair();
                    System.out.println("[OK] repair done - applied migrations were realigned with the current scripts");
                    System.out.println("     (no DDL executed, no business data touched)");
                    printInfo(flyway);
                }
                case "migrate" -> {
                    flyway.migrate();
                    System.out.println("[OK] migrate done");
                    printInfo(flyway);
                }
                default -> {
                    System.err.println("unknown command: " + command);
                    System.exit(2);
                }
            }
        } catch (Exception e) {
            System.err.println("[FAIL] " + command + ": " + e.getClass().getName());
            System.err.println("       " + String.valueOf(e.getMessage()).replace("\n", "\n       "));
            System.exit(1);
        }
    }

    private static void printInfo(Flyway flyway) {
        MigrationInfoService info = flyway.info();
        System.out.println("schema history:");
        for (MigrationInfo mi : info.all()) {
            System.out.printf("  version=%-4s state=%-10s checksum=%-12s description=%s%n",
                    mi.getVersion() == null ? "-" : mi.getVersion().getVersion(),
                    mi.getState() == null ? "-" : mi.getState().name(),
                    String.valueOf(mi.getChecksum()),
                    mi.getDescription() == null ? "" : mi.getDescription());
        }
        System.out.println("  current=" + (info.current() == null ? "none" : info.current().getVersion()));
    }
}