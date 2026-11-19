# V2.2 正式验收报告（2026-11-19）

> 验收对象：本仓库 V2.2 迭代（FR-01 ~ FR-10、PWA、稳定性、外部 Provider）
> 执行环境：Windows 11 / JDK 17.0.1 / Maven 3.9.16 / Node 20.17.0 / 本机 MySQL 8.0.46 服务 + **全新便携 MySQL 8.4.10**
> 证据：`.e2e/acceptance/evidence/`（精编、随仓库提交）；原始产物在 `.evidence/`（本地，约 780MB，已 gitignore）
> 复跑入口：`.e2e/acceptance/fr-acceptance.mjs`、`.e2e/acceptance/mysql-local-acceptance.ps1`、
> `.e2e/acceptance/mysql-native-3307-acceptance.ps1`、`.e2e/mobile/mobile-pwa-check.mjs`、`.e2e/resilience/`

## 0. 结论先行

**FR-01 ~ FR-10 已全部验收。** FR-05 的真实 REST 链路由同日并行会话用真实「Web 服务」Key 跑了两次并 PASS
（本报告 §3.6 逐文件核对了证据）；仍留两项尾巴：MCP 真实 Key 链路未验、高德 QPS 限流未做重试/可观测。
FR-03 的真模型复验仍受 DeepSeek 余额（402）限制，链路已用本地替身验通。
PWA 离线恢复在验收中发现真实缺陷并已修复；稳定性四项通过但有 1 个设计问题（慢依赖无端到端预算）。
Docker Compose 因本机无 Docker 未执行，改用**全新便携 MySQL 8.4.10 实例**做了等价验证（偏差已记录，Compose 脚本已就绪）。

| FR | 能力 | 结论 | 主要证据 |
|---|---|---|---|
| FR-01 | 自然语言需求理解（槽位/追问/人数） | ✅ **已验收** | 4/4：合并追问、跨轮补槽、兜底明示、`partySize=2` 落库 |
| FR-02 | Coordinator 任务编排（依赖图/并行/重派） | ✅ **已验收** | 3/3：依赖边、PREVIEW‖REVIEW 重叠、REPLAN + 重派 `replacesTaskId` |
| FR-03 | Agent 自主工具调用循环 | ✅ **已验收**（链路） | RESEARCH 自主选 `query_weather` → 观察 → FINAL；**真模型 402，见 §4.2** |
| FR-04 | 多 Agent 任务协议 + 过程事件 | ✅ **已验收** | 4 类 receiver、任务字段齐全、SSE `run/delegate/complete` |
| FR-05 | 真实旅行 Provider（POI/地理编码/路线/GCJ-02） | ✅ **已验收**（REST 链路） | 真实「Web 服务」Key 两次实测：`route-legs` **6/7** 与 **4/7** 段 `source=amap`；POI 3 条、地理编码 3/3、GCJ-02 偏差 **0.13m**。MCP 真实链路未验；无效 Key 自报失真（P2）仍待修 |
| FR-06 | Reviewer 审核与动态调整 | ✅ **已验收** | 预算 100 → `blocked` + `requiresAcknowledgement`；无 ack 拒绝、带 ack 通过 |
| FR-07 | 交互地图与行程（路线段/来源徽标） | ✅ **已验收**（estimated 路径） | `route-legs` 7 段字段完整、`source` 合法；`amap` 段数据已在 FR-05 出现，但「实线/虚线」在浏览器里的区分渲染未用真实 Key 复验 |
| FR-08 | 计划版本与内容指纹确认 | ✅ **已验收** | 64 位 SHA-256；错误指纹拒绝且方案不消费；正确指纹提交成功 |
| FR-09 | 确认后幂等同步 | ✅ **已验收** | 三模块 `SUCCEEDED`、台账可查、重复确认被拦截 |
| FR-10 | 全链路 Trace | ✅ **已验收** | run 可回查、45 次工具调用 100% 成功、未登录 401、他人 run 404 |

## 1. 验收方法

| 层 | 方法 | 规模 |
|---|---|---|
| 后端 | `mvn -B verify -DargLine=-Djdk.attach.allowAttachSelf=true`（沙箱环境需允许 self-attach） | 395 用例 |
| 前端 | `npm run typecheck` + `npm test` + `npm run build` | 23 文件 / 172 用例 |
| HTTP 验收 | `.e2e/acceptance/fr-acceptance.mjs`：真实登录/规划/确认/追踪/路线段，逐 FR 断言 | 27 项/轮，H2 + MySQL 各一轮 |
| 数据库 | 便携 MySQL 8.4.10 全新 datadir：`--initialize-insecure` → Flyway V1..V18 → 核心链路 → 三种重启 | 30 项 + 27 项 |
| 浏览器 | 既有 Playwright e2e（5 用例）+ 移动端独立脚本（iPhone 13 WebKit / Pixel 7 Chromium） | 5 + 32 项 |
| 稳定性 | 并发 / 慢依赖 / 第三方失败 / 11.9 分钟长跑 | 576 请求长跑等 |
| 真实 Provider | 高德 REST 直连诊断 + 后端 `route-legs.source` 统计 | **阻塞：无 Key** |

## 2. 环境与偏差（必读）

| 项 | 计划 | 实际 | 影响 |
|---|---|---|---|
| 数据库 | **Docker Compose + `mysql:8.4`** | 本机**无 Docker / WSL / Podman**（非管理员，Docker Desktop 需提权 + 重启）。改用便携 `mysql-8.4.10-winx64`（独立 basedir/datadir、`:3317`，不碰 `:3306` 的 8.0 服务） | 迁移/核心链路/重启恢复**等价验证通过**；`docker compose up --build` 路径未执行，脚本 `.e2e/acceptance/mysql-compose-acceptance.ps1` 已就绪 |
| 高德 | 「Web 服务」Key 真实联调 | 本会话未收到 Key 明文；但同日 **14:31–14:39 的并行会话**用真实 Key 跑了两次探针（`.e2e/amap-key-20261119-143613/`、`143839/`，Key 指纹 `56257ebd`）并提交文档 | FR-05 依据该证据验收（REST 链路）；本会话**逐文件核对**了 checks/route-legs/GCJ 证据，但未亲手复跑 |
| 大模型 | DeepSeek 真实调用 | Key 存在但**余额为 0**：`402 Insufficient Balance`（见 `evidence/llm-402-evidence.md`） | FR-03 用本地 OpenAI 兼容替身完成链路验收；真模型复验命令已给 |
| 真机 | iPhone Safari / Android Chrome | 用 Playwright **WebKit 2370（WebKit 27.2）+ Chromium 156** 真引擎仿真；真机系统级安装 UI 无法自动化 | 仿真全部 PASS；真机清单见移动端报告 §5 |

> 偏差原则：**没有做的事不写成做过**。以上四项在报告与文档状态表中均显式标出。

## 3. 验收结果

### 3.1 构建与自动化基线

| 项 | 结果 |
|---|---|
| 后端 `mvn verify` | ✅ **395 / 395** 通过，BUILD SUCCESS（01:13） |
| 后端说明 | 受限沙箱下 Mockito inline mock maker 会因 JVM attach 失败大面积报错，加 `-DargLine=-Djdk.attach.allowAttachSelf=true` 后全绿（与仓库既有口径一致） |
| 前端 typecheck | ✅ 0 error |
| 前端单测 | ✅ **172 / 172**（23 文件；含本轮新增 6 个离线缓存用例） |
| 前端构建 | ✅ 成功，PWA precache **42 entries / 3604.68 KiB** |
| 既有浏览器 e2e | ✅ **5 / 5**（登录、建行程、行程页/路书、攻略表单、路由守卫、PWA 资源可达） |

### 3.2 FR-01 ~ FR-10 真实 HTTP 断言

同一套断言跑了两遍，结论一致：

| 运行 | 数据库 | 结果 | 报告 |
|---|---|---|---|
| 隔离实例 A | 全新 H2 文件库（`:18090`） | **27 / 27 PASS**（0.5s） | `evidence/fr-acceptance-h2.json` |
| MySQL 轮 | 全新 MySQL 8.4.10（`:18093` + `:3317`） | **27 / 27 PASS**（3.2s） | `evidence/fr-acceptance-mysql84.json` |

关键断言摘录：

- **FR-01**：「帮我规划行程」→ `missingSlots=["destination","days","partySize"]` 且无 `planId`；
  「上海」→「三天」→「两个人」逐轮收敛，最后 `NEED_CONFIRMATION` 且消息含「2 人」「预算按 ¥5000」；确认后 `partySize=2`。
- **FR-02**：任务链 `INTENT → PLAN → PREVIEW → REVIEW → REPLAN → PREVIEW#1 → COMMIT`；
  `PLAN.deps=[INTENT]`、`PREVIEW/REVIEW.deps=[PLAN]`；PREVIEW 与 REVIEW 时间区间真实重叠；
  REPLAN 依赖首轮 REVIEW；重派 PREVIEW `attempt=1`、`replacesTaskId=首轮预览`。
- **FR-04**：receivers = `intent-agent / planner-agent / executor-agent / reflect-agent`；
  SSE 收到 `start → run → delegate → intent → planning → delegate → tool_call → tool_result → complete`。
- **FR-06**：预算 100 → `review.blocked=true`、`attempts=1`、`issues=[BUDGET_OVERRUN/HIGH]`、`riskLevel=HIGH`；
  不带 `acknowledgeIssues` 提交返回 FAILED 且方案不消费；带 ack 后 COMPLETED。
- **FR-08**：错误 `contentHash` →「方案内容与确认请求不一致」且方案仍可取回；正确指纹 → COMPLETED。
- **FR-09**：`calendar/budget/footprint` 全 `SUCCEEDED`，calendar `idempotencyKey` 绑定 planId；台账可查；重复确认被拦截。
- **FR-10**：该轮 45 次工具调用全部 `success=true` 且挂 `taskId`；未登录 401；他人 run 404。

### 3.3 全新 MySQL 8.4：安装 / 迁移 / 核心链路 / 重启恢复

两套脚本各自独立执行，互为印证：

| 步骤 | 结果（本地脚本 `mysql-local-acceptance.ps1`） | 结果（专项脚本 `mysql-native-3307-acceptance.ps1`） |
|---|---|---|
| 版本 | `8.4.10`（`SELECT VERSION()`） | `8.4.10` |
| 全新 datadir | `--initialize-insecure` 成功，迁移前 **0 张表** | 独立 datadir / my.ini，全新库 |
| Flyway | **17 个迁移全成功**，`now at version v18`，执行 4.2s，0 失败 | 迁移全部成功、到 V18、0 失败 |
| `ddl-auto=validate` | 通过（启动即证明） | 通过 |
| 表数量 | **25** | 25 |
| 启动耗时 | 20.6s | 16.6s |
| 核心链路 | 登录→规划→确认（三模块 SUCCEEDED）→追踪→路线段 7 段 | 同左，路线段 10 段；C1~C11 全 PASS |
| 仅重启后端 | 数据一致（trips 4 / runs 8 / itinerary 10），旧 runId 仍可查 | R1/R3/R4 通过，迁移不重跑，写链路可用 |
| 仅重启 MySQL | 后端连接池自动恢复（读 + 写新规划均成功） | R6/R7/R8 通过（3.1s 恢复） |
| 整栈重启 | —（本地脚本未覆盖整栈） | R9/R10 通过：数据不丢、迁移不重跑（12.6s） |
| 断言 | **27/27 FR + 三重启人工核对全通过** | **30/30 PASS** |

> `flyway_schema_history` 三次重启后恒为 17 行 —— **迁移不重跑**，这是重启恢复的关键不变量。
> 已知告警：Spring Boot 3.5.7 自带 Flyway 对 MySQL 8.4 打印 “latest supported version of MySQL is 8.1”，
> **仅 WARN**，17 个迁移与 validate 全部通过；建议后续升级 Flyway（已记录在 deployment §6.3.2）。

### 3.4 移动端（iPhone Safari / Android Chrome）与 PWA

| 项 | 结果 |
|---|---|
| 引擎 | iPhone 13 = **webkit 2370（WebKit 27.2）**；Pixel 7 = Chromium 156（Android 14 UA） |
| 断言 | **32 项：30 PASS / 0 FAIL / 2 INFO** |
| PWA 安装硬指标 | manifest 200（`standalone`、192/512 图标）、`<link rel=manifest>`、SW `activated`、页面受控 |
| 离线 | `context.setOffline(true)` 后 reload：app shell 打开、提示条出现、**已看过的行程时间轴 5 行仍渲染**、不踢回登录页 |
| 恢复在线 | 行程重新拉取、无横向溢出 |
| 横向溢出 | iPhone 390px / Pixel 412px 全部 `overflowPx=0` |

**本轮修复的真实缺陷**：旧实现下离线冷启动被 `/auth/me` 网络失败挡在「登录状态校验失败」兜底页，
SW 缓存的数据永远渲染不出来（只做到「不白屏、不踢人」）。修复：`authStore` 缓存上次登录的公开资料
（不含 token）+ `App.tsx` 网络错误时离线放行 + `online` 事件重新校验（401 才登出）。
回归：前端 166 → **172 用例**、移动端脚本 0 FAIL。详见 [2026-11-19-mobile-pwa.md](2026-11-19-mobile-pwa.md)。

### 3.5 并发 / 慢接口 / 第三方失败 / 长时间运行

| 项 | 关键数字 | 结论 |
|---|---|---|
| 并发 | chat 40/40、runs 20/20 成功；5xx=0、超时=0、planId 重复=0、串号=0；chat p50 47.4 / p95 60.9 / max 62.3ms；runs p95 14.1ms | ✅ 通过 |
| 慢依赖 | 单次调用在 30.03s 被截断并降级成功；**但一次规划 5 个串行 LLM 阶段 → 端到端 128.13s**；期间 health/runs 正常，恢复后 5/5 成功、无线程挂死 | ⚠ 功能通过，**P1 无端到端预算/熔断** |
| 第三方失败 | 真实 402（2.59s 降级）、替身 402（95.7ms）、500（94.3ms）、连接拒绝（68.6ms）均降级成功；高德无 Key → 7/7 estimated 且状态自报 estimated | ✅ 通过 |
| 高德无效 Key | route-legs 7/7 estimated，但 `/api/system/status` 仍报 `routeProvider=amap` +「使用高德真实路径」 | ❌ **P2 自报失真** |
| 长跑 | 716.2s / 576 请求 **0 失败、0 5xx**；p50 3.7 / p95 8.6 / p99 12.8ms；p95 无恶化；RSS 408.0→416.1MB（+2%，回归斜率 −3.4MB/min，GC 锯齿）；线程 84→86；health UP | ✅ 通过 |

完整数据与复现命令见 [2026-11-19-resilience.md](2026-11-19-resilience.md)。

### 3.6 真实高德联调（FR-05，REST 链路已 PASS）

真实「Web 服务」Key（指纹 `56257ebd`，不落明文）由并行会话在 14:31–14:39 起独立实例（`:18097` + 独立 H2）
跑了两次探针，结论均 PASS；本节逐文件核对了 `checks.json` / `05-route-legs.json` / `10-gcj02-transform.json`。

| 项 | 实测结果 | 判定 |
|---|---|---|
| 系统自检 | `amapConfigured=true`、`geoProvider=amap`、`routeProvider=amap` | PASS |
| 路线段 | 7 段中 **6 段 `source=amap`**（另一次 4/7）；首段「滨江汉庭酒店 → 西湖风景区」10.7km / 驾车 31min | PASS（判定口径 amap≥1） |
| 直连 POI | `/v3/place/text` `status=1`，3 条 POI（西湖风景名胜区 / 断桥残雪 / 湖滨公园），坐标境内 | PASS |
| 地理编码 | `/v3/geocode/geo` **3/3** `status=1`：西湖 120.130396,30.259242、杭州东站 120.212600,30.290851、天安门 116.397463,39.909187 | PASS |
| GCJ-02 | 后端 `wgs84ToGcj02` 与高德 `/v3/assistant/coordinate/convert` 偏差 **0.13m**；偏移量 554.7m；往返误差 **0m** | PASS |
| Key 类型踩坑 | 同一枚「Web端(JS API)」Key 在 place/geocode/convert 四个端点全部 `USERKEY_PLAT_NOMATCH / 10009`，后端静默降级 | 已定论（文档 §5.2/§7） |
| 无 Key 降级 | `routeProvider=estimated`、7/7 `source=estimated`、`routeHint` 如实说明「估算」 | PASS |
| 无效 Key | 路线仍降级 estimated，但状态页仍宣称 amap | ❌ P2（`RouteService.provider()` 只看配置不看调用结果） |
| QPS 限流 | 8 并发算路 3 次 `CUQPS_HAS_EXCEEDED_THE_LIMIT / 10021` → 静默降级且结果被缓存 10 分钟 | ⚠ P6（缺重试与 WARN） |
| MCP 真实链路 | `MCP_ENABLED=true` + `AMAP_MAPS_API_KEY` 未用真实 Key 跑 | 遗留项（12 个工具的业务输出未逐一核对） |

## 4. 本轮发现

### 4.1 已修复

| # | 问题 | 修复 | 验证 |
|---|---|---|---|
| F-1 | PWA 离线冷启动被登录态校验挡住，缓存行程不渲染 | `authStore` 公开资料缓存 + `App.tsx` 离线放行 + `online` 重校验 | 移动端 32 项 0 FAIL；前端 172 用例 |

### 4.2 未修复（按优先级）

| # | 级别 | 问题 | 建议 |
|---|---|---|---|
| P1 | 高 | 慢依赖无端到端时间预算/熔断：一次规划 128s，同步接口无服务端总超时 | 给 Agent 主链路加总预算（如 30s）+ 每阶段降级；SSE 异步池 4/16/100 需按慢规划评估 |
| P2 | 高 | 高德无效 Key 时 `routeProvider` 自报失真（违反 ADR-006「估算必须自报家门」） | `provider()` 结合最近一次真实调用结果；状态页区分「已配置」与「已验证」；失败打日志 |
| P3 | 中 | 降级日志丢根因（只打 wrapper message） | 记录 cause（SocketTimeout/ConnectException） |
| P4 | 低 | 日志文件默认平台字符集（中文 Windows = GBK），UTF-8 检索漏匹配 | logging.charset 固定 UTF-8 |
| P5 | 中 | LLM 100% 失败时 `/api/system/status` 仍报 `llmConfigured=true` | 区分 configured / last-call-ok |
| P6 | 中 | 高德 QPS 限流（10021）静默降级 + 结果被缓存 10 分钟；8 并发实测 3 次命中 | `AmapDataService.direction` 对 10021 做退避重试 + WARN（记 infocode），不缓存失败结果 |

> **同日晚上已修复并复核**：P1/P2/P3/P5/P6 全部收口，另补上真实道路 polyline、首屏分包与 MySQL 旧库升级回归。
> 逐项证据与仍然未验的项见 [2026-11-19-fix-verification.md](2026-11-19-fix-verification.md)：
> P1 端到端 128.13s→30.05s、P2 无效 Key 10/10 断言 PASS、P6 重试+60s TTL、MySQL 旧库升级 80/80 PASS、
> 首屏静态闭包 1168.79→558.26 KiB。本表保留验收当时的原始结论，不做追溯修改。

### 4.3 FR-03 的真模型说明

DeepSeek Key 余额为 0（402）。为不把「链路没验证」写成「已验证」，用本地 OpenAI 兼容替身（`.e2e/acceptance/stub-openai-llm.mjs`）
走**真实 `OpenAiCompatibleLlmClient` → HTTP → 决策解析 → ToolInvoker → trace**，替身只负责按协议回
`CALL_TOOL query_weather` → `FINAL`。结论：自主循环、观察回灌、工具执行、追踪落库全部真实；
**模型决策质量未验证**。Key 充值后复跑：`fr-acceptance.mjs --base ... --expect-llm`。

## 5. 产品决策（实时酒店/餐饮 Provider 与个性化 RAG）

详见 [2026-11-19-provider-rag-decision.md](2026-11-19-provider-rag-decision.md)。结论：

- **FR-12 实时房态/可订价：延后（P1）**。高德只有地图/POI 能力，无库存与报价；瓶颈是 OTA 商务授权而非代码。
  现在硬做只会产出「看起来实时」的假数据，违反 ADR-006。
- **FR-11 个性化 RAG：延后（P1）**。现有 RAG 是字符 bigram 词面匹配、无阈值/无 rerank/无评估集，
  且不在规划链路里（只服务知识问答）；先补检索质量基线，再谈个性化。
- **本轮唯一建议动手的防守项**：价格「参考价 + 来源 + 深链」，补 ADR-006 在价格维度的欠账（10~16 人日）。

## 6. 遗留清单（按阻塞程度）

1. **高德 MCP 真实链路**：`MCP_ENABLED=true` + `AMAP_MAPS_API_KEY` 尚未用真实 Key 跑；12 个工具的业务输出未逐一核对。
2. **Docker Desktop**：本机仍无 `docker` 命令；装好后执行
   `.e2e/acceptance/mysql-compose-acceptance.ps1 -AmapKey <key>` 即可产出与 §3.3 等价的 Compose 证据（含 `mysql:8.4` 镜像）。
3. **DeepSeek 余额**：充值后复验 FR-03 真模型。
4. **P1/P2/P6 修复**：建议下一批优先；P2 与 FR-05 的「无效 Key 自报」一起收口。
5. **真机人工确认**：按移动端报告 §5 清单在 iPhone/Android 上点一遍。
6. **Key 保管**：真实高德 Key 只在并行会话的进程环境里用过，未写入仓库/.env.local；后续联调请继续走环境变量。

## 7. 复现命令

```powershell
# 后端单测（沙箱需允许 self-attach）
cd travel-agent-server
mvn -B verify "-DargLine=-Djdk.attach.allowAttachSelf=true"

# 前端
cd travel-agent-web
npm run typecheck; npm test; npm run build; npm run e2e   # e2e 需后端在 18090 且 E2E_API_TARGET 指向它

# FR 验收（对任意已启动实例）
node .e2e/acceptance/fr-acceptance.mjs --base http://127.0.0.1:18090 --out .evidence/reports/fr.json
# 拿到 Key 后：
node .e2e/acceptance/fr-acceptance.mjs --base http://127.0.0.1:18090 --expect-amap --amap-key <Key>

# 全新 MySQL 8.4（便携版，自动初始化/迁移/重启）
powershell -ExecutionPolicy Bypass -File .e2e/acceptance/mysql-local-acceptance.ps1

# Docker Compose（有 Docker 时）
powershell -ExecutionPolicy Bypass -File .e2e/acceptance/mysql-compose-acceptance.ps1 -AmapKey <Key>

# 移动端 PWA
node .e2e/mobile/serve-dist.mjs --port 4173 --api http://127.0.0.1:18090 &
node .e2e/mobile/mobile-pwa-check.mjs --base http://127.0.0.1:4173 --api http://127.0.0.1:18090

# 稳定性
node .e2e/resilience/run-concurrency.mjs; node .e2e/resilience/run-soak.mjs
```

## 8. 证据索引

| 文件 | 内容 |
|---|---|
| `evidence/fr-acceptance-h2.json` | H2 实例 27 项 FR 断言 |
| `evidence/fr-acceptance-mysql84.json` | MySQL 8.4.10 实例 27 项 FR 断言 |
| `evidence/mysql84-local-summary.md` | 本地 MySQL 8.4 全流程（迁移/链路/双重启） |
| `evidence/mysql84-3307-summary.md` / `mysql84-3307-results.json` | 专项 30 项断言与三态重启 |
| `evidence/fr-03-stub-llm.json` / `llm-402-evidence.md` | FR-03 链路证据 + 真模型 402 原文 |
| `evidence/mobile-pwa.json` / `mobile-pwa-summary.md` / `screenshots/` | 移动端 32 项结果与截图 |
| `evidence/resilience.json` | 并发/慢依赖/第三方/长跑原始数据 |
| `.e2e/amap-key-20261119-143839/`（本地，gitignore） | 高德真实 Key：checks.json、POI、geocode、GCJ-02、route-legs（6/7 amap） |
| `.e2e/amap-key-20261119-143613/`（本地，gitignore） | 高德真实 Key 第二次实跑（4/7 amap） |
| `.evidence/`（本地） | 原始库、日志、完整截图（约 780MB，gitignore） |