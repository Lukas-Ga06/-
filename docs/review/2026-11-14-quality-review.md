# travel-agent 测试报告与改进建议

> 快照日期：2026-11-14 ｜ 验证范围：全栈质量门禁、后端 / 安全、前端三门禁、运行时实测 ｜ 结论：核心闭环可跑、三门禁全绿；上线前仍缺并发锁、凭证边界与可重复质量门禁（详见 §0.1）

- 日期：2026-11-14 · Asia/Shanghai
- 被测基线：`79489ab`（2026-11-08）**+ 工作区未提交改动**（`AmapDataService`/`AmapPoi`/`AmapRoute` 与 `web-course/`、`deploy-package/`、`docs/proposals/` 均为 untracked）
- 被测范围：`travel-agent-server`（Spring Boot 3.5.7 / Java 17 / 187 个 Java 源文件 / 约 14.9k 行）+ `travel-agent-web`（React 18 / TS / Vite 6 / 70 个 ts+tsx / 约 9.3k 行）
- 方式：**4 路并行**——基线门禁复跑 + 后端/安全结论独立核验 + 真实启动服务做运行期实测 + 前端核验；关键 P0 另写了并发探针做实证
- 标注约定：**【实测】** = 真实运行服务/探针跑出来的数字；**【静态】** = 代码路径可复核但未动态验证；**【待验证】** = 依赖部署形态

> 本报告不采信任何历史结论，所有条目均由本次独立复核产出；与历史审计不一致处以本报告为准。

> **修复进展（2026-11-16）**：本报告 §4 的 P0-1/P0-2/P0-3、P0-4、P1-1～P1-7 以及前端的 P1-8/P2-5
> 已在「批次 A/B/C」修复并通过验证——后端 239 个用例全绿（含新增并发/隔离/RAG/配额测试）、
> 并发探针 `duplicatedRounds=0`（修复前 5/5 复现）、运行期安全复验 **20/20** 通过、
> e2e 3 轮连跑全绿。逐项状态见 §9，行为变更见 `docs/CHANGELOG.md` 第七批。
> 仍未完成的是 §4 中 P1-4（schema 双轨）与 P2 清单的大部分条目。

---

## 0. 摘要

### 0.1 一句话结论

**这是一个"完成度明显高于同规模项目"的全栈应用：开箱即用、核心闭环真实可跑、文档与工程规范相当扎实；但它离"可放心上线"还差三件事——一把并发锁、一层凭证边界、一条可重复的质量门禁。**

核心闭环【实测，无 LLM 规则引擎路径】：登录 → 建行程 → 自然语言规划（15 项变更 / 预算 1626）→ 确认 → 行程 15 项（5/5/5）→ 预算 3000/1626/0 自洽，7 步全 200。前端 `typecheck`/`vitest`/`build` 三门禁全绿，后端 `mvn verify` 204 用例全绿。

但"全绿"只覆盖了顺序、单用户、无故障的路径。本次测试发现：

| 类别 | 结论 |
|---|---|
| **1 个已复现的数据一致性 P0** | 并发确认同一方案 **5/5 轮**产生双行程/双份条目/双份操作历史，且失败方收到 500 而数据已双写 |
| **3 个已在运行环境坐实的安全高危** | 媒体票据 `?ticket=` **等价完整登录且可自我续期**（实测无 Cookie 写入成功）；`X-Forwarded-For` 可无限绕过登录限流（实测 25 次 0 个 429）；个人知识库**跨用户检索**（B 实测读到 A 的私有文本） |
| **1 个架构性缺口** | Agent 没有工具调用循环：模型只能调 `mcp_*` 工具，8 个内置工具由 Java 代码硬编码顺序调用，`AgentPlan` 无"动作节点" |
| **1 组工程基建缺口** | 无覆盖率、无静态分析、无 ESLint、无依赖扫描；e2e 因限流桶污染**不可重复**（同一命令 5 分钟内二次运行必挂） |

### 0.2 健康度评分（主观，依据见正文）

| 维度 | 分数 | 依据 |
|---|---|---|
| 功能完整性与闭环 | 8.5 / 10 | 旅行主链路 + 记账/备忘/日程/办公/学习/健康全覆盖，实测闭环通过 |
| 代码质量与分层 | 8 / 10 | 只读工具闸门、降级、异常语义、注释质量都好；问题集中在并发与外部副作用 |
| 测试有效性 | 6 / 10 | 断言真实、无注水；但并发 0、SSE 协议 0、MySQL/Flyway 0、前端组件 0 |
| 安全 | 5 / 10 | IDOR/上传/XSS/注入做得很好；凭证与旁路入口有 3 个 HIGH |
| 并发与一致性 | 3 / 10 | P0 已复现；提交/撤销无锁；配额判断非原子 |
| 工程质量基建 | 6.5 / 10 | CI 三门禁齐全；缺覆盖率/lint/静态分析/依赖治理，e2e 不稳定 |
| 文档 | 8 / 10 | `docs/guide/` 4788 行、行号引用可核对；README 有 6 处陈旧/过度宣称 |
| 前端性能与体验 | 6 / 10 | 3 个 >1MB chunk、PWA 预缓存 3.7MB、429 会被误判为登出 |

### 0.3 最值得先做的 5 件事（按 ROI 排序）

1. **把"确认"改成原子消费**（`PendingPlanRepository` 加条件 UPDATE/DELETE + `@Version`），并给提交加幂等键 —— 消灭已复现 P0。
2. **把票据的用途落到请求上**（仅 `GET` + 白名单 path），一举解决"票据=完整登录"和"票据自续期"。
3. **限流客户端 IP 只信受信代理**（后端取可信代理网段，nginx 改 `$remote_addr`），恢复登录爆破防线。
4. **个人知识库加 `userId` 隔离**（实体+迁移+向量检索过滤）。
5. **e2e 限流隔离 + 单次登录 storageState** —— 让"跑一次绿、跑两次红"变成可重复门禁。

---

## 1. 测试范围与可复现命令

### 1.1 环境【实测】

| 项 | 值 |
|---|---|
| OS / Shell | Windows 10 amd64 / git bash（MSYS2，无 Docker、无 jq/python3） |
| JDK / Maven | Oracle JDK 17.0.1 / Maven 3.9.16（中文 locale，platform encoding GBK，已用 `-Dfile.encoding=UTF-8` 规避乱码） |
| Node / npm | 20.17.0 / 10.8.2 |
| 数据库 | 默认 H2 文件库（测试用 H2 内存 `create-drop`）；**未验证 MySQL 8.4** |
| LLM | 关闭（`.env.local` 里的真实 key 未使用，避免外部计费）→ 走规则引擎 + 本地哈希 embedding |
| 端口 | 后端基线门禁 8080 无占用；运行期实测用 **18080** + 独立数据目录 `data-verify/` |

### 1.2 命令清单（全部可复现）

```bash
# 后端：编译 + 204 用例 + 打包
cd travel-agent-server && mvn -B verify

# 后端：仅为 P0 新增的并发探针（不污染主干测试集）
cd travel-agent-server && mvn -q -Dtest=ConcurrentConfirmProbeTest test

# 前端：三门禁
cd travel-agent-web && npm run typecheck && npm test && npm run build

# 前端：依赖审计
cd travel-agent-web && npm audit --omit=dev --json

# 运行期：独立端口/数据目录启动（关键参数见 2.5）
cd travel-agent-server && JWT_SECRET=<base64-48B> java -Dfile.encoding=UTF-8 \
  -jar target/travel-agent-server-0.1.0.jar --server.port=18080 \
  --spring.datasource.url="jdbc:h2:file:./data-verify/verify;MODE=MySQL;AUTO_SERVER=TRUE" \
  --travel.llm.enabled=false
```

---

## 2. 基线测试结果

### 2.1 后端：`mvn -B verify` 【实测】

| 项 | 结果 |
|---|---|
| 退出码 / 构建 | **0 / BUILD SUCCESS** |
| 用例 | 基线 **Tests run: 204, Failures: 0, Errors: 0, Skipped: 0**；加入本轮探针后复跑为 **207 run / 0 failed / 1 skipped（`@Disabled`）/ BUILD SUCCESS** |
| 测试类 | 57 个（`src/test/java`），约 5.4k 行测试代码 |
| 耗时 | 55.5 s（含 Spring 上下文多次启动；最慢 `AccessTicketTest` 14.3 s，其次 `ChannelWebhookTest` 2.9 s） |
| 产物 | `travel-agent-server-0.1.0.jar`（93.8 MB，fat jar） |
| 启动耗时 | 14.7 s（jar + H2 文件库，含建表/目录与 RAG 知识库 bootstrap） |
| 编译告警 | `deprecation` 0 处；`@MockBean` 0 处；`@SuppressWarnings("unchecked")` 8 处人为压制 |

> 基线包含工作区未提交的 `AmapDataServiceTest`，因此 204 是"当前工作区"的数字；README 写的 199 已过时。

### 2.2 前端：三门禁【实测，两次独立复跑结论一致】

| 步骤 | 退出码 | 耗时 | 结果 |
|---|---|---|---|
| `npm run typecheck` | 0 | 9.8–28 s | 无任何 TS 错误 |
| `npm test`（vitest 4.1） | 0 | 1.2–4.6 s | **8 files / 55 tests passed**，0 failed / 0 skipped |
| `npm run build` | 0 | vite 24–40 s | 3765 modules；`dist` 43 文件 / 3.66 MiB |
| `npm run build` 告警 | — | — | 3 个 chunk > 800 kB：maps 1.22 MB、echarts 1.11 MB、antd 1.07 MB |
| PWA | — | — | `precache 41 entries (3727.90 KiB)` + `skipWaiting()` |

### 2.3 依赖安全【实测】

- 生产依赖（`--omit=dev`）：**1 个 high** —— `follow-redirects@1.16.1`（axios 传递依赖，GHSA-mrwp-f3q8-3g9p，CWE-201）；`fixAvailable: true`，`npm audit fix`（非 `--force`）升 1.16.3 即可，无 breaking。浏览器构建一般不走该代码路径，实际风险低。
- 全量（含 dev）：7 条（2 low / 4 moderate / 1 high），其余 6 条在 `vite-plugin-pwa → workbox-build` 构建链，**不建议 `--force` 降级**。
- 版本组合：React 18 + react-router-dom 7 + vitest 4 + happy-dom 21 在 Node 20.17 下均可运行，无"跑不动"组合。
- Java 侧：Spring Boot 3.5.7、jjwt 0.12.6、pdfbox 3.0.3、flying-saucer 9.4.0 均为较新版本；**离线无法确认 CVE**，也未接入任何漏洞扫描。

### 2.4 E2E（Playwright）【实测：可用但不可重复】

| 轮次 | 结果 | 证据 |
|---|---|---|
| 首跑（干净限流窗口） | **4 passed / 14.8 s** | 登录、建行程、行程页/路书、PWA 资源、结构化规划表单两条闭环 |
| 5 分钟内二次运行 | **2 passed / 2 failed** | trace 捕获 `POST /api/auth/login` → **429「操作过于频繁，请 92 秒后再试」**；`GET /api/auth/me` → **429「请 99 秒后再试」** |

- 根因不是"中文文案选择器"（旧结论），而是**限流桶污染**：`/api/auth/me` 与 `/api/auth/login` 共用 `10/300s` 的 IP 桶，一轮 e2e 约产生 8 次 auth 请求，两轮即 16 > 10。
- 级联路径：`/me` 429 → 前端把任何异常都当"未登录"（`App.tsx:94-101`）→ `Protected` 重定向 `/login` → 页面停在登录页 → 断言失败。**这同时是一个产品可用性缺陷**（见 P1-8）。
- 结论：CI 每次跑一次能过，但**本地/重试场景确定性失败**，门禁可信度打折。

### 2.5 运行期核心闭环【实测】

启动 18080 + 独立 H2 库，`LLM_ENABLED=false`（规则引擎路径），7 步全绿：

| 步骤 | HTTP | 关键结果 |
|---|---|---|
| 登录 demo/demo123 | 200 | httpOnly Cookie，`/api/auth/me` 正常 |
| 建行程（上海三日游 / 3000） | 200 | tripId=2，phase=UPCOMING |
| `POST /api/agent/chat` 自然语言规划 | 200 | `intent=PLAN_TRIP`、`status=NEED_CONFIRMATION`、15 项变更、`budget.total=1626` |
| `POST /api/agent/confirm` | 200 | `status=COMPLETED`、tripId=2 |
| `GET /api/trips/2/itinerary` | 200 | 15 项，day1/2/3 各 5 项 |
| `GET /api/trips/2/budget` | 200 | 3000 / 1626 / 0，variance 自洽 |
| `/actuator/health` | 200 | UP（metrics 与 `/api/system/status` 匿名 401，符合预期） |

### 2.6 并发探针：P0 复现【实测】

新增 `travel-agent-server/src/test/java/com/travel/agent/ConcurrentConfirmProbeTest.java`（2 线程 + `CountDownLatch`，每轮新用户 + 新 plan，两线程同时 `AgentEngine.confirm(planId, userId)`）：

| 场景 | 轮次 | 结果 |
|---|---|---|
| **新建行程**（planRevision=null，跳过指纹） | 5/5 复现 | **2 个行程 / 30 条 itinerary（2×15）/ 2 条 operation_history** |
| **改已有行程**（走 revision 校验） | 5/5 复现 | 同一批 15 条 ADD 落两遍：**items 0 → 30 / history=2** |
| 附加失败模式 | 3/5（新建）、1/5（改） | 失败方在 commit **成功之后** `remove` 抛 `ObjectOptimisticLockingException(PendingPlanRecord)` → HTTP **500，但数据已双写** |

> 该探针的严格断言用例已写好并 `@Disabled("arch P0#1 已复现，修复后启用")`，修复后去掉注解即成为回归守护；两个记录型探针不断言，保证主干测试集不被已知缺陷染红。

---

## 3. "全绿"的含金量：测试有效性评估

### 3.1 后端：质量在线，盲区结构性

**优点**：204 个用例绝大多数是行为断言（如 `ApiErrorHandlingTest` 同时断言 HTTP status + body `code` + 不泄露内部异常；`TripOwnershipTest` 断言越权 404；`PendingPlanRevisionTest` 断言旧方案被拒且不落库）。**不是"只断言 200"的项目**。

**盲区**（本次实测暴露出其中 3 个是真实缺陷的藏身处）：

| 盲区 | 后果（本次实证） |
|---|---|
| **并发维度 = 0**（全仓无 `ExecutorService`/`Thread`/`CountDownLatch`） | P0 双落库连续存在，测试全绿 |
| **SSE 协议 = 0**（`PlanStreamApiTest` 只断言 `Content-Type`，不断言事件名/顺序） | 协议回归无保护 |
| **Flyway/MySQL = 0**（`application-test.yml` 显式 `flyway.enabled=false`） | 迁移脚本语法与约束完整性从未在 CI 跑过 |
| **故障注入 = 0**（Redis/S3/LLM 断连多为单测 mock "异常被吞"路径） | 降级链路无端到端保障 |
| 跨认证域越权（`tg_/wx_` 渠道账号 vs 网页账号） | 渠道入口的归属边界无测试 |

### 3.2 前端：55 条全有效，但"测的是纯逻辑"

- 8 个测试文件 / 55 用例 / 716 行，**没有注水用例**，断言质量中上（`markerElement.test.ts` 甚至覆盖了 XSS 文本渲染）。
- 但覆盖对象是 **4 个纯函数模块 + 1 个 store + 3 个 api 客户端**；**16 个 pages、23 个 components 中组件渲染测试为 0**，`@testing-library/react` 已安装但全仓引用数为 **0**。
- 最危险的盲区恰好被 mock 绕开：`agentStore.test.ts` 整模块 mock `../api/agent`，真实 SSE 生命周期、POST 分帧（`agent.ts:102-202`，约 100 行）零覆盖；`confirm`/`cancel`、`PlanDiff` 确认流程、`ErrorBoundary`、`tripStore` 竞态均 0。

### 3.3 覆盖率数据缺失

前后端均**未接入覆盖率工具**（无 JaCoCo、无 `@vitest/coverage-v8`），所以"测试够不够"只能靠人工判断——这本身是工程基建缺口（见批次 C）。

---

## 4. 问题清单

> 每条包含：现象 → 证据 → 影响 → 修复方向 → 验收标准。P0 建议本迭代内修完。

### P0-1 并发确认同一方案会重复落库【实测，已复现 5/5】

**现象**：`AgentEngine.confirm` 是 `peek` → 校验 revision → `commit` → `remove` 四步，无锁、无 `@Version`、无唯一约束兜底。两个并发确认都能走完提交。

**证据**：
- `agent/AgentEngine.java:527-554`（`peek:529` → `commit:551` → `remove:552`）；`PendingPlanRecord.java` 全文无 `@Version`；`PendingPlanRepository` 只有 `JpaRepository` + `deleteByCreatedAtBefore`。
- 新建方案 `planRevision = null`（`AgentEngine.java:197-201`），而指纹校验带 `!= null` 前置（`:541`）→ 新行程连兜底都没有。
- 探针实测：5/5 轮双落库（数据见 2.6）。

**影响**：用户双击/双标签页/网关重试 → 多一个行程或多一批重复条目、操作历史出现两条同源记录（撤销语义错乱）、预算统计失真。更糟的是失败方返回 500，用户以为失败了，实际已多写一份。

**修复方向**（推荐"条件更新抢所有权 + 幂等键"）：
```java
// PendingPlanRepository：条件更新即抢锁，失败方拿不到所有权
@Modifying
@Query(value = "UPDATE pending_plan SET status='CONFIRMING' " +
               "WHERE plan_id=:planId AND user_id=:userId AND status='PENDING'", nativeQuery = true)
int claim(@Param("planId") String planId, @Param("userId") Long userId);

// AgentEngine.confirm
PendingPlan p = pendingPlans.peek(planId, userId);
if (p == null) return failure("待确认方案不存在或已过期");
if (pendingPlans.claim(planId, userId) != 1) return failure("该方案正在处理中或已完成");
try { return executorAgent.commit(p.plan(), userId); }
catch (RuntimeException e) { pendingPlans.release(planId); throw e; }  // 允许重试
finally { pendingPlans.remove(planId); }
```
另加两道纵深防御：① `pending_plan` 实体加 `@Version`；② `operation_history` 增加 `plan_id` 列 + `unique(trip_id, plan_id)`，让重复提交在 DB 层直接失败（对新建行程尤其有效）。`cancel`（`:556-566`）同源 TOCTOU 一并解决。

**验收**：启用 `ConcurrentConfirmProbeTest#concurrentConfirmCreatesExactlyOneTrip`（去掉 `@Disabled`）后，5/5 轮均为 1 个行程 / 15 条条目 / 1 条历史，另一线程返回明确的业务失败而非 500。

---

### P0-2 `?ticket=` 等价完整登录，且可自我续期【实测】

**现象**：`JwtAuthenticationFilter` 对**任意**请求接受 `?ticket=`，校验签名后直接写入 `ROLE_USER`，**不校验 path / HTTP method / 资源**。

**证据**：`security/JwtAuthenticationFilter.java:56-59`；`AccessTicketService.validate` 只验签名/scope/过期（`:51-94`）；`SecurityConfig.java:66` 只分 `permitAll` 与 `anyRequest().authenticated()`。
运行期实测（**完全不带 Cookie/Authorization，只用 `?ticket=`**）：
- `GET /api/auth/me` 200（demo）、`GET /api/trips` 200、`GET /api/expenses/overview` 200
- **`POST /api/memos` 200（写入成功）**、**`POST /api/trips` 200（写入成功）**
- 无凭证对照 401；服务重启后同一票据仍 200（无状态，TTL 600s）
- **新发现（放大危害）**：票据既然对任意请求有效，就能调 `POST /api/access-ticket` 再签一张新票据（`AccessTicketController.java:20-23` 只要求 `Authentication`）→ **泄漏一张票 = 长期账号接管，不是 10 分钟**。

**影响**：票据按设计会进 URL（`<img src>`、SSE），从而落入网关/代理日志、Referer、截图、缺陷报告。任何拿到一条日志的人可在无密码、无 2FA 情况下接管账号。实测证明可读可写、可访问无关业务路径。

**修复方向**（三处一起做，成本约半天）：
```java
// 1) 用途落地：只有 GET + 白名单 path 才接受票据
String ticket = request.getParameter("ticket");
if (ticket == null || !"GET".equals(request.getMethod())) return null;
String uri = request.getRequestURI();
if (!uri.matches("^/api/photos/\\d+/raw$") && !"/api/agent/stream".equals(uri)) return null;
return accessTicketService.validate(ticket);
```
2) 票据 payload 增加 `resourceId`（如 photoId）与 `jti`，读取时比对；`jti` 一次性消费（服务端记录）。3) TTL 降到 60s，响应加 `Referrer-Policy: no-referrer`。按此改后，"非 GET 一律拒绝"同时天然堵死自续期。

**验收**：`?ticket=` 时 `POST /api/memos`、`POST /api/access-ticket`、`GET /api/expenses/overview` 全部 401/403；`GET /api/photos/{id}/raw` 与 `GET /api/agent/stream` 仍 200；断言写入 `AccessTicketTest`。

---

### P0-3 个人知识库跨用户读取【实测】

**现象**：`knowledge/add` 落库无 `userId`；`knowledge/search` 用 `city=null` 检索，`matchesCity` 在 `queryCity==null` 时放行全部文档。

**证据**：`controller/LearningController.java:98-125`（无 userId）、`:132-141`（`ragService.search(query, null, 5)`）；`rag/InMemoryVectorStore.java:74-84`（`queryCity==null → true`）；`entity/RagDocument.java` 无 userId。
运行期实测：A 上传含 `ZQX-MARKER-7731` 的私有文本 → **B（无关账号）搜索该词直接返回 A 的片段（score 0.691）**；换语义 query（"护照和银行卡的个人隐私信息"）仍返回 A 的两段文本（0.352/0.345）。

**影响**：注册匿名开放（`/api/auth/register` permitAll），等价于准匿名用户可持续抽取他人私有文本；同一管道还会把他人文本注入 Agent 的 RAG 上下文 → **跨用户存储型 Prompt Injection**。

**修复方向**：`RagDocument`/`RagChunk` 加 `userId`（个人知识=当前用户、公共知识=null）；`VectorStore.search` 增加 `userId IS NULL OR userId = :uid` 过滤；补 `V5__rag_user_scope.sql` 迁移并重建索引；`RagService.answer` 同样过滤。

**验收**：新增测试"B 搜 A 的标记词返回空"；公共知识（城市/攻略）仍可被所有人检索。

---

### P0-4 `commit` 快照无锁 + 撤销 `replaceAll` 全删全插【静态】

**现象**：`ExecutorAgent.commit` 的 before/after 两次 `listEntities` 无行锁、实体无 `@Version`；撤销按旧快照 `deleteByTripId` + `saveAll` 重建整表。

**证据**：`agent/ExecutorAgent.java:141-145`（快照序列）；`service/OperationHistoryService.java:81,97`（`findFirst...` 无锁 → `undoLatest`）；`service/ItineraryService.java:135-145`（`replaceAll` = 全删全插）。
（未做运行时探针：需要生产钩子才能确定性复现；但 P0-1 已证明"同源无锁写入"确实并发落库，且 `replaceAll` 的逻辑决定了"撤销时当前行程 ≠ 快照"就必然抹掉快照之后的写入。）

**影响**：并发提交 + 撤销 → 静默数据丢失，且因为撤销显式"还原"，用户很难归因。

**修复方向**：① `Trip` 加 `@Version`，提交/撤销都带版本（乐观锁冲突返回 409 让用户刷新）；② 或按 trip 行 `SELECT … FOR UPDATE` 串行化；③ `replaceAll` 改为按 id diff（增/删/改），不整表重建。

**验收**：新增 `ConcurrentCommitHistoryTest`：同 trip 两次提交各 ADD 一条 → 分别撤销后仍能看到另一条。

---

### P1-1 `X-Forwarded-For` 可无限绕过登录限流【实测，默认配置即成立】

**证据**：`security/RateLimitFilter.java:97-103` 无条件取 XFF 第一段；默认 `travel.rate-limit.enabled=true`（`application.yml:85`），`/api/auth/**` 为 `10/300s`。
实测：固定 XFF 第 **11** 次 429（`Retry-After: 299`）；**每次换 XFF 连续 25 次 → 0 个 429**。无账号锁定（`AuthService.java:36-43` 只做 BCrypt 比对），限流是唯一在线爆破防线。
另需注意：`travel-agent-web/nginx.conf` 用 `proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;`——该指令把**客户端伪造的 XFF 原样保留并追加** `$remote_addr`，而后端取 `split(",")[0]`，所以**即使上了 nginx，伪造依然生效**。

**修复**：后端只信受信代理（`remoteAddr ∈ trustedProxies` 才读 XFF，且取**最后一跳**而非第一段）；nginx 改 `proxy_set_header X-Forwarded-For $remote_addr;`。另建议登录加"按用户名失败计数 + 锁定/退避"，与 IP 桶互补。

**验收**：换 XFF 25 次仍在第 11 次左右 429；固定 XFF 正常 429。

---

### P1-2 微信公众号 POST 无签名校验【实测，条件成立】

**证据**：`channel/WeChatOfficialAccountController.java:84-92` 的 POST 只判断 token 是否配置，**完全不校验** `signature/timestamp/nonce`；GET 握手（`:65-81`）有校验。`/api/channels/**` 为 `permitAll`。
实测：默认 token 空 → 403（渠道关闭，**默认不可利用**）；加 `--travel.channels.wechat.token=testtoken` 重启后，**签名错误或完全不带 signature 的伪造 XML 均 200**，匿名建号 `wx_attacker_openid_7731`（user id=33）并写入 `pending_plan`。

**影响**：一旦按 README 启用公众号，匿名者可批量建号、驱动 AgentEngine（消耗 LLM/算力）、污染数据；若已知他人 openid 还可伪造其消息。

**修复**：POST 与 GET 一样校验 `sha1(sort(token,timestamp,nonce))`，用 `MessageDigest.isEqual` 恒时比较，并校验 `timestamp` 新鲜度（±5 分钟）防重放；渠道级单独限流。

**验收**：新增 `ChannelWeChatTest#postWithoutSignatureIsRejected`（伪造 XML → 403）。

---

### P1-3 默认配置不安全（叠加后可达"匿名读写全库"）【实测】

| 默认项 | 证据 | 实测后果 |
|---|---|---|
| H2 console 开 + `permitAll` + `sa` 空密码 | `application.yml:36-39`、`SecurityConfig.java:62-64` | **匿名可登录 `/h2-console/` 并执行 SQL**，实测读出 `users.password` BCrypt 前缀、枚举 19 张表 |
| demo/demo123 默认创建 | `application.yml:61-63`、`DataInitializer` | 公网部署即公开弱口令账号 |
| CSRF 关闭 + Cookie 凭证 | `SecurityConfig.java:48`、`AuthCookieService.java:34-43` | 同站跨源可 CSRF（依赖部署形态） |
| 带副作用的 GET | `AgentController.java:50-68` | `<img src=/api/agent/stream?message=...>` 可消耗受害者额度 |
| `cookie-secure: false` | `application.yml:144` | 明文 HTTP 下登录态可被嗅探 |

**重要缓解**：`docker-compose.yml` 走 `mysql` profile，而 `application-mysql.yml:8-11` 把 `h2.console.enabled` 显式设为 **false**，且 compose 里 `DEMO_ACCOUNT=false`、`JWT_SECRET` 必填——**Docker 生产路径已经修掉大部分**。风险集中在"不用 compose、直接 `java -jar` 默认配置上线"的路径。
**H2 远端限制**：H2 默认 `webAllowOthers=false` 只允许回环访问；但 Tomcat 监听 `0.0.0.0`，**若同机 nginx 反代 `/h2-console/`，源地址变成 127.0.0.1，这层保护即失效**。

**修复**：H2 console 与 demo 账号移入 `dev` profile 默认关闭；非 dev profile 启动期 fail-fast（`JWT_SECRET` 非空、`DEMO_ACCOUNT=false`、`COOKIE_SECURE=true`、h2 console off）；`/api/agent/stream` GET 版下线或强制只读。

---

### P1-4 Schema 双轨：Flyway 与 `ddl-auto:update` 并存，迁移脚本不自足【静态】

**证据**：`application.yml:27`（`ddl-auto: update`）+ `:29-35`（Flyway 启用、baseline v1）；`db/migration/V2-V4` 全是 `CREATE TABLE IF NOT EXISTS`，**刻意不建索引/唯一约束**（脚本注释自认"交给 ddl-auto 补齐"）；`application-test.yml:11-14` 显式 `flyway.enabled: false`。
**具体隐患**：`LlmQuotaUsageRepository.increment` 的 `INSERT … ON DUPLICATE KEY UPDATE` 依赖 `uk_llm_quota_user_date`，而该唯一约束**只存在于实体注解**（`LlmQuotaUsage.java:12-14`），`V3__llm_quota.sql` 没有。若任何人按"Flyway-only"（`ddl-auto=validate`）建库，配额自增会**每次插入新行**，计数永久失真且永不拦截。

**影响**：迁移脚本语法/顺序在 CI 完全没跑过（V5 写错 = 线上启动失败）；两套 schema 来源长期漂移；`sql/init.sql` 已与实体脱节。

**修复**：补 `V1__baseline.sql`（实体全量 DDL，含索引与唯一约束）→ `ddl-auto` 改 `validate` → CI 加 Testcontainers MySQL 跑 Flyway + validate 的测试 job。

---

### P1-5 LLM 配额"先读后增"，判断非原子【静态】

**证据**：`service/LlmQuotaService.java:41-48`：读 `used` → 比较 `used >= limit` → `increment()`；方法级 `@Transactional` 挡不住读-判-写交叉。计数本身原子（`ON DUPLICATE KEY UPDATE`），**判断不原子**。
**影响**：N 个并发请求同读 `used = limit-1` 全部放行 → 当日实际调用可达 N 倍超发（配额控成本，超发即真金白银）。顺序测试永远发现不了。
**修复**：条件自增 `UPDATE … SET used_count = used_count + 1 WHERE user_name=? AND usage_date=? AND used_count < :limit`，`rowcount==1` 才放行。
**验收**：`LlmQuotaConcurrencyTest`（10 线程，limit=1）→ 恰好 1 个 true，`usedToday==1`。

---

### P1-6 地图接口复用 Agent 线程池 + 超时后 worker 仍写共享列表【静态】

**证据**：`service/TripMapService.java:66-78`：`CompletableFuture.runAsync(..., agentTaskExecutor)` 与 SSE 同池（`AsyncConfig` core4/max16/queue100），`allOf().get(8s)` 超时只 `log.warn` **不 cancel**，worker 仍在 `points.add(...)`；主线程返回同一个 `ArrayList` 并在 `:81` 排序。
**影响**：① 超时返回后与 worker 并发 → 元素数不稳定/CME；② 池饱和时 `/api/trips/map` 抛 `RejectedExecutionException` 直接 500（`AgentController` 对 SSE 有捕获，这里没有）；③ 地图与 SSE 互相拖慢（表现为偶发 8 秒白屏）。
**修复**：独立小池或顺序执行；超时 `future.cancel(true)`；结果收集进 `ConcurrentLinkedQueue`，排序在取消之后；返回不可变 List。

---

### P1-7 删除行程在事务内先删外部文件【静态】

**证据**：`service/TripService.java:130-141` `@Transactional delete()` 先 `photoStorage.deleteQuietly(...)`（S3/local），后删除 DB 行；文件系统/S3 不参与 JDBC 事务，`deleteQuietly` 还吞异常。
**影响**：后续 DB 删除失败并回滚 → **行还在、照片永久裂图**（不可逆）；反向也留孤儿文件。
**修复**：DB 提交后再删文件（`TransactionSynchronization.afterCommit` 或领域事件 + 补偿队列）；或软删除 + 后台清理。

---

### P1-8 `/auth/me` 429 被当作"未登录"，用户被静默踢回登录页【实测】

**证据**：`travel-agent-web/src/App.tsx:94-101` 对 `/auth/me` 的**所有**异常 `.catch(() => {})`，`Protected`（`:28-43`）见 `user null` 即跳 `/login`；`client.ts:32-44` 也没区分 429 与 401。
实测：e2e 二次运行的失败 trace 正是 `/me` 429 → 停在登录页；手动把 auth 配额调小后，普通用户 5 分钟内多刷新几次就会被踢出。
**修复**：仅 401 清登录态；429/5xx/网络错误保留登录态 + 提示重试（指数退避）；后端把 `/auth/me` 移出 auth 限流桶（给会话恢复单独配额）。
**验收**：auth 配额=3 时连续刷新 10 次，停留原页并提示"稍后重试"，不跳 `/login`。

---

### P2 清单（短条目）

| # | 问题 | 证据 | 建议 |
|---|---|---|---|
| P2-1 | 分页是**内存切片**、聚合是**全量加载** | `util/Paging.java:12-14` 注释自认待办；`ExpenseService` 每次对话 `totalOf` 全量求和 | 改 `Pageable` + `SUM/GROUP BY` 聚合 |
| P2-2 | 未知路径/尾斜杠返回 **500** 并打 ERROR 堆栈 | 实测（仅已登录）：`NoResourceFoundException` 被兜底成 500 | `GlobalExceptionHandler` 单独处理 `NoResourceFoundException` → 404 |
| P2-3 | SSE 客户端断连不取消；前端"停止"按钮是死的；POST 分帧只认 `\n\n`；错误可能重复上报 | `AgentController.java:88` 无 `onCompletion/onTimeout`；`ChatPanel:123-128` 的 icon 点击仍是 `doSend` 且 `disabled` 含 streaming；`agent.ts:184` 只 `indexOf('\n\n')`；`:172-177` + `:190-191` 双 onError | 注册生命周期回调中断引擎；store 暴露 `stop()`；分帧改 `/\r?\n\r?\n/`；`finished` 语义覆盖 error |
| P2-4 | 首屏/安装体积大 + PWA 策略风险 | maps 1.22MB、echarts 1.11MB、antd 1.07MB；`Travel` 静态 import 把 maps 拖进默认 tab；echarts 全量 import 进 Trip 静态闭包；precache 3.7MB + `skipWaiting` | Travel tabs lazy；echarts 按需注册；`workbox.globPatterns` 收敛；`controllerchange` 后一次性 reload |
| P2-5 | 依赖治理缺失 | 1 个 prod high；无 Dependabot/Renovate；CI 无 `npm audit` | `npm audit fix`（非 force）+ Dependabot + CI 增加审计步骤 |
| P2-6 | 文档漂移 | 见 §6 | 按清单修正 |
| P2-7 | `cancel` 与 `confirm` TOCTOU | `AgentEngine.java:556-566` 同为 peek→remove | 与 P0-1 一并原子化 |
| P2-8 | 撤销并发无锁 | `OperationHistoryService:81` `findFirst` 无锁 | 与 P0-4 一并处理 |

---

## 5. 改进建议（分批、可验收、含成本）

### 批次 A：安全止血（本周，约 1–1.5 人日）

| # | 事项 | 验收标准 | 成本 |
|---|---|---|---|
| A1 | 票据用途落地（GET + path 白名单 + jti + TTL 60s） | 票据无法 POST/无法访问无关路径/无法自续期，`AccessTicketTest` 断言 | 0.5d |
| A2 | 限流 IP 只信受信代理 + nginx `$remote_addr` | 换 XFF 25 次仍 429 | 0.5d |
| A3 | 微信 POST 补验签 + 时间戳新鲜度 | 伪造 XML → 403 | 0.25d |
| A4 | 默认配置改 dev-only + 非 dev 启动 fail-fast | 默认 profile 启动时 H2 console 404、demo 账号不存在；缺 `JWT_SECRET`/`COOKIE_SECURE` 直接启动失败 | 0.5d |
| A5 | 依赖：`npm audit fix` 升级 follow-redirects | `npm audit --omit=dev` 归零 | 0.1d |

### 批次 B：一致性与可重复测试（本迭代，约 3–4 人日）

| # | 事项 | 验收标准 | 成本 |
|---|---|---|---|
| B1 | 确认原子消费 + `@Version` + `operation_history(trip_id, plan_id)` 唯一键 | 探针严格用例启用后 5/5 轮均 1 行程/15 条目/1 历史 | 1d |
| B2 | `Trip` 乐观锁；`replaceAll` 改 diff；撤销冲突返回 409 | `ConcurrentCommitHistoryTest` 通过 | 1d |
| B3 | RAG 加 `userId` 隔离 + V5 迁移 | 跨用户检索测试返回空 | 0.5d |
| B4 | 配额原子判断（条件 UPDATE） | `LlmQuotaConcurrencyTest` 10 线程仅 1 个放行 | 0.25d |
| B5 | 地图接口独立池/顺序执行 + 取消 + 不可变返回 | 池满不 500；超时不丢点 | 0.5d |
| B6 | 删除行程外部副作用移到 `afterCommit` | 回滚后照片仍在 | 0.25d |
| B7 | e2e 稳定性：`RATE_LIMIT_ENABLED=false`（e2e 后端）+ `globalSetup` 单次登录 `storageState` + `serviceWorkers:'block'` + `workers:1` + `data-testid` | 5 分钟内连跑 3 轮全绿，auth 请求/轮 ≤2 | 1d |
| B8 | 前端 429 ≠ 登出 | 配额=3 时连续刷新不跳登录页 | 0.25d |

### 批次 C：工程基建 / 质量门禁（2–3 人日，可与 B 并行）

| # | 事项 | 建议配置 |
|---|---|---|
| C1 | **覆盖率** | JaCoCo + `jacoco:check`（先设 line ≥ 55% 防倒退，再逐步提）；前端 `@vitest/coverage-v8` + 阈值 |
| C2 | **Java 静态分析** | SpotBugs（含 `find-sec-bugs`）+ Checkstyle（或 Error Prone）；先只告警不阻断，清完存量再阻断 |
| C3 | **前端 lint** | ESLint（`@typescript-eslint` + `react-hooks` + `jsx-a11y`）+ Prettier；`npm run lint` 进 CI。**当前全仓无 ESLint**，`Planner/index.tsx:76` 还留着无效 `eslint-disable` |
| C4 | **依赖治理** | Dependabot（maven + npm，weekly）+ CI 增加 `npm audit --omit=dev --audit-level=high` 与 `mvn dependency-check`（可先 report-only） |
| C5 | **数据层测试** | Testcontainers MySQL 跑 Flyway + `ddl-auto=validate`；顺带补 `sql/init.sql` 一致性校验 |
| C6 | **协议测试** | `PlanStreamApiTest` 增加事件序列断言（`start → intent → … → confirmation → complete`，含 `planId`） |
| C7 | **并发测试** | 把 `ConcurrentConfirmProbeTest` 的严格用例、`ConcurrentCommitHistoryTest`、`LlmQuotaConcurrencyTest` 纳入常规套件 |

### 批次 D：架构演进（下一阶段，约 1–2 人迭代）

| # | 事项 | 说明 |
|---|---|---|
| D1 | **补上 Agent 工具调用循环**（最高战略价值） | 现状：`IntentAgent.java:65-71` 只允许模型调用 `mcp_*` 工具，8 个内置工具模型一个都调不到；`PlannerAgent` 用 Java 语句硬编码 `tools.invoke("search_hotel"...)` 序列（`:144,264,275,566,587,610,624,635`）；`AgentPlan` 只有"条目变更列表"，没有"待执行动作节点"。建议引入 `ToolCall` 节点 + Plan-Execute/ReAct 循环（`maxSteps`、每步 SSE 事件、复用已有 `SafetyInterceptor` 三级风险与 commit 确认），把现有硬编码流水线改造成"模型提议 → 规则校验 → 工具执行 → 观察 → 继续"的真实循环 |
| D2 | **Schema 单一权威** | 见 P1-4：V1 baseline → `validate` → 迁移测试进 CI |
| D3 | **外部副作用事务模型** | 统一"DB 提交后再做文件/对象存储/通知"，配补偿队列 |
| D4 | **可观测性** | 已有 `TraceIdFilter` 与 metrics；建议给 Agent 各阶段加 Micrometer Timer（intent/plan/tool/commit 分位耗时）、LLM 配额与超发计数、SSE 断连计数 |
| D5 | **生产配置基线** | `server.shutdown: graceful`、Tomcat 线程池上限、nginx gzip（当前未开，3.7MB 静态资源裸传）、容器资源 limits |

### 批次 E：性能与体验（2–3 人日）

| # | 事项 | 预期收益 |
|---|---|---|
| E1 | 分页/聚合下推数据库 | 对话与列表延迟不再随数据线性增长 |
| E2 | 前端体积 | Travel tabs lazy（-1.2MB）、echarts 按需（-0.7MB）、PWA precache < 800KB |
| E3 | 可访问性 | icon-only 按钮补 `aria-label`（当前全仓仅 3 处 aria-label、0 处 alt）；`tripId=NaN` 守卫 |
| E4 | 类型债 | 非测试代码 54 处 `any`/`as any`、12 处强转，配合 C3 逐步改 `unknown` + 泛型 |
| E5 | 移动端/离线 | 弱网白屏风险（P2-4）+ 离线只保 app shell 的取舍写进文档 |

---

## 6. 文档修正清单（README 漂移）

| # | 位置 | 现文 | 事实 | 建议 |
|---|---|---|---|---|
| 1 | `README.md:456` | 「后端 199 个」 | **204**（含未提交的 `AmapDataServiceTest`） | 改为 204，并加"以 CI 输出为准" |
| 2 | `README.md:474` | 已完成列表含「**原子化提交**」 | 确认链路非原子（P0-1 已复现双写） | 去掉该断言或标注"待补并发原子化" |
| 3 | `README.md:476` | 已完成列表含「**数据库索引与分页**」 | 分页是内存切片（`Paging.java:12-14` 注释自认待办），索引虽在实体上但聚合仍全量加载 | 改为"索引已建；数据库分页待办" |
| 4 | `README.md:143` | 「存最近 **10 轮**消息」 | `HISTORY_LIMIT = 10` 是 **10 条消息**（先取 20 再截 10）≈ 5 轮（`ConversationService.java:28,43-45`） | 改"10 条消息（约 5 轮）" |
| 5 | `README.md:377` | `CORS_ALLOWED_ORIGINS` 默认 2 个来源 | `application.yml:147` 默认 **4 个**（含 preview 4173） | 同步默认值 |
| 6 | `README.md:103` | 「首次启动会自动完成：建表（JPA ddl-auto）…」 | 还并行执行 Flyway `V2–V4` 增量 | 补一句双轨说明，避免运维只按 ddl-auto 理解 schema |

> `docs/guide/architecture.md`（594 行）与实现对齐度很高，主动披露的差异抽查全部属实，可继续信任；需要修的是 README 的上述断言。

---

## 7. 本次测试的边界与未覆盖面

**已覆盖**：后端全量单测/集成测试（204）、前端三门禁与 55 个单测、依赖审计、Playwright e2e（含失败 trace 分析）、真实启动的核心闭环 7 步、票据越权/限流绕过/跨用户检索/默认配置攻击面的运行期实测、并发确认 P0 探针、10 条高严重度静态结论核验、文档漂移核对。

**未覆盖（结论不要外推到这些场景）**：

1. **真实 LLM 路径**：为避免用 `.env.local` 的真实 key 产生外部计费，运行期全程 `LLM_ENABLED=false`。结构化规划、Prompt 稳定性、LLM 超时/降级、真实 Embedding 均未测。
2. **MySQL 8.4**：全部运行在 H2（`MODE=MySQL`）。隔离级别（REPEATABLE_READ）、`ON DUPLICATE KEY`、Flyway 迁移的 MySQL 行为未实测——**P0-4 的"RR 快照缺项"机制尤其如此**。
3. **Redis 限流、S3 存储、邮件/微信/Telegram 真实渠道、Tesseract OCR**：仅静态阅读与既有单测。
4. **并发压测/容量**：只做了 2 线程的正确性探针，未做吞吐、池饱和、长时间运行（内存/连接泄漏）。
5. **移动端真机、浏览器矩阵**：仅 Chromium；Safari/iOS PWA 未测。
6. **安全渗透**：未做 XSS/CSRF 实战利用、未做认证绕过之外的漏洞挖掘；`npm audit` 之外未做 Java 依赖 CVE 扫描。

---

## 8. 附录：证据与产物

### 8.1 本地证据文件（`.review/`，已于 2026-11-16 清理时删除；下表为当时的证据清单，可重建）

| 文件 | 内容 |
|---|---|
| `.review/mvn-verify.log` / `mvn-final.log` | 后端全量门禁日志（BUILD SUCCESS / 用例数） |
| `.review/web-checks.log` | 前端 typecheck/vitest/build 基线 |
| `.review/verify-claims.md` | 10 条高严重度结论逐条核验（含当前行号与代码摘录） |
| `.review/runtime-verify.md` | 运行期实测（票据/XFF/微信/RAG/H2/500/闭环），含 curl 与响应原文 |
| `.review/frontend-verify.md` | 前端门禁、测试有效性、依赖、e2e 脆弱点、静态改进点 |
| `.review/rv/` | 运行期原始证据（Cookie jar、响应 JSON/HTML、逐次限流状态） |
| `.agent-team/` | 上一轮审计产物（本报告仅作为待核验输入，未直接采信） |

### 8.2 本轮新增/变更的文件

| 文件 | 性质 | 说明 |
|---|---|---|
| `travel-agent-server/src/test/java/com/travel/agent/ConcurrentConfirmProbeTest.java` | **新增，untracked** | P0 实证探针；2 个记录型用例 + 1 个 `@Disabled` 严格回归用例。建议保留：修复 P0 后去掉 `@Disabled` 即为回归守护（会让全量套件增加约 13s） |
| `docs/review/2026-11-14-quality-review.md` | **新增** | 本报告 |
| `travel-agent-server/data-verify/` | 临时 | 运行期实测数据目录，**已清理** |
| `travel-agent-web/dist/` | 重新生成 | 构建产物，`.gitignore` 已忽略 |

### 8.3 复现优先级建议

若只想用 10 分钟验证本报告最核心的两条结论：

```bash
# 1) P0 并发双写（约 40s）
cd travel-agent-server && mvn -q -Dtest=ConcurrentConfirmProbeTest test
#    看输出里 [CONCURRENT-CONFIRM-PROBE] SUMMARY rounds=5 duplicatedRounds=5

# 2) e2e 不可重复（约 60s，需先起后端 8080 与 build）
cd travel-agent-web && npx playwright test && npx playwright test
#    第二次预期 2 failed，test-results/ 里能看到 429 响应体
```

---

*本报告由 Lead 与 3 名独立核验员并行产出；所有【实测】结论均可按 §1.2 / §8.3 命令复现。*

---

## 9. 修复进展（2026-11-16 · 批次 A/B/C）

### 9.1 状态总览

| 编号 | 问题 | 状态 | 验证证据 |
|---|---|---|---|
| P0-1 | 并发确认重复落库 | **已修复** | 探针 `duplicatedRounds=0`（修复前 5/5）；逐轮 1 trip / 15 items / 1 history，败方 `FAILED` 而非 500 |
| P0-2 | `?ticket=` 等价完整登录 + 自续期 | **已修复** | 运行期复验：5 条禁止路径全 401（含 `POST /api/access-ticket`），2 条允许路径仍通；TTL 300s |
| P0-3 | 个人知识库跨用户读取 | **已修复** | `RagUserIsolationTest`：B 搜 A 的标记词返回空，A 自己可命中，公共知识仍共享 |
| P0-4 | commit 快照无锁 + 撤销全删全插 | **已修复（顺序语义）** | `Trip @Version` + 409 handler + `replaceAll` 按 id diff；并发强串行（行锁）仍建议后续加 |
| P1-1 | XFF 绕过登录限流 | **已修复** | 运行期：固定 XFF 第 10 次 429；**换 XFF 8/8 仍 429**（修复前 25 次 0 个 429） |
| P1-2 | 微信 POST 无验签 | **已修复** | 运行期：无签名/坏签名 POST 均 403；真实签名仍 200 |
| P1-3 | 默认配置不安全 | **已修复（主要项）** | H2 控制台默认关且 `/h2-console/` 返回 401；`SecurityStartupGuard` 在 mysql/prod 下 WARN |
| P1-4 | Schema 双轨 | **未修复（已知项）** | 仍 `ddl-auto=update`；V5–V7 沿用"脚本自足 + ddl-auto 兜底"策略 |
| P1-5 | 配额先读后增 | **已修复** | 条件自增 rowcount 判据；`LlmQuotaConcurrencyTest` 10 线程 limit=1 恰好放行 1 次 |
| P1-6 | 地图复用 Agent 池 + 竞态 | **已修复** | 独立 `map-locate-*` 池 + 超时 cancel + 并发队列 + 拒绝降级 |
| P1-7 | 删除事务内先删文件 | **已修复** | 照片删除移到 `afterCommit` |
| P1-8 | `/auth/me` 429 被当未登录 | **已修复** | 只有 401 清态；429 保留登录态 + 退避重试 + 提示（真机实测 URL 不跳转） |
| P2-5 | 依赖 high | **已修复** | `follow-redirects` 1.16.3，`npm audit --omit=dev` = 0 |
| B7（新增） | e2e 不可重复 | **已修复** | 3 轮连跑各 5 passed（5 分钟窗口内，限流未关） |

### 9.2 门禁与实测汇总

| 项 | 修复前 | 修复后 |
|---|---|---|
| 后端 `mvn -B verify` | 204 用例 | **239 用例全绿**（BUILD SUCCESS，新增 35 个：并发/隔离/配额/限流/微信/乐观锁） |
| 并发确认探针 | `duplicatedRounds=5/5` | **`duplicatedRounds=0`**（两个场景都是 0） |
| 运行期安全复验 | 票据可写、XFF 可绕、微信 200、H2 可读全库 | **20/20 PASS**（复验脚本已随清理删除；复现命令见 §8.3） |
| 前端三门禁 | 55 用例 | **62 用例全绿**，`npm audit --omit=dev` 0 漏洞 |
| e2e | 5 分钟内二次运行 2 failed | **3 轮连跑全绿**，auth 请求 8/轮 → 5/轮 |

### 9.3 修复过程中新发现并已修的问题

1. **规划表单"去掉重复项"触发错误意图**（真实产品 bug，HEAD 既有）：该文案命中后端 `IntentAgent` 的修改类关键词，
   攻略整合模式被误判为 `MODIFY_ITINERARY`，**确认弹窗永远不出现**。前端已改为"合并重复项"；
   后端关键词优先级（`tripId=null` 时不应判修改意图）建议后续修，见 §9.4。
2. **antd `Modal` 的 `data-testid` 落在零尺寸根节点**：Playwright 判为 hidden；已改标到内容容器。

### 9.4 建议的后续工作（按价值排序）

1. **`/api/auth/me` 移出 auth 限流桶**（前端已能容忍 429，但会话恢复不该消耗登录配额）。
2. **`IntentAgent` 修改类关键词在 `tripId=null` 时不优先**（消除同类误判，减少对提示词的隐式依赖）。
3. **Schema 单一权威**（P1-4）：补 `V1__baseline.sql`（含索引与唯一约束）→ `ddl-auto=validate` →
   CI 加 Testcontainers MySQL 迁移测试。当前 `V3` 仍缺 `uk_llm_quota_user_date`。
4. **提交/撤销的行锁**：`itinerary_item` 无 version，commit 与 undo 交叉仍只做到"顺序正确"，
   建议 `SELECT … FOR UPDATE` 按 trip 串行化。
5. **claim 与 release 之间进程被 kill** 会留 `CONFIRMING` 行，依赖 30 分钟 TTL 回收；
   可加"超时自动回 PENDING"的定时任务。
6. §4 的 P2 清单（分页下推、SSE 断连取消、前端体积/PWA、ESLint/覆盖率/静态分析、文档漂移修正）
   仍全部待办。