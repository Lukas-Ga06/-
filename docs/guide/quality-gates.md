# 质量门禁（后端发布前必须通过）

> 状态：当前事实 | 适用版本：0.1.0 / Spring Boot 3.5.16（Java 17） | 最后验证：2026-11-25 | 事实来源：`travel-agent-server/pom.xml`、`travel-agent-server/config/`（`checkstyle/checkstyle.xml`、`checkstyle/suppressions.xml`、`spotbugs-exclude.xml`）、`travel-agent-server/.mvn/jvm.config`、`travel-agent-server/src/main/resources/application.yml`、`.github/workflows/ci.yml`

一句话导读：`mvn -B verify` 是后端唯一的发布门禁——单元/集成测试 + JaCoCo 覆盖率门槛 + Checkstyle + SpotBugs，任一不过即 BUILD FAILURE；真实 MySQL 迁移与前端门禁见 [mysql-migration.md](mysql-migration.md)、[frontend-quality.md](frontend-quality.md)，CI 编排见 [ci.md](ci.md)。

## 1. 门禁清单与本地命令

| 门禁 | 绑定阶段 | 本地命令 | 当前数字 / 阈值 |
| --- | --- | --- | --- |
| 单元/集成测试（H2） | test | `mvn -B test` | **以 CI / `mvn -B verify` 输出为准**（本文不维护静态用例数） |
| 发布验收（P0 可靠链路） | CI 独立命名步骤（`mvn verify` 之后） | `mvn -B -Dtest=ReleaseAcceptanceTest -DfailIfNoTests=false test` | `ReleaseAcceptanceTest`（`@Tag("release-acceptance")`）：证明双确认只提交一次、旧方案必失败、提交后崩溃 Outbox 可补投、断线停止无意义调用、LLM 不可用仍产出可审核方案；结果以 CI 步骤 `Release acceptance (P0 reliability)` 为准 |
| JaCoCo INSTRUCTION | verify（check） | `mvn -B verify` | 实测 **79.73%**（38038/47707），阈值 **≥ 0.76** |
| JaCoCo BRANCH | verify（check） | `mvn -B verify` | 实测 **60.81%**（3105/5106），阈值 **≥ 0.57** |
| JaCoCo LINE | verify（check） | `mvn -B verify` | 实测 **79.15%**（7252/9162），阈值 **≥ 0.76** |
| Checkstyle | verify（check） | `mvn -B checkstyle:check` | **0 violations**（只查 `src/main/java`，228 个文件；存量豁免见第 3 节） |
| SpotBugs | verify（check） | `mvn -B spotbugs:check` | effort=**Max** / threshold=**High**，421 个类，**0 findings，0 errors** |
| UTF-8（JVM + 日志） | 全程 | `mvn -B test`（见第 5 节证据） | 测试 JVM、控制台日志、surefire 输出统一 UTF-8 |

- 3 条 skip 是 2 个真实 MCP 用例 + 1 个真实 MySQL 迁移用例（`migration.MySqlFlywayMigrationTest`，无 Docker/MySQL 时按设计跳过），真实迁移验证走 `scripts/mysql-it.ps1`。
- **P0 发布验收独立成命名步骤**：`ReleaseAcceptanceTest`（`@Tag("release-acceptance")`）已包含在 `mvn verify` 里，
  CI 仍在 verify 之后单独跑一次，失败时直接指向「哪条可靠性承诺被打破」，而不是淹没在整轮用例里。
  该套件**不替代**完整 verify，也不以通过条数当发布结论；结果以 CI 日志为准。
- 时间参考（Windows 本机，JDK 17 / Maven 3.9.16）：`mvn -B test` 约 2 分钟，`mvn -B verify`（含 Checkstyle + SpotBugs）约 2 分 25 秒。
- 报告产物：`target/site/jacoco/index.html` + `jacoco.csv`（覆盖率）、`target/checkstyle-result.xml`、`target/spotbugsXml.xml`（CI 上传这三份）。

## 2. JaCoCo 覆盖率门槛与 ratchet 规则

- 阈值写在 `pom.xml` 的 `jacoco-maven-plugin` `check` execution（`BUNDLE` 级三个 `limit`），绑定 `verify`。
- **阈值 = 基线向下取整 3 个百分点**：INSTRUCTION 79.73% → 0.76；BRANCH 60.81% → 0.57；LINE 79.15% → 0.76。
  留 3 个点的余量是为了不把门禁变成「每次小改都要救覆盖率」的噪声源。基线每次全量测试会有 ±0.05 个点的正常抖动（并行测试计时不同），门槛按整数百分比定，不受抖动影响。
- **只允许 ratchet 上调，不得下调**。要下调阈值必须在评审里写清原因、涉及哪些包/类，并同步改本表；不允许「为了过构建」调低。
- 上调时机：基线稳定高于阈值 3 个点以上时，把 `minimum` 提到「当前基线 − 2 个点」。
- 优先给高风险路径补测试：确认提交/撤销、撤销后可重提交、权限隔离、迁移、外部依赖降级、限流与幂等。不追求全仓统一高数字（大量 getter/setter/DTO 只会虚高分母）。

## 3. Checkstyle：低争议硬规则 + 按文件豁免

`config/checkstyle/checkstyle.xml` 只启用能明确判定对错的规则：

| 规则 | 配置 | 说明 |
| --- | --- | --- |
| `FileTabCharacter` | eachLine=true | 统一 4 空格，禁止 Tab 缩进 |
| `LineLength` | max=160 | 不用 80/100 的教条值；长提示词/长 URL 例外走豁免 |
| `AvoidStarImport` | — | 禁止 `import x.*` |
| `UnusedImports` | processJavadoc=true | Javadoc `@link` 引用的 import 不算未使用 |
| `RedundantImport` | — | 禁止 `java.lang.*`、同包 import |

**刻意关闭**（主观或会引发大范围重排）：`TrailingComment`、`WhitespaceAround`、`Indentation`、`Javadoc*`、`MagicNumber`、`FinalParameters`、`HiddenField`、`DesignForExtension`、`VisibilityModifier`。本门禁不要求全仓改格式。

存量豁免在 `config/checkstyle/suppressions.xml`，**只减不增（ratchet）**：

- `AvoidStarImport`：`entity/` 下实体的 `jakarta.persistence.*` / `lombok.*`，以及 9 个 Controller 的 `web.bind.annotation.*`——机械展开会与进行中的功能分支冲突，单独排期。
- `LineLength`：IntentAgent、MessageParser、PlanConfirmationService、StructuredPlanner、RagBootstrap、RagService、OnlineCityCatalogReader、PlanNarrationService、ReminderService、RouteService、SafetyInterceptor（长行主要是 LLM 提示词/中文文案/长 URL，拆字符串伤可读性）。

**排除原则**：豁免必须精确到「文件 + 检查项」，禁止整文件 `severity=ignore`；每条写 reason 与还债方式；新代码不得往清单里加条目；修完一处删一条并同步本文。

## 4. SpotBugs：Max effort，threshold 当前 High

- `pom.xml`：`spotbugs-maven-plugin 4.8.6.6`，`effort=Max`、`threshold=High`，绑定 `verify`，过滤器 `config/spotbugs-exclude.xml`。
- **为什么当前是 High 而不是 Medium**：Max/Medium 的历史基线约 466 条告警，绝大多数是 `EI_EXPOSE_REP*`（Lombok DTO / JPA 实体 getter / Spring 构造器注入字段）——SpotBugs 看不到 Jackson 序列化与容器生命周期语义，这批告警没有行动价值。用 Medium 落地只会把门禁变成「必红」。
- 当前过滤器下 **Max/High = 0 findings**；若后续出现 High 级告警，优先修代码（例如资源未关闭 `OS_OPEN_STREAM`、`SQL_*`、`NP_*` 空指针路径）。
- `config/spotbugs-exclude.xml` 当前 3 条，全部是具体 Class + Bug pattern 的误报：
  - `LocalHashEmbeddingService` `RV_ABSOLUTE_VALUE_OF_HASHCODE`（单/双字符子串 hashCode 恒非负）；
  - `HttpMcpTransport` `NP_NULL_ON_SOME_PATH_FROM_RETURN_VALUE`（三元分支已保证 `getBody()` 非 null）；
  - `JwtService` `DMI_RANDOM_USED_ONLY_ONCE`（一次性 `SecureRandom` 密钥正是正确用法）。
- **排除原则**：只排除「工具看不到运行时语义」的具体误报，必须精确到 Class + Bug pattern 并写 `reason`；发现真实缺陷一律修代码，禁止用 exclude 掩盖；测试代码与生成代码靠插件默认的 `excludeTestClasses` + `src/main` 分析范围天然排除，不写宽泛的文件级排除。
- **Medium ratchet 路线（未完成）**：按包分批消化 `EI_EXPOSE_REP*`（DTO 保留、只给真正泄露可变内部状态的类补拷贝或改不可变），达到阈值后再把 `threshold` 从 High 下调到 Medium，并更新第 1 节表格。

## 5. UTF-8：三处口径一致

| 位置 | 内容 |
| --- | --- |
| `travel-agent-server/.mvn/jvm.config` | `-Dfile.encoding=UTF-8 -Dsun.stdout.encoding=UTF-8 -Dsun.stderr.encoding=UTF-8`（Maven 自身 JVM） |
| `pom.xml` → surefire `<argLine>` | `@{argLine} -Dfile.encoding=UTF-8 -Dsun.stdout.encoding=UTF-8 -Dsun.stderr.encoding=UTF-8` |
| `application.yml` → `logging.charset` | `console: UTF-8`、`file: UTF-8`（注入 Boot 默认 Console/FileAppender 的 encoder） |

关于 `@{argLine}`：它是 JaCoCo `prepare-agent` 在运行期写入的延迟绑定占位符。`pom.xml` 的 `<properties><argLine></argLine></properties>` 只是防御性定义——万一有人绕过 `initialize` 直接跑 `surefire:test`，传给 JVM 的是空串而不是字面量 `@{argLine}`。**不要改成 `${argLine}`**：那样会错过插件运行期设置的值，覆盖率会静默变成 0（JaCoCo check 随后失败）。

不做的事情：不加自写 `logback-spring.xml`。Boot 的 `logging.charset.*` 已能把 UTF-8 交给默认 encoder，额外写一份 logback 配置会覆盖 Boot 的 pattern/级别/彩色输出，收益为零、回归面很大。

**2026-11-19 实测证据**（`mvn -B test -Dtest=ChannelWeChatTest,GuidebookPdfTest`，9 tests 全绿）：

- 测试内日志「微信消息 XML 解析失败: …」在 surefire 输出里为合法 UTF-8 字节：`U+5FAE` → `e5 be ae`，`U+4FE1` → `e4 bf a1`。
- 路书字体日志「路书 PDF 使用中文字体: Microsoft YaHei」：`路书` → `e8 b7 af e4 b9 a6`。
- 校验方式：对整份日志做严格 `utf-8` decode 成功、按 `gbk` decode 失败；`grep -axv '.*'`（逐行匹配合法 UTF-8）无输出。
- 注意：在「代码页 936」的 Windows 终端里直接 `cat` 这些字节会显示为乱码——那是终端解码器的问题，不是日志字节的问题。判断依据只能是字节/严格解码结果。

## 6. 启动告警清理

- **Spring Data Redis repository 误判**：`application.yml` 增加 `spring.data.redis.repositories.enabled: false`。项目只用 `StringRedisTemplate` + Lua 脚本做多实例限流计数（`RedisRateCounter`），没有任何 Redis Repository，数据访问全部走 JPA。不关闭时 Spring Data Redis 的自动配置会去尝试匹配所有 JPA repository 接口，每个上下文启动刷一批 `Could not safely identify store assignment for repository candidate`。
  实测：`mvn -B verify` 日志中该字符串 **0 次**（改动前约 266 条）。
- **PDF 依赖坐标迁移**：`flying-saucer-pdf-openpdf:9.4.0` → 官方合并后的 `flying-saucer-pdf:9.13.0`（OpenPDF 内核，实际传递依赖 `com.github.librepdf:openpdf:2.0.5`）。实测 `mvn -B verify` 无 `has been relocated` 警告（0 次），`ITextRenderer` / `ITextFontResolver` API 未变，`TripGuidebookService` **无需改动**。
  PDF 内容实测：`GuidebookPdfTest` 导出 60 KB / 1 页 / 1 个内嵌字体，用 PDFBox 抽取文本得到「北京三日游…目的地 北京 状态 已结束…」，中文未乱码——证明 CJK 字体已内嵌且 `IDENTITY_H` 编码正确。
- **MySQL 8.4 下的 Flyway WARN**（"MySQL 8.4 is newer than this version of Flyway"）属预期提示，BOM 实际 Flyway 11.7.2，迁移与 validate 全部通过，见 [mysql-migration.md](mysql-migration.md)。这条是外部事实，不由本门禁消音。

## 7. 遗留与后续

- SpotBugs threshold 从 High ratchet 到 Medium：按包分批消化 `EI_EXPOSE_REP*`（第 4 节）。
- Checkstyle suppressions 里的 star import / LineLength 条目随重构逐条删除，只减不增。
- JaCoCo 阈值随基线稳定后上调（每次上调 ≥ 2 个点，且必须同一次提交里更新第 1 节表格）。
- 本机无 Docker 时 MySQL 迁移集成测试固定 skip；CI 的 `mysql-migration` job 负责这条链路，不要在本地门禁里假装覆盖。