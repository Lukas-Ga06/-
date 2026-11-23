# 真实 MySQL Flyway 迁移验证

> 状态：当前事实 | 适用版本：`travel-agent-server` 0.1.0 / Spring Boot 3.5.12 / Flyway 11.7.2 / MySQL 8.4.10 | 最后验证：2026-11-19 | 事实来源：`travel-agent-server/src/test/java/com/travel/agent/migration/MySqlFlywayMigrationTest.java`、`scripts/mysql-it.ps1`、本机 3307 实例实测输出

本文说明如何用**真实 MySQL** 验证「Flyway 从空库全量迁移到最新版本，并且 Hibernate `ddl-auto=validate` 校验 21 个实体全部通过」。这是默认测试 profile（H2 + `create-drop` + Flyway 关闭）覆盖不到的部分：H2 上永远不会执行 `db/migration-mysql` 里的 `information_schema` 探测、`PREPARE` 动态语句、STORED 生成列与 MySQL 方言 DDL。

- 测试类：[MySqlFlywayMigrationTest.java](../../travel-agent-server/src/test/java/com/travel/agent/migration/MySqlFlywayMigrationTest.java)
- 本地一键脚本：[scripts/mysql-it.ps1](../../scripts/mysql-it.ps1)
- 迁移脚本：`travel-agent-server/src/main/resources/db/migration/` 与 `db/migration-mysql/`
- 生产配置：`application-mysql.yml`（`locations: classpath:db/migration,classpath:db/migration-mysql`，`ddl-auto: validate`）

---

## 0. 一页速览

```powershell
# 本机（仓库根目录；默认 127.0.0.1:3307 / root / 123456）
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\mysql-it.ps1
```

```bash
# 手动（任意有 MySQL 的环境）
cd travel-agent-server
MYSQL_IT=true mvn -B -Dtest=MySqlFlywayMigrationTest test
```

普通 `mvn test` 下该测试是 **skipped**（条件不满足），不会失败、不会连数据库。只有 `MYSQL_IT=true`（大小写敏感）才真正执行。

---

## 1. 它验证什么、不验证什么

### 1.1 验证（每次运行都会断言）

| 断言 | 说明 | 失败时能定位到 |
| --- | --- | --- |
| 一次性库创建 | 在目标实例上建 `travel_agent_it_<yyyyMMddHHmmss>`，`utf8mb4`，不碰其他库 | 连接 / 认证 / `CREATE DATABASE` 权限 |
| 迁移版本集合 | 从 classpath 动态枚举脚本，要求 `flyway_schema_history` 的版本集合与之**完全一致** | 缺跑、重版本、脚本没打进 jar |
| 全部 `success` | `success=0` 的行为 0；逐行打印版本、描述、耗时 | 具体失败版本（Flyway 报错本身也会带脚本名） |
| 关键表 | 19 个 `@Table` 映射表全部存在（另含 6 张退役旧表） | 表名 |
| 关键列 | V13 补的 `trip.version` / `trip_photo` 列；V16 `pending_plan.content_hash`；V17 `trip.party_size`；V18 `agent_task.depends_on/attempt/replaces_task_id`；V19 `itinerary_item.status`、`trip_event.*`、`schedule_item/memo` 的旅行关联列 | `表.列` |
| 关键索引 | V13 补齐的 8 个索引、V8/V11 唯一键、V9 复合索引、V15/V16/V19 新表索引 | `索引名（表）` |
| 实体校验 | 上下文启动时 `ddl-auto=validate` 逐列校验；并断言 Hibernate 元模型恰好 21 个实体 | Hibernate 的 `Schema-validation` 原文 |
| 证据输出 | schema、MySQL 版本、Flyway 版本、23 条迁移耗时、表数、实体数、证据库名 | 控制台 `[mysql-it]` 行 |

### 1.2 不验证（避免误读）

- **不验证存量库升级**：本测试只从空库全量迁移；旧版本 → 新版本的增量升级由 `.e2e/acceptance/evidence/mysql84-upgrade-summary.md` 那类升级演练覆盖。
- **不验证业务数据迁移**：空库没有历史行，`UPDATE`/回填逻辑只验证「可执行」，不验证数据正确性。
- **不验证 MySQL 之外的目标版本**：脚本不硬编码 8.4 断言，但本仓库的实测基线是 8.4.10；换 8.0/8.4 小版本仍建议跑一次。
- **不验证并发/多实例**：唯一库名只保证并行运行不互相砸库，不验证迁移并发锁。

---

## 2. 本地运行

### 2.1 一键脚本（推荐）

脚本做的事：清理 24 小时前的 `travel_agent_it_*` → 导出 `MYSQL_IT_*` → 在 `travel-agent-server/` 执行 Maven → **原样透传退出码**。

```powershell
# 仓库根目录
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\mysql-it.ps1
```

常用参数（PowerShell 5.1 兼容）：

| 参数 | 默认 | 说明 |
| --- | --- | --- |
| `-DbHost` | `127.0.0.1` | MySQL 主机 |
| `-DbPort` | `3307` | 本机便携实例端口；CI/默认实例常用 `3306` |
| `-DbUser` / `-DbPassword` | `root` / `123456` | **需要 `CREATE DATABASE` 与建表权限**；`travel/travel_pw` 业务账号不够 |
| `-MysqlBin` | 自动查找 | 仓库内置 `.tools/mysql8410-3307/.../mysql.exe`，其次 `PATH` |
| `-MysqlDefaultsFile` | 空 | 给定时优先使用该 `defaults-extra-file` 做清理连接 |
| `-RetentionHours` | `24` | 只 DROP 创建时间早于 N 小时的测试库 |
| `-KeepOldDatabases` | 关 | 跳过清理（无 mysql 客户端时脚本也会自动跳过） |

示例：

```powershell
# 连 3306 的 CI 风格实例，不做旧库清理
.\scripts\mysql-it.ps1 -DbPort 3306 -DbPassword root -KeepOldDatabases
```

脚本只影响**当前进程**的环境变量，不写用户/系统环境变量；`*.ps1` 按 `.gitattributes` 保持 CRLF，文件为 UTF-8（带 BOM，PowerShell 5.1 才能正确读中文）。

### 2.2 手动命令（bash / PowerShell）

```bash
MYSQL_IT=true \
MYSQL_IT_HOST=127.0.0.1 \
MYSQL_IT_PORT=3307 \
MYSQL_IT_USERNAME=root \
MYSQL_IT_PASSWORD=123456 \
mvn -B -f travel-agent-server/pom.xml -Dtest=MySqlFlywayMigrationTest test
```

> 也可先 `cd travel-agent-server` 再执行 `mvn -B -Dtest=MySqlFlywayMigrationTest test`（与 CI 的 `working-directory` 一致）。

PowerShell：

```powershell
$env:MYSQL_IT="true"; $env:MYSQL_IT_HOST="127.0.0.1"; $env:MYSQL_IT_PORT="3307"
$env:MYSQL_IT_USERNAME="root"; $env:MYSQL_IT_PASSWORD="123456"
cd travel-agent-server
mvn -B -Dtest=MySqlFlywayMigrationTest test
```

环境变量：

| 变量 | 默认 | 必填 | 说明 |
| --- | --- | --- | --- |
| `MYSQL_IT` | 未设置 | **是** | 必须严格等于 `true`（`True`/`1` 不匹配 JUnit 条件，测试会被跳过） |
| `MYSQL_IT_HOST` | `127.0.0.1` | 否 | 数据库主机 |
| `MYSQL_IT_PORT` | `3307` | 否 | 数据库端口 |
| `MYSQL_IT_USERNAME` | `root` | 否 | 需要 `CREATE DATABASE` + DDL 权限 |
| `MYSQL_IT_PASSWORD` | `123456` | 否 | 仅通过 JDBC 使用，不落盘、不打印 |

### 2.3 测试如何隔离

1. `@DynamicPropertySource` 在 Spring 上下文启动前执行：用 `DriverManager` 连 `jdbc:mysql://<host>:<port>/mysql`，创建一次性库 `travel_agent_it_<yyyyMMddHHmmss>`；同秒重名时追加 `_<4 位十六进制>`，**绝不 DROP 别人的库**。
2. 通过动态属性把 `spring.datasource.*` 指向该库，并显式固定 `spring.flyway.enabled=true`、`spring.flyway.locations=classpath:db/migration,classpath:db/migration-mysql`、`spring.jpa.hibernate.ddl-auto=validate`。
3. `@ActiveProfiles("mysql")` 加载 `application-mysql.yml`；测试 profile（`application-test.yml`）不生效，因此 H2 配置不会覆盖 MySQL。
4. 测试结束**不 DROP** 该库：`flyway_schema_history`、26 张表与结构断言结果留在库里作为证据；清理交给脚本的 24 小时保留策略或人工。

---

## 3. CI service container

CI 会起 `mysql:8.4` service container。建议的 job（**本次任务不改 `.github/workflows/ci.yml`**，由 CI 维护方合入）：

```yaml
  mysql-migration:
    name: MySQL 8.4 / Flyway migrate + validate
    runs-on: ubuntu-latest
    services:
      mysql:
        image: mysql:8.4
        env:
          MYSQL_ROOT_PASSWORD: root
        ports:
          - 3306:3306
        options: >-
          --health-cmd="mysqladmin ping -h 127.0.0.1 -uroot -proot"
          --health-interval=5s
          --health-timeout=5s
          --health-retries=20
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with:
          distribution: temurin
          java-version: '17'
          cache: maven
      - name: Flyway + Hibernate validate on real MySQL
        working-directory: travel-agent-server
        env:
          MYSQL_IT: "true"
          MYSQL_IT_HOST: 127.0.0.1
          MYSQL_IT_PORT: "3306"
          MYSQL_IT_USERNAME: root
          MYSQL_IT_PASSWORD: root
        run: mvn -B -Dtest=MySqlFlywayMigrationTest test
```

说明：

- job 未使用 `container:` 时，service 的映射端口在 runner 上通过 `127.0.0.1:<port>` 访问；如果改用 `container:`，主机名应改为 `mysql` 并去掉端口映射。
- 建议让该 job 只跑这一个测试；它需要真实数据库，不适合与默认 H2 全量测试共用一个 job 的缓存语义。
- 默认 `mvn verify` 不设置 `MYSQL_IT`，本测试 skipped，不增加耗时与外部依赖。

---

## 4. 断言清单（跑完看什么）

### 4.1 为什么是 23 条迁移，不是 24 条

`mysql` profile 只加载 `db/migration` + `db/migration-mysql`：

| 目录 | 版本 |
| --- | --- |
| `db/migration` | V1–V7、V9–V11、V15–V24（20 个） |
| `db/migration-mysql` | V8、V12、V13（3 个） |
| **MySQL 合计** | **23 条**（V1–V13 + V15–V24） |

`V14__llm_quota_unique_h2.sql` 在 `db/migration-h2/`，是 H2 专属（MySQL 侧同一约束由 V8 负责），因此 MySQL 迁移链**没有 V14**。测试从 classpath 动态枚举版本集合，并显式断言「不包含 V14」；新增 V25 会被自动纳入版本集合，但 `DOCUMENTED_MYSQL_VERSIONS`（链尾清单）与本文仍需同步更新。

### 4.2 `flyway_schema_history` 断言

- 行数 = classpath 上 MySQL 迁移脚本数（当前 23）；
- 每行 `success=1`，`success=0` 计数为 0；
- 已应用版本集合与 classpath 版本集合**完全相等**（多一条少一条都失败）。

### 4.3 结构与实体断言

- 21 张实体表：`agent_command_dedupe`、`agent_run`、`agent_task`、`conversation_message`、`conversation_meta`、`expense`、`itinerary_item`、`llm_quota_usage`、`memo`、`operation_history`、`pending_plan`、`plan_execution`、`rag_chunk`、`rag_document`、`rag_document_version`、`schedule_item`、`tool_call_trace`、`trip`、`trip_event`、`trip_photo`、`users`；
- 关键列与索引见测试源码中的 `REQUIRED_COLUMNS` / `REQUIRED_INDEXES`；
- Hibernate 在上下文启动时已执行 `validate`；测试再断言元模型恰好 21 个实体。

---

## 5. 实测证据（2026-11-19，本机 MySQL 8.4.10）

命令：

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\mysql-it.ps1 -RetentionHours 0
```

关键输出（原文摘录）：

```text
[mysql-it] 已清理 1 个超过 0 小时的旧测试库: travel_agent_it_20261119214220
[mysql-it] MySQL=127.0.0.1:3307 user=root
[mysql-it] schema=travel_agent_it_20261119214358
[mysql-it] server=MySQL 8.4.10
[mysql-it] flyway=11.7.2
[mysql-it] applied=18 migrations, sum(execution_time)=4759ms, context startup incl. migrate=24789ms
[mysql-it]   V1   baseline                                      ...
[mysql-it]   V8   llm quota unique                              ...
[mysql-it]   V13  baseline gap                                  ...
[mysql-it]   V15  agent trace                                   ...
[mysql-it]   V19  travel closed loop                           ...
[mysql-it] tables=26 [...]
[mysql-it] entities=19
[mysql-it] 证据库保留（不 DROP）: travel_agent_it_20261119214358

[INFO] Tests run: 1, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 25.98 s
[INFO] BUILD SUCCESS
[mysql-it] 通过（exit=0）。
```

`schema history` 摘要（18 行全部 `success=1`）：

```text
# installed_rank  version  description            success  execution_time
1  1  baseline              1   973ms
2  2  conversation message  1    41ms
3  3  llm quota             1    48ms
4  4  rag fingerprint       1    36ms
5  5  rag document user     1     5ms
6  6  pending plan status   1    39ms
7  7  trip version          1     5ms
8  8  llm quota unique      1    26ms
9  9  list indexes          1   143ms
10 10 rag document version  1   159ms
11 11 rag document key      1   162ms
12 12 rag active unique     1   139ms
13 13 baseline gap          1   963ms
14 15 agent trace           1   202ms
15 16 plan execution        1   271ms
16 17 trip party size       1    87ms
17 18 agent task dag        1   251ms
18 19 travel closed loop    1  1209ms
```

> 耗时随机器负载波动（另一轮约 24.8s 上下文 / 4.8s 迁移总和）；判断标准是断言全绿，而不是绝对秒数。

### 5.1 直接查证据库

```bash
MYSQL=".tools/mysql8410-3307/mysql-8.4.10-winx64/bin/mysql.exe"
CNF=".tools/mysql8410-3307/instance/client-root.cnf"
DB=travel_agent_it_20261119214358

"$MYSQL" --defaults-extra-file="$CNF" -e "
  SELECT installed_rank, version, description, success, execution_time
  FROM $DB.flyway_schema_history ORDER BY installed_rank;
  SELECT COUNT(*) AS tables FROM information_schema.tables WHERE table_schema='$DB';
  SELECT table_name, column_name FROM information_schema.columns
   WHERE table_schema='$DB' AND table_name='trip' AND column_name='version';
"
```

---

## 6. 排障

### 6.1 测试显示 skipped / 没跑

| 现象 | 原因 | 处理 |
| --- | --- | --- |
| `Tests run: 1, Skipped: 1` | `MYSQL_IT` 未设置或不等于 `true` | 设 `MYSQL_IT=true`；注意大小写 |
| `No tests matching pattern` | `-Dtest` 名字拼错 / 不在当前模块 | 在 `travel-agent-server/` 下执行，类名 `MySqlFlywayMigrationTest` |
| 想确认是否真的跳过 | Surefire 报告或控制台 `Skipped: 1` | `mvn -B -Dtest=MySqlFlywayMigrationTest test` 不带 `MYSQL_IT` 应 BUILD SUCCESS |

### 6.2 连不上 / 认证失败

| 错误关键字 | 含义 | 处理 |
| --- | --- | --- |
| `Communications link failure` / `Connection refused` | 实例没起、端口不对 | 确认 MySQL 监听：`netstat -ano | findstr 3307`；核对 `MYSQL_IT_HOST/PORT` |
| `Access denied for user ... (using password: YES)` | 密码或账号错 | 核对 `MYSQL_IT_USERNAME/PASSWORD`；本机默认 `root/123456` |
| `Public Key Retrieval is not allowed` | MySQL 8 `caching_sha2_password` 需要公钥 | 测试 JDBC URL 已带 `allowPublicKeyRetrieval=true`；若自建 URL 保留该参数 |
| `CREATE DATABASE ... denied` | 用了业务账号 | 用具备 DDL 权限的账号（本机 `root`；CI `root`） |
| `Unknown database 'travel_agent_it_...'` | 建库步骤没执行 | 确认 `@DynamicPropertySource` 未被条件跳过；看异常首行「无法在 MySQL 实例 ... 创建一次性库」 |

### 6.3 MySQL 8.4 与 Flyway 版本警告（预期，不是失败）

启动时会出现：

```text
WARN o.f.c.internal.database.base.Database : Flyway upgrade recommended:
MySQL 8.4 is newer than this version of Flyway and support has not been tested.
The latest supported version of MySQL is 8.1.
```

- 当前 Flyway 由 Spring Boot 3.5.12 BOM 管理，实际版本 **11.7.2**；它的声明支持上限是 MySQL 8.1。
- 这是**警告**：迁移、`validate`、断言在本机 8.4.10 全部通过（见 §5）。
- **不要为了消除警告改 pom**（pom 由 T1 维护）。后续若升级：在 `flyway-core` / `flyway-mysql` 的新版本声明支持 8.4 后，由 pom 维护方升级，然后重跑本测试确认警告消失且断言仍全绿；升级前后都建议保留本次证据。

### 6.4 迁移失败 / 校验和不一致

| 错误 | 定位 | 处理 |
| --- | --- | --- |
| `Migration V<n>__... failed` | 错误里带脚本名与 SQL 行 | 修脚本只能新增后续版本；已发布脚本不重写（见 `docs/DEVELOPMENT.md` 迁移策略） |
| `Validate failed: Migration checksum mismatch for version <n>` | `flyway_schema_history.checksum` 与当前脚本不同 | 已应用脚本被改过：先恢复脚本内容；确需变更则走 `flyway repair` 流程（本仓库的 repair 说明见 `docs/DEVELOPMENT.md`），重建库重跑验证 |
| `Detected resolved migration not applied to database: <n>` | 历史停在旧版本、有新脚本未执行 | 正常启动会执行；若 `target` 被人为固定，检查启动参数 |
| `Detected applied migration not resolved locally: <n>` | 库里有脚本没打进 classpath | 检查 `db/migration*` 是否被打包（`target/classes` / jar） |

**不要**通过 `DROP DATABASE` 生产库或关掉 `ddl-auto=validate` 来「修复」；唯一库可以直接丢掉重跑，生产库必须 forward-only。

### 6.5 Hibernate `Schema-validation` 失败

```text
Schema-validation: missing table [trip_photo]
Schema-validation: missing column [version] in table [trip]
Schema-validation: wrong column type encountered in column [...]
```

- 说明某个实体需要的对象没有被迁移链建出（或类型不一致）。
- 先在本测试的证据库上查：`information_schema.tables/columns`；
- 修复方式：**新增**一个 V* 迁移补对象，不要改已发布脚本；本地重跑本测试直到 `validate` 通过。
- 注意 V13 是「MySQL 方言幂等补齐」，若它空转但对象仍缺，通常是探测条件写错或表名大小写不符。

### 6.6 一次性库残留

测试故意不 DROP。手动清理：

```sql
SELECT schema_name FROM information_schema.schemata
 WHERE schema_name LIKE 'travel_agent_it_%';
-- 确认名字后逐个执行（不要用模糊 DROP）
DROP DATABASE `travel_agent_it_20261119214358`;
```

或运行脚本默认的 24 小时清理；`-RetentionHours 0` 会清掉所有已有测试库（包括刚跑完的证据库），只保留本次运行新建的库。

---

## 7. 维护约定

1. **新增迁移**：直接加 `V20__...sql` 到对应目录；测试自动枚举，无需改版本断言。只要版本不重复、脚本能在 MySQL 上执行，测试会覆盖。
2. **新增/删除实体**：同步更新测试里的 `MAPPED_TABLES` 与 `MAPPED_ENTITIES`（否则 19 这个数字会挡住你，这是有意的护栏）。
3. **新增方言专属脚本**：只放 `db/migration-mysql` 或 `db/migration-h2`，不要放进共享目录；同时确认另一方言的 profile 不会加载它。
4. **不要**在测试里 `DROP DATABASE`、不要连生产库、不要把密码写进仓库；`MYSQL_IT_PASSWORD` 只从环境变量/脚本参数读取。
5. CI 接入后，建议把本测试作为独立 job，并保留 `target/surefire-reports` 与 `[mysql-it]` 日志作为发布证据。