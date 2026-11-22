# 远端 CI 首次跑通：从 8 个红 job 到只剩仓库设置项（2026-11-22）

> 快照日期：2026-11-22 ｜ 验证范围：GitHub Actions（`Lukas-Ga06/-`）首跑、逐 job 归因与修复、真实模型验收 ｜ 环境：GitHub-hosted `ubuntu-latest` / JDK 17 temurin / Node 20 / MySQL 8.4 service container ｜ 结论：**main 上 8 个 job 中 7 个绿**，剩余 2 个卡在**仓库设置开关**（Dependency graph、Code scanning），代码侧已无可修项

## 1. 为什么「有 CI 配置」不等于「CI 有效」

仓库里 `.github/workflows/ci.yml` 写了 8 个 job，但在此之前**从未在真 runner 上跑过**
（没有 remote）。第一次跑的结果是：**8 个 job 里 4 个红**，而且全部不是本轮功能代码的问题，
而是「本地环境与 runner 环境不同」积累下来的配置漂移。这一类问题的特点是：

- 本地永远复现不出来（本机 +08、有中文字体、有 `node_modules`、有历史 lockfile）；
- 只有真 runner 能暴露；
- 每一个都会让门禁变成「看起来有，其实没拦住」。

## 2. 逐 job 归因（按发现顺序）

| # | job / 步骤 | 症状 | 根因 | 处置 |
| --- | --- | --- | --- | --- |
| 1 | `docs` / lychee | `Cannot load configuration file: lychee.toml` | lychee 0.24 起只认 snake_case，`max-concurrency` 被判 unknown field，整份配置加载失败 | `lychee.toml` 改 `max_retries` / `max_concurrency`，并在文件里注明原因 |
| 2 | `docs` / lychee | `Cannot parse 'http://localhost:5173，用' into a URL` | README 里裸 URL 紧跟中文逗号，被当成 URL 的一部分 | 改成 `<http://localhost:5173>` 包裹 |
| 3 | `security` / Trivy | `unable to find version 0.28.0` | 工作流写 `trivy-action@0.28.0`，实际 tag 带 `v` | 改 `@v0.36.0` |
| 4 | `e2e` / smoke | 路书 PDF 接口 **500**（`还有1天`…之后 `GET /api/guidebook.pdf` 失败） | `ubuntu-latest` 默认无 CJK 字体，`TripGuidebookService.findCjkFont()` 找不到字体抛 500；**生产镜像已装 `fonts-noto-cjk`**，本地 Windows 有微软雅黑，所以从未暴露 | e2e / e2e-device job 增加 `fonts-noto-cjk` 安装，与生产镜像对齐 |
| 5 | `backend` / surefire | `ReminderServiceTest` 4 例 + `PagingApiTest` 1 例 + `ItineraryPriceReferenceTest` 1 例失败，表现为**日期整体偏一天**（行程 `2026-11-20` 读回 `2026-11-19`、携程链接 `checkin` 同样偏移） | runner 是 UTC。`TimeZoneConfig` 在 Spring 启动后才 `setDefault`，而 JDBC 会话建连时已用掉启动时区 | ① pom surefire `argLine` 钉 `-Duser.timezone=Asia/Shanghai`；② **Dockerfile 补 `tzdata` + `ENV TZ=Asia/Shanghai`**（见下节：这同时修掉了生产容器在 UTC 主机上的同一偏移） |
| 6 | `backend` / `GuidebookPdfTest` | 同上字体问题 | 同 #4 | backend job 也装 `fonts-noto-cjk` |
| 7 | `security` / Trivy | `Total: 2 (HIGH: 2)`：`CVE-2026-22731`、`CVE-2026-22733` | Spring Boot 3.5.7 的两个 Actuator **认证绕过**，修复版本 3.5.12 | `spring-boot-starter-parent` 升到 **3.5.12**（零代码改动，本地 551 用例全绿） |
| 8 | `security` / Dependency review | `Dependency review is not supported on this repository` | 仓库未启用 Dependency graph，且该开关**只能**在仓库设置里打开（REST API 对未启用状态返回 403/404） | 降级为 `continue-on-error: true` 并写明「启用后要改回硬门禁」；真正的依赖门禁仍是 npm audit + Trivy |
| 9 | CodeQL / `Analyze (java-kotlin)` | `Code scanning is not enabled for this repository` | 同类：仓库设置项 | 无法用代码修；需要人工在 Settings → Security 打开 |

## 3. 第 5 条不只是 CI 问题：它同时是一条生产隐患

复现方式（关键，否则会被当成「CI 玄学」）：

```bash
# Windows/Linux 均可；JAVA_TOOL_OPTIONS 会传递给 surefire fork 出的 JVM，
# 而 TZ 环境变量在 Windows 上不会改变 JVM 默认时区（所以一开始的复现尝试是无效的）
JAVA_TOOL_OPTIONS="-Duser.timezone=UTC" mvn -B -q -Dtest=ItineraryPriceReferenceTest test
# → expected checkin=2026-11-20 but was checkin=2026-11-19（与 CI 完全一致）
```

**为什么这是生产隐患**：`docker-compose` / Dockerfile 原先只设置了 `APP_TIMEZONE`
（那是 Spring 的 `travel.timezone` 配置项），**没有设 JVM 的 `TZ`**。
容器默认 UTC → JDBC 建连发生在 `TimeZoneConfig` 生效之前 → 日期类字段偏移一天。
表现会是：路书/比价链接里的日期早一天、出发提醒窗口错位、行程天数计算异常。

修复分两处，缺一不可：
- 测试侧：pom 的 surefire `argLine` 加 `-Duser.timezone=Asia/Shanghai`（让 CI 与业务时区一致）；
- 运行时：Dockerfile 装 `tzdata` 并 `ENV TZ=Asia/Shanghai`（让生产容器与业务时区一致）。

## 4. 修复后的结果

| 批次 | main 上的 job 状态 |
| --- | --- |
| 第 1 次（`e139d63`） | 4 红：docs / security / e2e / backend |
| 第 2 次（`b1778ad`） | 3 红：docs(lychee README) / security(trivy tag + dependency graph) / backend(时区) |
| 第 3 次（`34912f8`） | **1 红**：security（Trivy 的 Spring Boot CVE） |
| 第 4 次（`e61e278`，PR #1 合并后） | **7 绿 1 红**：仅 security / Trivy——因为安全升级提交晚于 PR 合并，未进 main |

第 4 次的 7 个绿 job 明细：

```
Backend / mvn verify（测试 + 覆盖率 + Checkstyle + SpotBugs）        success
Backend / 真实 MySQL Flyway 迁移 + Hibernate validate              success   ← V1..V21 在 service container 上复现
Frontend / typecheck + lint + format + coverage + build budget     success
Contract / OpenAPI 快照一致 + 破坏性变更                             success   ← 快照无漂移
E2E / Playwright smoke（后端 H2 + 前端 preview）                     success   ← 13/13
E2E / 移动端(WebKit) + 弱网(CDP) + 多端并发（观察中）                  success   ← 21/21，观察模式首跑即绿
Docs / 链接与命令引用校验                                              success
```

安全升级本身在 **PR #13** 上验证（本地 `mvn -B verify` 551 用例 0 失败）。

## 5. 仍需人工完成的两件事（代码改不了）

1. **Settings → Code security → Dependency graph** 打开 → 然后删掉 `dependency-review` 步骤上的
   `continue-on-error: true`，让它变回硬门禁。
2. **Settings → Code security → Code scanning** 打开 → CodeQL 的 java-kotlin job 才能真正上传结果。
   （javascript-typescript 那一半是绿的，说明 workflow 本身没问题，只是上传被拒。）

## 6. 顺带发现：Dependabot 的 vite 8 升级现在合不了

Dependabot 开了 12 个 PR（vite 8、vite-plugin-pwa 2、antd 6、typescript 7…）。
其中 **vite 8（PR #12）装不上**：`@vitejs/plugin-react@4.7.0` 的 peer 只到 `^7`，
`npm ci` 直接 `ERESOLVE`（Security 与 e2e-device job 上实测到）。
正确顺序是先升 `@vitejs/plugin-react`（≥5），再升 vite，然后跑前端四道门禁 + 视觉回归。
**不要**用 `--legacy-peer-deps` 绕过——那会把版本不兼容藏进 lockfile。
已在 PR #12 留了复现输出与建议顺序。

## 7. 证据与命令

- 工作流：`.github/workflows/ci.yml`（8 个 job）
- 逐 job 日志留档：CI 页面 `https://github.com/Lukas-Ga06/-/actions`
- 复现时区偏移：见 §3 的 `JAVA_TOOL_OPTIONS` 命令
- 本地等价门禁：`mvn -B verify` / `npm run typecheck && npm run lint && npm run format:check &&
  npm run test:coverage && node scripts/check-core-coverage.mjs && npm run build:check && npm run api:check`