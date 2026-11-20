# 修复复核报告：数据可信度、真实几何与部署回归（2026-11-19 晚）

> 快照日期：2026-11-19（晚） ｜ 验证范围：验收遗留 P1/P2/P3/P5/P6、真实道路几何、部署回归 ｜ 结论：五项均已修复并复验；真机与 MCP 真实链路仍待人工（详见 §0）

> 前置：[V2.2 正式验收](2026-11-19-acceptance.md)（同日白天）列出了 P1/P2/P3/P5/P6 五项待修与四类未验项。
> 本报告只回答一件事：**这些项现在到底做没做、验到什么程度**。
> 原则不变：没有做的事不写成做过；模拟器不等于真机；单测通过不等于统计效果提升。

## 0. 结论先行

| 项 | 状态 | 关键证据 |
|---|---|---|
| P1 慢依赖无端到端预算 | ✅ **已修复并实测** | 慢依赖完整规划 **128.13s / 5 次 LLM 调用 → 30.05s / 1 次调用**（-76.1%），降级可观测（`DEGRADE` 任务 + 4 条 `budget_degraded` + 如实文案） |
| P2 无效 Key 自报失真 | ✅ **已修复并用真实实例验证** | 无效「Web 服务」Key 下 A1–A10 **10/10 PASS**：`routeProvider=estimated`、`routeConfigured=true`、`amapVerified=false`、`routeLastError=infocode=10001`，`route-legs` 全 estimated 且 `polyline` 全空 |
| P3 降级日志丢根因 | ✅ 已修复 | `ErrorSummary`：`包装类型 -> 根因类型: 消息`，并做 Key 脱敏 |
| P5 LLM 配置态失真 | ✅ 已修复 | `llmConfigured` / `llmVerified` 分离，健康页两档呈现 |
| P6 QPS 10021 静默降级 | ✅ 已修复（单测覆盖） | 一次退避重试（220~400ms 抖动）+ WARN 带 infocode；`estimated` 改 60s 短 TTL |
| 真实道路 polyline | ✅ **已用真实 Key 端到端验通（2026-11-19 晚）** | `route-legs` 7/7 段 `source=amap`，几何点数 3/8/11/27/28/35/41；Playwright 确认浏览器收到的正是这些多点路径（非两点直连）；截图 `.evidence/polyline-browser.png` |
| 首屏分包 >1MB | ✅ 已优化 | 首屏静态闭包 **1168.79 → 558.26 KiB（-52.2%）**；echarts **1086.71 KiB → 550.60 kB（-50.5%）**；maps 仍 1.06 MB（故意不抬阈值） |
| MySQL 8.4 真实部署 / 旧库升级 / 重启恢复 | ✅ **旧库升级已正式回归** | 新增 `mysql-upgrade-acceptance.ps1`，V16 真实旧 schema → V17/V18，**80/80 断言 PASS**，24/24 表行数与旧列哈希一致、三态重启通过 |
| Safari/iOS/PWA 真机、暗色地图/图表 | ✅ 暗色已修复并量化；⚠ 真机仍需人工 | 地图平均亮度 220.66→61.13；图表文字对比度 13.68:1 / 6.81:1；WebKit iPhone 13 + Chromium Pixel 7 PWA **32 项 0 FAIL** |
| 生产压测 / 第三方异常 / 长跑 | ✅ 已复跑出新基线 | 并发 40/40 + 20/20、第三方 3 类降级成功、长跑 576/576（预热后 RSS +2.5%） |
| FR-11 个性化 RAG / FR-12 实时房态价格 | ⛔ **仍延后（P1）** | 依赖 OTA 商务授权与库存接口；见 [provider-rag 决策](2026-11-19-provider-rag-decision.md) |
| FR-12 防守项「参考价 + 来源 + 深链」 | ✅ **已实现（同日更晚，最小版）** | `price{kind=reference,source=local-estimate,disclaimer,links}`；酒店→携程 canonical（北京 1/上海 2/杭州 17，表外不猜）、条目→高德 URI；`priceProvider=reference-estimate`。后端 467、前端 201 用例；真实 HTTP 冒烟过 |
| 高德「Web 服务」Key 联调 | ✅ **已完成（第三枚 Key，指纹 `5f74d350`）** | 探针全 PASS：7/7 段 amap、POI 3、geocode 3/3、GCJ-02 0.13m；`.env.local` 已配置且 gitignore |

**验证基线**：后端 `mvn verify` **449/449** 全绿；前端 `typecheck` 0 error + **192/192**（26 文件）+ build 成功；
合并后 jar 真实 HTTP `fr-acceptance.mjs` **27/27 PASS**。

## 1. 本轮方法与环境

| 项 | 实际 |
|---|---|
| 后端 | Spring Boot 3.5.7 / JDK 17 / Maven，`mvn -B verify "-DargLine=-Djdk.attach.allowAttachSelf=true"` |
| 前端 | Node 20 / Vite 6，`npm run typecheck && npm test && npm run build` |
| 并行方式 | 三条实现线在**写范围互不重叠**的文件上并行；P1 因改 `AgentEngine`/`AgentController` 且需反复跑 Maven，放在独立 git worktree（`.worktrees/p1-budget`）避免共享 `target/` 互相污染 |
| 环境限制 | 本机 **无 Docker**（沿用便携 MySQL 8.4.10 原生实例，如实标注）；后端无「Web 服务」Key；DeepSeek 余额 402；Apple 真机不可自动化 |
| 沙箱 | 子进程管道 stdio 在 workspace-write 下 `spawn EPERM`，前端构建/测试与 Maven 验证均在放开权限的会话中执行（这一点写清楚，避免把"别人跑的"记成"我跑的"） |

## 2. P1：端到端时间预算（已修复）

**设计**：请求入口创建不可变 deadline（`travel.agent.budget.enabled` 默认 `true`、`total-ms` 默认 `30000`），
随 `AgentContext` 传递；各阶段开始前查剩余预算，单次 LLM 读超时被压到「剩余预算」以内。
耗尽不是报错而是按 ADR-002 降级：intent→规则引擎、research 跳过、结构化规划→规则规划、审核/重规划跳过、润色跳过。

**实测（自建实例 :18100，慢 LLM 替身）**：

| 指标 | 旧基线 | 本轮 |
|---|---|---|
| 完整规划端到端 | 128,125.2 ms | **30,608.5 ms**（另一次独立测量 30,046.7 ms） |
| 调用次数 | 5 | **1** |
| 节省 | — | 97,516.7 ms（**76.1%**） |
| 单次调用超时 | 30.03 s | 30.014 s（读超时仍精确生效） |

**降级可观测性**（三处，均取自真实响应/追踪）：

1. run 追踪：`{"taskType":"DEGRADE","receiver":"budget-guard","summary":"research/plan/review/narration：预算耗尽降级（剩余 0ms）…"}`；
2. SSE：4 条 `budget_degraded`（stage=research/plan/review/narration，remainingMs=0），事件流仍以 `complete` 收尾；
3. 回复文案与 `warnings`：「时间预算（30.0s）已用尽…跳过研究取证、跳过 LLM 结构化规划、跳过审核与重规划（未做路线/天气校验）…本次结果未经完整校验」。

**功能代价（必须说清）**：跳过上述 4 个阶段；方案主体仍由规则规划产出（4 景点 / 4 美食 / 酒店 / ¥1259）。
相对旧基线，那 128s 里的 4 次调用最终也全部超时并降级为规则方案——所以这 97.5s 主要是
**不再等待注定超时的调用**；相对"依赖正常（规划 p50 132ms）"时，才是真正损失了 4 个阶段的完整度。

**已知边界**：预算**不约束**审核阶段内部的外部调用超时（高德算路 6s / 天气 5s / MCP 工具 30s），
最坏会多出一个在途调用的自身超时。彻底解决需把预算下沉到这些客户端，列为后续小项。

## 3. P2 / P3 / P5 / P6（已修复）

**P2 端到端断言（A1–A10，10/10 PASS）**——实例用 `--amap-key=INVALID_amap_key_for_test` 启动：

```json
{"routeProvider":"estimated","routeConfigured":true,"amapVerified":false,
 "routeLastError":"infocode=10001, info=INVALID_USER_KEY",
 "routeHint":"高德已配置但最近一次真实调用失败（infocode=10001…）：当前路线为本地估算…"}
```
```json
{"source":"estimated","polyline":[]}
```

- 修复前：状态页报 `routeProvider=amap` +「路线使用高德真实路径规划」，而 7 段实际全 estimated（P2 缺陷）。
- 修复后：状态页与 `route-legs` 口径一致；**调用前**也报 estimated（不猜），“已验证”只认最近一次真实成功。
- 前端：健康页「已配置未验证」+ 原始 infocode，绝不显示成「使用高德真实路径」。

**P6**：`AmapDataService.direction` 对 `status=0 & infocode=10021` 只重试一次（220~400ms 抖动），
其余 infocode（10001/10009）不重试只 WARN；`RouteService` 对 estimated 结果用 60s TTL、amap 结果仍 10 分钟。
单测覆盖：重试后成功（恰好 2 次请求）、重试仍失败（WARN 含 10021、未验证）、平台不匹配（1 次请求 + 1 条 WARN）、
无效 Key 不报 amap、60s 后自动恢复 amap、失败结果不进 10 分钟缓存。

**P3/P5**：`ErrorSummary` 输出「包装类型 → 根因类型: 根因消息」（`ResourceAccessException -> SocketTimeoutException: Read timed out`）
并对 URL 里的 `key=` 脱敏；`DelegatingLlmClient` 在唯一入口记录最近一次真实调用成败，`llmVerified` 与 `llmConfigured` 分离。

## 4. 真实道路 polyline（已打通，真实 Key 下的渲染待验）

**后端契约**（`route-legs`，字段常驻不为 null）：

| 项 | 约定 |
|---|---|
| 类型 | `number[][]`，每点 `[lat, lng]`，**纬度在前** |
| 坐标系 | **WGS-84**（全仓业务坐标一致） |
| 来源 | 高德 `route.paths[].steps[].polyline` 拼接 → 逐点 GCJ-02→WGS-84 → Douglas-Peucker（12 m 容差）→ **≤200 点**、保留首尾 |
| 缺省 | `estimated` 段与「真实接口无几何」一律 `[]`，**绝不返回假点** |

**前端**：`RouteLeg.polyline` 存在且 ≥2 点时逐点 `toAmapLngLat()` 转 GCJ-02 后绘制；缺省/空数组/单点回退两端点直连；
`source=amap` 实线、`estimated` 虚线；渲染 key 用「点数 + FNV-1a 几何摘要（1e-5° 量化）」，既感知中段点变化又不塞整串坐标。

**已验证**：单测覆盖解析/拼接去重/坐标/抽稀/上限/空几何、JSON 契约（`RouteLegPolylineJsonTest` 锁死 `[[lat,lng],…]` 与 `[]`）、
前端几何优先/回退/虚线/重绘；合并后真实 HTTP 确认 `polyline` 字段常驻且 estimated 全为 `[]`。
**2026-11-19 晚已补齐（真实 Key，指纹 `5f74d350`）**：`route-legs` **7/7 段 `source=amap`**，
几何点数 3/8/11/27/28/35/41；用 Playwright 在 `window.AMap` 赋值时接管并包装 `Polyline` 构造，
确认地图收到的正是这些多点路径（28 次构造 = 7 段 × 辉光/主线 × 两次渲染），**不是两点直连**；
截图 `.evidence/polyline-browser.png`。探针另新增「真实调用后状态翻转为 amap」的反向断言。

## 5. 首屏分包（已优化）

`vite.config.ts` 原本已有 manualChunks，但把「所有页面用到的 antd/rc」强制合成一块（1047.82 KiB），
首屏只用 `Button/Result/Spin` 也要先下载全量。改为交给 Rollup 按真实引用分块；echarts 改 `echarts/core` 按需注册。

| 指标（KiB=1024B） | 优化前 | 优化后 | 变化 |
|---|---:|---:|---:|
| 首屏 JS 静态闭包 | 1168.79 | **558.26** | **-52.2%** |
| echarts 全部 | 1086.71 | 537.70 | -50.5% |
| antd 独立 chunk | 1047.82 | 0（不再单列） | -100% |
| maps（maplibre） | 1036.05 | 1036.05 | 0（懒加载，不在首屏） |
| PWA precache | 3605.82 | 3072.33 | -14.8% |
| 首屏 JS gzip | 365.97 | **181.94** | -50.3% |

**口径与易误读点**：`echarts-CQX-PZJU.js`（124 B）是注册胶水空壳，不是「echarts 只有 120 字节」；
maps 仍 >800 kB 且**故意不抬** `chunkSizeWarningLimit`（抬阈值只掩盖告警）；
按需注册必须显式 `use(LegacyGridContainLabel)`——ECharts 6 把 `grid.containLabel` 移入 legacy，
漏注册会告警且**静默失效**（BudgetChart 布局错位），这一点由 SSR 直出三处 option 验证为零告警。

## 6. MySQL 8.4：旧库升级 + 重启恢复（80/80 PASS）

新增 `.e2e/acceptance/mysql-upgrade-acceptance.ps1`（一键可复跑，总耗时 177.6s）：

| 阶段 | 真实结果 |
|---|---|
| 实例 | 便携 **MySQL 8.4.10**，端口 **3318** / 独立 datadir / 后端 18094（不碰 :3306 的 8.0 服务） |
| V15 探针 | 库可停在 V15（14 行 / max=15），但**旧代码启动即失败**：`Schema-validation: missing column [content_hash] in table [pending_plan]` → **V15 不是可运行发布版本** |
| 旧部署基线 | 改用真实旧发布 `9918a4f`（最高迁移 V16）+ `target=16`，启动 12.4s、validate 通过、25 表 |
| 灌数据 | 真实 HTTP：登录、建行程（苏州/¥8000）、3 条目、2 支出、2 备忘、1 日程、规划 run、确认、留 1 条待确认方案 + SQL 补旧列 |
| 升级 | 只应用 **V17（trip.party_size）+ V18（agent_task DAG）**，17 行 / max=18 / 0 失败，`validate` 通过，业务表 25→25 |
| 数据完整性 | **24/24 表行数 + 旧列内容哈希一致**；关键字段快照逐字节一致；新列回填正确（`party_size=1`、`attempt=0`） |
| 三态重启 | 仅后端 15.1s / 仅 MySQL 连接池自动恢复 / 整栈 28.5s；每次旧 runId 可查、计数一致、`flyway_schema_history` 不变（迁移不重跑） |
| 环境标注 | Windows + **无 Docker**，全程原生 `mysqld`；未运行、也未声称运行 `docker compose`；Flyway 对 8.4 仅 WARN（"latest supported … is 8.1"） |

> 这条路径恰好覆盖「给已有表补 NOT NULL DEFAULT 列」这一最容易翻车的迁移类型。

## 7. 暗色地图与图表 + PWA（已修复并量化）

| 项 | 修复前 | 修复后 |
|---|---|---|
| 行程地图底图 | 写死 `amap://styles/normal`，暗色下仍是亮底图 | `amap://styles/dark`，主题切换只 `setMapStyle` + 重绘、不重建地图；SDK 未就绪时挂起 |
| 地图区域平均亮度（sRGB 加权） | 220.66 | **61.13**（暗/浅 = 0.277；<80 亮度像素 0.52% → 77.92%） |
| BudgetChart 文字对比度 | 浅色文字写死，暗色下不可读 | **13.68:1**（AA 4.5 通过） |
| ExpensePanel 标签对比度 | 同上 | **6.81:1**（AA 通过） |
| 自检卡片 | 只按「配置了」显示 | 「未配置 / 已配置未上报 / 已配置未验证 / 已验证」四态，`routeLastError` 原样展示 |

WebKit/PWA 复跑：iPhone 13（WebKit）**15 PASS + 1 INFO**、Pixel 7（Chromium）**15 PASS + 1 INFO**，
合计 **32 项 0 FAIL**，与基线一致（INFO 为既有离线记录项）。**真机 Safari 仍未测**。

## 8. 韧性四项复跑（新基线）

| 项 | 旧基线 | 本轮 | 判定 |
|---|---|---|---|
| 并发 chat / runs | 40/40、20/20；p95 60.9 / 14.1 ms | 40/40、20/20；p95 **85.5 / 9.9 ms**（冷启动首轮 p95 385ms，预热后对齐） | PASS（0 5xx/超时/串号） |
| 慢依赖 · 单次 | 30.03 s | 30.014 s | 读超时精确 |
| 慢依赖 · 完整规划 | 128.13 s / 5 次调用 | **30.05 s / 1 次调用** | 预算收敛（见 §2） |
| 第三方 402 / 500 / 连接拒绝 | PASS | **137.6 / 122.5 / 42.5 ms**，均产出方案并降级 | PASS |
| 高德无 Key | PASS | 7 段全 estimated、`polyline` 全空 | PASS |
| 高德无效 Key | **REVIEW（报 amap）** | **PASS（报 estimated）** | 缺陷已修复 |
| 长跑（12 分钟） | 576/576，RSS +2% | **576/576**，预热后 RSS 573.5→587.6 MB（**+2.5%**，斜率 1.3）、线程 72→72、health UP | PASS |

**冷 JVM 首轮长跑判 REVIEW（如实记录）**：RSS 479.9→575.7 MB（+20%，斜率 11.7 MB/min）。
`jcmd` 诊断：全量 GC 后活跃堆仅 **56 MB**，空闲 45s→60s RSS 恒为 573.5 MB 不增长；预热后同实例 12 分钟重跑 +2.5%。
结论：**冷 JVM 的堆提交/metaspace 扩张，不是本 jar 的泄漏回归**（旧基线实例已长期预热故平坦）。
`llm402-real` 记 **SKIP**：:18091 只读实例不存在，按约定不代建——这是环境限制，不是产品缺陷。

## 9. 仍未验证 / 风险清单

1. ~~高德「Web 服务」Key~~ → **2026-11-19 晚已拿到并验通**（7/7 段 amap、几何 3~41 点、浏览器已按几何绘制）。
2. **MCP 真实链路**：`MCP_ENABLED=true` + `AMAP_MAPS_API_KEY` 未用真实 Key 跑，12 个工具输出未逐一核对。
3. **FR-03 真模型**：DeepSeek 余额 402，仍只有替身链路证据。
4. **真机**：iPhone/Android 的 Safari/Chrome 真机渲染与 PWA 系统级安装需人工；WebKit 仿真不算真机。
5. **P6 的真实限流时序**：重试行为由 MockRestServiceServer 验证，真实 10021 时序未复现（需真实 Key + 并发）。
6. **P1 边界**：审核阶段内部的外部调用超时不受预算约束（见 §2）。
7. **FR-11/FR-12**：按决策继续延后，前置是商务授权与库存接口，不是代码。
   同日更晚已实现其中的**防守项**（参考价 + 来源 + 深链，不改 schema），见 CHANGELOG §四；
   「实时可订价」仍**未**接入。

## 10. 复现命令

```powershell
# 后端全量
cd travel-agent-server; mvn -B verify "-DargLine=-Djdk.attach.allowAttachSelf=true"

# 前端
cd travel-agent-web; npm run typecheck; npm test; npm run build

# 真实 HTTP FR 断言（对任意已启动实例）
node .e2e/acceptance/fr-acceptance.mjs --base http://127.0.0.1:18100 --out .evidence/reports/fr.json
node .e2e/acceptance/fr-acceptance.mjs --base http://127.0.0.1:18100 --expect-amap --amap-key <Key>

# MySQL 8.4 旧库升级 + 三态重启（一键，自清理）
powershell -ExecutionPolicy Bypass -File .e2e/acceptance/mysql-upgrade-acceptance.ps1

# P2 自报 / P1 预算（自建实例 + 替身，用后自动清理）
node .e2e/resilience/manage-instance.mjs serve --amap-key=INVALID_amap_key_for_test   # 后台
node .e2e/resilience/run-amap-selfreport.mjs
node .e2e/resilience/run-budget-p1.mjs
node .e2e/resilience/manage-instance.mjs stop

# 韧性四项
node .e2e/resilience/run-concurrency.mjs; node .e2e/resilience/run-slow-llm.mjs
node .e2e/resilience/run-third-party.mjs --part=stub-errors
node .e2e/resilience/run-soak.mjs --minutes=12 --interval=5000

# 暗色 + PWA 复验
node .e2e/dark/start-instance.mjs        # 18096 + 独立 H2
node .e2e/dark/verify-dark.mjs           # 截图 + 亮度采样
node .e2e/mobile/mobile-pwa-check.mjs --base http://127.0.0.1:4173 --api http://127.0.0.1:18096
```

## 11. 证据索引

| 文件 | 内容 |
|---|---|
| `.evidence/reports/fr-postfix.json` | 合并后 jar 的 27 项 FR 断言（27/27 PASS） |
| `.evidence/reports/resilience-postfix.json` | P2 A1–A10、P1 P1-1..P1-7、冷启动长跑与 jcmd 诊断、SKIP 记录 |
| `.evidence/reports/resilience-postfix.md` | 由脚本生成的 summary（数字不手填） |
| `.evidence/reports/resilience.json` | 四项复跑原始数据 + `resolvedIssues` |
| `.e2e/acceptance/evidence/mysql84-upgrade-results.json` | 80 条升级断言与阶段耗时 |
| `.e2e/acceptance/evidence/mysql84-upgrade-summary.md` / `-logs/` | 人读摘要 + 27 个原始日志/快照 |
| `.e2e/acceptance/evidence/mysql84-upgrade-logs/flyway-v15-probe.txt` | 「V15 不是可运行旧基线」的原始证据 |
| `.evidence/dark/luminance-analysis.json` / `dark-evidence.json` | 暗色地图亮度与图表对比度量化 |
| `.evidence/dark/screens/` | 暗色地图/图表/自检卡截图 |
| `.evidence/web-bundle/comparison.json` | 分包 before/after 全量数据 |
| `.evidence/dark/mobile-pwa.json` | WebKit/Chromium PWA 32 项结果 |

> 原始大文件在 `.evidence/`（gitignore）；随仓库提交的是本报告与 `.e2e/acceptance/evidence/` 下的精编证据。