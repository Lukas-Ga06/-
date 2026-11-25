# 上线执行清单（Android 真机口径）

> 状态：执行清单（计划文档，不是验收快照）｜最后更新：2026-11-24｜事实来源：[上线路线图](../PROJECT_ROADMAP.md)、[P0/P1 执行快照](../review/2026-11-22-p0-execution.md)、[GitHub 安全设置清单](github-security-settings.md)、2026-11-24 本机环境核对
> 本文把"真实 iOS Safari"这一 v1 硬阻断替换为"真实 Android 设备"；**不修改任何源码、配置或测试用例**。

## 0. 口径变更（先读这段）

| 项 | 原口径 | 新口径 |
| --- | --- | --- |
| 真机门禁 | 真实 iPhone + Safari | 真实 Android + Chrome，另加一台国产 ROM 或微信内置浏览器 |
| 模拟口径 | Playwright WebKit + iPhone 预设 | **代码未改**：仍是 Chromium 桌面 + WebKit iPhone 预设（[mobile-safari.spec.ts](../../travel-agent-web/e2e/mobile-safari.spec.ts)） |
| 验收记录 | 待补 iOS 真机记录 | `docs/review/2026-11-XX-android-device.md`（新快照，含设备/版本/网络/截图） |

换口径的理由：产品没有 iOS 独占能力；公众号渠道在 Android 上运行于微信 WebView（Chromium/X5 内核），这条路径只有真机才能验，Android 真机比 iOS 更贴近实际用户路径；PWA 安装、离线、下载在 Android Chrome 上也更容易完整验收。

需要承认的代价：模拟套件的设备逼近仍是 WebKit/iPhone 预设，换成 Android 口径后**模拟的代表性下降**（见 §9 风险 R1）。本文只改验收目标，不改测试代码；若长期以 Android 为主口径，需要一个单独的代码批次（Chromium Android 设备仿真 + PR 模板措辞），不在本文范围。

### 0.1 本机环境核对（2026-11-23 00:0x 首测，19:2x 复核）

| 项 | 实测结果 | 对计划的影响 |
| --- | --- | --- |
| 工作区 | 复核时**工作区干净**（0 个未提交路径）；分支 `feat/weather-strict-ui-closeout` 领先 `origin/main` **24 个提交**，其中 **1 个（`9c52251`）未 push**；本地 `main` 落后 `origin/main` 6 个提交 | 阶段 0 的「提交 / 拆分」已完成，只剩 push → PR → CI → 合 main → tag |
| Docker | **已解决（2026-11-23）**：Docker Desktop 29.9.3 + WSL 3.0.5.0 已可用（`docker info` 正常）；本机 `wsl --install` 会 403，实际路径是 DISM 启用功能 + 独立 MSI | 阶段 1.3 已完成，过程与坑见 [冷启动快照](../review/2026-11-23-container-cold-start-and-llm-load.md) |
| MySQL | 3306 有 MySQL80 服务在跑；便携 8.4.10 在 `.tools/mysql8410-3307`，当前未启动 | 阶段 1.1/1.2 本机即可做，不必等 Docker |
| Node / Maven | Node 20.17；Maven 3.9.16（不在 bash PATH，用 PowerShell 或 `cmd //c`） | 门禁命令在 PowerShell 里跑 |
| 前端依赖 | `travel-agent-web/node_modules` 已安装 | 门禁不用重新 `npm ci` |
| 视觉基线 | 只有 `*-win32.png`，无 `*-chromium-linux.png` | 阶段 2.2 仍缺 Linux 基线 |

> **2026-11-24 复核**：阶段 0 已收口 —— PR [#17](https://github.com/Lukas-Ga06/-/pull/17) 合入 `main`（`7eadbd1`），tag `v0.1.1`；同日 11 张 Linux 视觉基线已生成并提交（§2 第 8 项）。剩余阻断项见 §2（序号 4/6/7/9/12）。

## 1. 上线分级判断

| 级别 | 现在 | 主要卡点 |
| --- | --- | --- |
| A 内部演示 | **具备** | 无；构建必须串行，不要并发共享 `target` |
| B 受控灰度 | **未具备** | 高德 MCP Key、告警值班 |
| C 公网 v1 | **未具备** | B 全部 + Android 真机、法务/许可证、回滚演练；Linux 视觉基线已完成（§2 第 8 项） |

代码侧完成度不低：639 个后端用例、537 个前端用例、隔离 E2E 桌面 14/14、设备 22/22（依据见 [执行快照](../review/2026-11-22-p0-execution.md)）。真正的距离是**这些绿灯全部来自 Windows 本机**，而生产是 Linux 容器 + 真实 MySQL + 真机 + 真实外部服务。

## 2. 离上线差什么（12 项，按阻断性排序）

| # | 缺口 | 现状 | 关闭动作 | 归属 | 量级 |
| --- | --- | --- | --- | --- | --- |
| 1 | 成果未落地 | ✅ **已完成（2026-11-24）**：PR [#17](https://github.com/Lukas-Ga06/-/pull/17) 已合入 `main`（`7eadbd1`）并打 tag `v0.1.1`；PR 的 11 项 check-runs 全绿，main push CI（[run 45379874333](https://github.com/Lukas-Ga06/-/actions/runs/45379874333)）通过 | — | ✅ | — |
| 2 | 真实 MySQL 8.4 | **已完成（2026-11-23）**：全量 23 条迁移 + 备份恢复演练实测（48.8 MB 恢复 5.06 s）；RTO/RPO 目标值仍待业务确认 | — | ✅ | — |
| 3 | 容器冷启动 | **已完成（2026-11-24 补充）**：全栈起停 + 限流按真实源 IP 分桶；S3 用本地协议桩验通上传/读回/删除（真实端点与凭证未验）；H2 控制台口径已按实测改为「API 401 / 经 nginx 为 SPA 首页」，不再要求 404；infra compose 的 `mysql:8.4` 新卷冷启动 + 23 条 Flyway 迁移已实测（[快照](../review/2026-11-24-infra-mysql84-cold-start.md)） | 真实 S3 端点验收 | 我 | ✅（真实 S3 待凭证） |
| 4 | GitHub 安全开关 | ✅ **已完成（2026-11-25）**：仓库 Public；Dependency graph、Code scanning（67 条告警待分诊）、Secret scanning + Push protection（**拦截已实测**）、`main` 分支保护（10 个必需检查）、PVR + `SECURITY.md`、CI gitleaks、dependency-review 硬门禁全部落地，`scripts/github-security-settings.mjs` 复核 4/4 | 剩余：67 条告警分诊 | 你 | 完成 |
| 5 | 真实 LLM 压测 | **已完成并修正（2026-11-24）**：20 连重跑 15 planned + 5 asked_back + **0 failed**、零 429、中位 30.9 s、花费 ¥0.64；旧版 run-18 假绿已修 | 高德 MCP Key 仍为空，`10021` 退避与降级告警待验 | 我 | ✅ |
| 6 | 高德真实边界 | **REST 已实测**：20 并发复现 9 次 `10021`，跨城同名 POI 与 polyline 正常；MCP Key 未验 | 生产 Web 服务 Key + MCP `AMAP_MAPS_API_KEY` 复验 | 我 / 你 | 0.5d |
| 7 | **Android 真机**（原 iPhone） | 只有 WebKit 模拟 | 两台真机跑 §5.1 矩阵并留证 | 你 | 0.5d |
| 8 | Linux 视觉基线 | ✅ **已完成（2026-11-24）**：`visual-baseline.yml` 在 Playwright 固定容器生成 11 张 `*-chromium-linux.png` 并提交；CI `visual` job 检测到基线后由「跳过」转为逐像素比较（仍 `continue-on-error` 观察） | 观察稳定后转硬门禁 | ✅ | — |
| 9 | 法务与许可证 | 5 处占位 + 留存期待确认 + LICENSE 专有 | 法务替换、生效日期、隐私页上线 | 你 | 外部 |
| 10 | 账号级数据导出 | **已完成**：`GET /api/auth/me/export` + 个人中心入口；JSON 快照，照片只给清单（H2 契约测试） | 上线前补一次 MySQL 实机复核 | 我 | ✅ |
| 11 | JWT 注销复用 | **已完成（V24）**：JWT 带 `uid` + `token_version`，注销先版本 +1 再删用户 | 上线后旧 token 整体失效一次，需在发布记录/公告写明；无需禁用近期注销名 | 我 | ✅ |
| 12 | 回滚与告警 | **告警已实跑（2026-11-24 补齐高德降级）**：`TravelAgentServerDown`（后端掉线 3 分钟）、`TravelExternalProviderDegraded{provider="amap"}`（`for: 15m`）与 `TravelExternalDegradedHigh`（`for: 10m`）均真实 firing 并在降级停止后恢复；回滚已本机演练（N-1 jar，RTO≈15s） | **只剩灰度值班人未定**（另一半归属：你） | 我 / 你 | 0.5d |

首屏 JS 只剩 2,831 B 余量（597,169 / 600,000），首屏 CSS 剩 8,380 B（57,156 / 65,536）。v1 前任何新增首屏依赖都会撞预算，这是约束，不是任务。

## 3. 阶段 0：把成果落地（0.5h + CI 等待）

> **2026-11-23 19:2x 复核**：3.1 / 3.2 的提交与拆分**已完成** —— 24 个提交在 `feat/weather-strict-ui-closeout` 上，工作区干净；只剩 3.4–3.5 的 push → PR → 远端 CI，以及合入 `main` 与打 tag。

### 3.1 保底

```bash
git switch -c feat/p0-release-readiness
git add -A
git commit -m "chore: P0 批次工作区快照"
```

现有分支名与这批改动不符，另起分支；先有一个可回滚提交，再谈拆提交。

### 3.2 拆成 5 个提交

`git reset --soft HEAD~1` 后按路径分批 add：

1. `feat(agent)`：工具参数 schema、研究 partial 收尾、MCP 10021 退避、多意图提示
2. `feat(server)`：模型探测、`system/status` 扩展、账号注销
3. `test(release)`：`start-e2e-backend.mjs`、`e2e:isolated`、CI 改造、`visual-baseline.yml`
4. `feat(web)`：RunStatus、CSS 拆分、Emoji→图标、页面收口 + 视觉快照
5. `docs(release)`：路线图、生产配置、运维/法务文档、LICENSE、PR 模板

### 3.3 跑门禁（push 前，串行）

```bash
cd travel-agent-server
mvn -B clean verify

cd ../travel-agent-web
npm run typecheck
npm run lint
npm run format:check
npm run test:coverage
node scripts/check-core-coverage.mjs
npm run build:check
npm run api:check
npm run e2e:isolated
npm run e2e:device:isolated
npm run test:visual

cd ..
node scripts/docs-check.mjs
```

| 门禁 | 通过标准 |
| --- | --- |
| 后端 | 639 用例，0 failures，0 errors，3 skipped（2 个真实 MCP + 1 个真实 MySQL） |
| 前端单测 | 537 用例通过；核心目录覆盖率达标 |
| E2E | 隔离后端 14/14；设备套件 22/22 |
| 视觉 | 11/11（Windows 基线） |
| 文档 | 0 断链、0 失效命令 |

### 3.4 push 并开 PR

```bash
git push -u origin feat/p0-release-readiness
```

PR 描述按上述 5 节组织，证据表直接引用 [执行快照](../review/2026-11-22-p0-execution.md) §3，未验证清单引用 §4。

### 3.5 看远端 CI

必须绿：`backend`、`mysql-migration`、`frontend`、`contract`、`docs`、`e2e`。观察中：`security`（Dependency graph 未开时跳过）、`e2e-device`、`visual`。

## 4. 阶段 1：受控灰度的 5 个阻断项（3–5 天）

### 4.1 真实 MySQL 8.4 全量迁移

本机便携实例已具备，只是没启动：

```bash
powershell -NoProfile -ExecutionPolicy Bypass -File .tools/mysql8410-3307/setup.ps1 -Action start
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/mysql-it.ps1
```

通过标准：`flyway_schema_history` 有 V1–V24 共 23 条（V14 为 H2 专属，MySQL 链没有）；Hibernate `validate` 通过。详见 [MySQL 迁移验证](../guide/mysql-migration.md)。

顺带记一笔：~~`docker-compose.infra.yml` 用的镜像 tag 是 `mysql:8.0`，生产 compose 是 `8.4`~~ **已对齐（2026-11-24）**：两个 compose 都是 `mysql:8.4`，8.0 旧数据卷首次用 8.4 启动属不可回退升级，已写进 [部署文档 §6.2](../guide/deployment.md)；生产显式 `utf8mb4_unicode_ci`、本地继承 8.4 默认 `utf8mb4_0900_ai_ci` 的 collation 差异也一并记在那里。

### 4.2 备份与恢复演练

用 [backup-drill.ps1](../../scripts/backup-drill.ps1) 先 `-DryRun` 再实跑（真实 `mysqldump` / `mysql.exe`），记录恢复耗时与可接受丢数窗口，填进 [备份恢复](backup-restore.md) 的 RTO/RPO 表。该脚本此前只在 fake client 下验证过流程，第一次实跑大概率要调参数，按 1 天预留。

### 4.3 容器冷启动（**已完成 2026-11-23**，见 [冷启动快照](../review/2026-11-23-container-cold-start-and-llm-load.md)）

1. 装 Docker Desktop（WSL2 后端），重启后确认 `docker compose version` 可用。
2. 复制 [.env.production.example](../../.env.production.example) 为 `.env`，只填本机可用的占位值，真实密钥不进仓库。
3. `docker compose up -d --build`，打开 `http://localhost:8081`。
4. 逐项核对 `/api/system/status`：LLM 配置/模型是否可用/provider、MCP enabled/connected/toolCount、AMAP 配置、时区。
5. 验安全开关：`DEMO_ACCOUNT=false` 后 demo 账号登录失败；**H2 控制台不可达**——API 直连 `/h2-console/` 返回 `401` JSON，经 nginx 走 SPA 回退返回 200 首页（控制台页面本身不存在）；这里**不是 404**，口径依据见 [冷启动快照](../review/2026-11-23-container-cold-start-and-llm-load.md) 与 `H2ConsoleAccessTest`；Cookie Secure；nginx 访问日志无 `?ticket=`；Redis 限流生效；S3 上传可用。

通过标准：自检表贴进 [生产配置清单](production-config.md)，每项写"实测值 + 证据"。

### 4.4 GitHub 仓库设置（你，10 分钟）

按 [GitHub 安全设置](github-security-settings.md)：开 Dependency graph、Code scanning（CodeQL）、Secret scanning + Push protection；给 `main` 加分支保护（必须 PR + 必须状态检查 + 禁强推）。完成后删掉 [ci.yml](../../.github/workflows/ci.yml#L255) 里 `dependency-review` 的 `continue-on-error`。

### 4.5 真实 provider 压测

- LLM：连续 20 次规划（先充 ¥10，成本约 ¥0.3），记录终态分布、`completed_partial`、schema 自纠次数、超时。
- 高德：并发 20 请求触发 10021、跨城同名 POI、polyline 点数、GCJ-02 偏移、REST 与 MCP 两条分支口径一致。
- 通过标准：用户看到"路线暂时使用估算/稍后重试"，不是堆栈或假实时。
- 记录到新快照 `docs/review/2026-11-XX-real-provider-load.md`。

### 4.6 告警与值班

起 Prometheus 加载 [alerts.yml](alerts.yml)、导入 [Grafana 面板](grafana-travel-agent.json)，停后端、断 AMAP 各触发一次，确认告警真的送达；同时定灰度值班人和反馈渠道。

## 5. 阶段 2：公网 v1 硬阻断（5–8 天 + 外部等待）

### 5.1 Android 真机验收（替换原 iPhone 项）

至少 2 台设备：一台接近原生（Pixel / Android 11+），一台国产 ROM（MIUI / ColorOS / 鸿蒙）或微信内置浏览器。

| 维度 | 验收点 | 通过标准 |
| --- | --- | --- |
| 视口 | 360×800、393×873、412×915、768×1024，横竖屏 | 无横向滚动；长标题不超过两行 |
| 安全区 | 手势导航 + 三键导航两种模式 | 底部 Tab、sticky 输入框不被系统条遮挡 |
| 软键盘 | 聊天输入、记账金额、日期选择 | 输入框不被键盘盖住，页面不跳动 |
| 返回 | 系统返回键与返回手势 | 不误退出 PWA，不丢未确认的 Diff |
| PWA | Chrome 菜单"安装应用"→ 桌面图标 → standalone 启动 | 正常启动；离线可只读行程；恢复后同步 |
| 地图 | 高德 JS API 手势缩放、点选 POI | 可交互；失败显示降级文案与重试 |
| 导出 | 路书 PDF 下载、打开、分享 | 下载管理器能打开，文件名正常 |
| 弱网 | 飞行模式 / 弱信号 | 明确的 loading/降级状态，不白屏 |
| 微信内打开 | 公众号链接 → 登录 → 规划 | 可在 WebView 内完成，不被拦截 |
| 登录态 | 关闭浏览器再打开 | 保持登录 |

通过标准：每条路径附截图与设备型号、Android 版本、Chrome 版本、ROM、网络条件、时间，写成 `docs/review/2026-11-XX-android-device.md` 并登记到 [评审索引](../review/README.md)。

注意：本文不改代码，所以不要把"Chrome 自动弹出安装提示"当作验收项——安装入口按浏览器菜单走。

### 5.2 Linux 视觉基线

✅ **已完成（2026-11-24）**：`visual-baseline.yml` 在 Playwright 固定容器 `v1.64.1-jammy` 生成 11 张
`*-chromium-linux.png` 并随 PR 提交（run 45381999718）；`visual` job 已由「跳过」转为逐像素比较，
仍保持 `continue-on-error` 观察，转硬门禁与 `e2e-device` 一起在 **2026-12-06** 复盘时决定。

### 5.3 法务、许可与数据导出

- 替换 [隐私政策](../legal/privacy-policy.md) §1 的运营主体、注册地址、隐私邮箱、生效日期、版本号；确认留存期。
- ~~实现账号级数据导出~~ **已完成**：`GET /api/auth/me/export`（JSON，照片只给清单），入口在个人中心「导出我的数据」；上线前补一次 MySQL 实机复核。
- 第三方服务清单与实际 provider 对齐；LICENSE 专有/开源是业务决策，需法务书面确认。

### 5.4 安全残余（代码项，可并行）

V24 已实现：`users.token_version` + JWT `uid`/`ver`，注销后旧 token 立即失效、同名重注册不串号；注销提交后 `vectorStore.reload()`。旧业务表清理策略见 [数据删除流程](../legal/data-deletion.md) §4（运维手工 SQL，API 不覆盖）。上线时所有旧会话需重新登录一次。

### 5.5 回滚演练与发布记录

保留 N-1 镜像与数据库快照，真实回滚一次；Flyway 只允许前滚，回滚口径写进 [runbook](runbook.md)。发布记录必须含 commit、环境变量版本、迁移版本、门禁链接、真实验收结果、RTO、已知风险与回滚方式。

### 5.6 首屏预算

2 KB 余量，先摸清最大首屏依赖；v1 前只允许代码分割，不允许调阈值。

## 6. 阶段 3：灰度到公网（1–2 周）

灰度名单 + 反馈渠道 + 值班表 → 观察 1–2 周无 P0/P1 → 复盘 SLO 与告警数据 → 决定公网发布日期。

## 7. 今天就能做的四件事

1. ~~push 24 个提交并开 PR~~ **已完成（2026-11-24）**：PR #17 已合入 `main`（`7eadbd1`）并打 tag `v0.1.1`，远端 CI 已见过且全绿。
2. **起便携 MySQL 8.4 跑迁移**：**已完成（2026-11-23，全量 23 条含 V24）**，见 [MySQL 迁移验证](../guide/mysql-migration.md)。
3. **备份/恢复演练并写 RTO/RPO**：演练**已完成**（48.8 MB 恢复 5.06 s、应用启动 12.75 s）；RTO/RPO 的**目标值**仍需业务确认后填入 [备份恢复](backup-restore.md) §7.2。
4. **开 GitHub 四个开关**（10min，你操作）：解锁 8/8 CI。

## 8. 现在不要做

P2 的 OTA 实时库存、真实 Embedding 调优、照片轨迹、继续 UI 打磨、加新功能——都不阻断 v1，做了只会挤掉验证时间。

## 9. 风险登记

| 编号 | 风险 | 影响 | 处置 |
| --- | --- | --- | --- |
| R1 | 模拟套件仍是 WebKit/iPhone 预设，真机口径已改 Android | 模拟通过不代表 Android 通过 | 真机验收必须人工做；长期需单独的 Chromium Android 仿真代码批次 |
| R2 | ~~Docker Desktop 因 WSL2 缺失起不来~~ **已解除**；换成「Docker Desktop 不能从 shell 里启动」 | shell 一被终止，引擎与 npipe 一起消失 | 用 WMI `Win32_Process.Create` 拉起（见快照 §2.3） |
| R3 | ~~infra 用 mysql:8.0，生产用 8.4~~ **已解除（2026-11-24）** | 版本漂移已消除：两个 compose 同为 `mysql:8.4`；旧 8.0 数据卷升级不可回退 | 镜像已对齐；升级处置（备份或删卷重建）写进[部署文档 §6.2](../guide/deployment.md) |
| R4 | 首屏 JS 余量 2,831 B / CSS 余量 8,380 B | 新依赖直接撞预算 | v1 前只做分割，不调阈值 |
| R5 | 法务排期不可控 | 公网发布日期不可控 | 尽早送审，法务确认前不发布 |
| R6 | ~~24 个提交未合入 `main`、1 个提交未 push~~ **已解除（2026-11-24）**：PR #17 已合入 `main` 并打 tag `v0.1.1` | CI 未见过，误操作即丢 | 阶段 0 立即 push → PR |