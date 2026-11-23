# 快速上手（新人 onboarding）

> 状态：当前事实 | 适用版本：0.1.0（Spring Boot 3.5.12 / Node 20） | 最后验证：2026-11-19 | 事实来源：travel-agent-server/pom.xml、travel-agent-server/run-dev.ps1、travel-agent-web/package.json、docker-compose.infra.yml

> 从零把项目跑起来：装环境 → 启动后端 → 启动前端 → 走通一条完整演示链路 → 知道遇到问题去哪里查。
> 全流程基于仓库当前 HEAD 的真实脚本与配置，命令可直接复制执行。

本文适用版本：以仓库当前 HEAD 为准（`travel-agent-server` 0.1.0 / Spring Boot 3.5.12，前端 0.1.0）。

---

## 0. 这个项目是什么

「AI 智能旅行助手」以一个 **Agent 引擎**为核心，把自然语言变成**可确认、可撤销**的结构化操作：

```
用户说一句话
  → 意图识别（IntentAgent）
  → 生成计划（PlannerAgent，操作列表，不落库；规划期检索旅行知识库并带引用）
  → 预览 Diff + 风险分级（ExecutorAgent.preview / SafetyInterceptor）
  → 用户确认（前端确认弹窗）
  → 单事务落地 + 写操作历史（ExecutorAgent.commit）
  → 可撤销（OperationHistory）
```

旅行规划是唯一主线场景（行前规划 + 行中动态调整 + 行后回顾），记账、备忘、日程、照片与知识库都归属旅行域，复用**同一套引擎、同一套账号体系、同一套确认/撤销机制**；办公、学习、健康模块已下线。

核心闭环在 [README.md](../../README.md) 第 3.3 节与本文件第 5 节都有 walkthrough。

---

## 1. 环境要求

| 依赖 | 版本要求 | 依据 | 说明 |
| --- | --- | --- | --- |
| JDK | **17+** | `travel-agent-server/pom.xml` → `<java.version>17</java.version>` | 后端编译与运行 |
| Maven | **3.8+** | README 第 2 节 | 后端构建（仓库未提供 mvnw，需自备） |
| Node.js | **20**（CI 固定） / 18+ | `.github/workflows/ci.yml` → `node-version: '20'` | 前端构建与测试 |
| npm | 随 Node 安装 | — | 前端依赖安装 |
| 数据库 | **无需安装** | `application.yml` → `jdbc:h2:file:./data/travel-agent` | 默认 H2 文件库，开箱即用 |

可选工具（不装也能跑，只是对应能力降级）：

| 工具 | 用途 | 不装的后果 |
| --- | --- | --- |
| Docker | 全栈一键部署 / 本地 MySQL + Redis | 只能走 H2 本地模式（这也是默认模式） |
| 大模型 API Key | 意图识别增强、结构化规划、文案润色、回顾总结 | 全部走规则规划器：主流程可跑，但表达与泛化弱于模型（默认体验见 [能力边界](capability-boundaries.md)） |
| 高德 Web 服务 Key | 目录外城市 POI 真实定位 | 退回「大模型生成坐标 + OSM Nominatim」兜底，较慢 |

> 仓库**不保存任何密钥**。`JWT_SECRET`、`LLM_API_KEY` 等一律通过环境变量注入，
> 本地放进 `travel-agent-server/.env.local`（已被 `.gitignore` 忽略）。

---

## 2. 启动后端（端口 8080）

### 2.1 方式 A：`run-dev.ps1`（最省事，推荐 Windows 本地开发）

```powershell
cd travel-agent-server
.\run-dev.ps1
```

脚本 [run-dev.ps1](../../travel-agent-server/run-dev.ps1) 做了三件事：

1. 逐行读取 `.env.local`（格式 `KEY=VALUE`，`#` 开头为注释），注入到当前进程环境变量，并打印 `已加载 KEY（长度 N）`——**只打印长度，不打印值**；
2. 若 `.env.local` 不存在，打印 `未找到 .env.local，将以默认配置启动（规则引擎模式）`，继续用默认配置；
3. **若 `JWT_SECRET` 为空**，用 `RandomNumberGenerator` 生成 48 字节随机值、Base64 编码后**追加写回 `.env.local`**——这样重启不会被踢下线。

最后执行 `mvn -B spring-boot:run`（加 `-Jar` 参数则改为 `java -jar target/travel-agent-server-0.1.0.jar`）。

`travel-agent-server/.env.local` 的示例内容（**密钥用占位符，不要提交真实值**）：

```properties
# 本地密钥文件（.gitignore 已忽略，不会进入版本库）
LLM_API_KEY=sk-你的key
LLM_BASE_URL=https://api.deepseek.com/v1
LLM_MODEL=deepseek-chat
JWT_SECRET=        # 留空即可，脚本首次运行会自动生成并写回
```

> ⚠️ **Windows 路径必须用正斜杠 `/`**：`.env.local` 按 properties 解析，反斜杠 `\` 会被当作转义符。

### 2.2 方式 B：手动注入环境变量

```bash
cd travel-agent-server

# 可选但强烈推荐：固定 JWT 密钥（不设置时每次启动生成一次性随机密钥，重启后需重新登录）
export JWT_SECRET="$(openssl rand -base64 48)"     # Windows PowerShell: $env:JWT_SECRET = "<base64>"

# 可选：接入真实大模型；不设置则全部走规则引擎
export LLM_API_KEY="sk-你的key"

mvn spring-boot:run
```

即使不走脚本，后端也会自动读取 `.env.local`——`application.yml` 第 8-12 行配置了：

```yaml
spring:
  config:
    import: optional:file:./.env.local[.properties]
```

优先级：**环境变量 > `.env.local` > `application.yml` 默认值**。所以 `mvn spring-boot:run` 与 `java -jar` 都能直接读到本地密钥。

> 注意 `optional:file:./.env.local` 是**相对当前工作目录**的，因此必须在 `travel-agent-server/` 目录下启动后端。

### 2.3 首次启动会自动完成的事

| 动作 | 实现位置 | 说明 |
| --- | --- | --- |
| 建表 / 增量迁移 | `spring.flyway` + `resources/db/migration*/V*__*.sql` | Flyway 是 schema 唯一权威：全新库由 `V1` 基线 + 增量建到当前 `V19`；`baseline-on-migrate: true`、`baseline-version: 1` 兼容存量库 |
| 结构校验 | `application.yml` → `spring.jpa.hibernate.ddl-auto: validate` | Hibernate 只校验实体与库结构一致，**不会自动补表/补列**；不一致直接启动失败 |
| 创建演示账号 | `config/DataInitializer#run` | `demo / demo123`（昵称「演示用户」）；`DEMO_ACCOUNT=false` 时跳过 |
| 加载旅行目录 | `service/TravelCatalogService`（读取 `resources/data/travel-catalog.json`） | 上海 / 北京 / 杭州 / 石家庄，共 4 城市 28 个景点、13 家酒店、16 家餐厅 |
| 引导 RAG 知识库 | `rag/RagBootstrap#run` | 读取 `resources/rag/knowledge.json`（10 篇文档）→ 切块 → Embedding → 写入向量库；按内容指纹增量同步 |

启动成功后日志里可以看到：

```
已创建演示账号: demo / demo123
...
Tomcat started on port 8080 (http)
```

### 2.4 健康检查

```bash
curl -s http://localhost:8080/actuator/health
# {"status":"UP"}
```

H2 控制台（默认关闭，调试时设 `H2_CONSOLE_ENABLED=true` 后重启）：<http://localhost:8080/h2-console>
（JDBC URL `jdbc:h2:file:./data/travel-agent;MODE=MySQL;AUTO_SERVER=TRUE`，用户 `sa`，密码空）

---

## 3. 启动前端（端口 5173）

```bash
cd travel-agent-web
npm install
npm run dev
```

行程地图使用高德 JS API。将 `.env.example` 复制为 `.env.local`，填写 `VITE_AMAP_KEY` 与
`VITE_AMAP_SECURITY_CODE`（高德「Web 端(JS API)」Key 及配套安全密钥），然后重启 Vite。
不要复用后端 `AMAP_KEY`，后者是另一种「Web 服务」凭据。

足迹地图默认也是高德栅格底图（MapLibre 球体保留）；如果高德瓦片不可达或想回退，设
`VITE_FOOTPRINT_BASEMAP=osm` 后重启。高德瓦片直连商用前需确认授权，详见
[前端地图说明](frontend.md)。

打开 <http://localhost:5173>，用 **demo / demo123** 登录。

dev server 的关键配置在 [vite.config.ts](../../travel-agent-web/vite.config.ts) 第 64-72 行：

```ts
server: {
  port: 5173,
  proxy: { '/api': { target: 'http://localhost:8080', changeOrigin: true } },
},
```

即前端所有 `/api/**` 请求由 Vite 反代到后端 8080，**前端不需要配置 API base URL**（axios `baseURL` 固定为 `/api`）。
`preview`（4173）同样配置了该反代，供 Playwright e2e 使用。

> 已装依赖的情况下重新拉代码后建议 `npm install` 一次；`npm run build` 前会先跑 `tsc --noEmit`。

### 前端常用脚本

| 脚本 | 命令 | 说明 |
| --- | --- | --- |
| `npm run dev` | `vite` | 开发服务器 5173 |
| `npm run typecheck` | `tsc --noEmit` | 仅类型检查 |
| `npm test` | `vitest run` | 单元测试（默认 node 环境，覆盖 store / API 客户端 / 纯逻辑） |
| `npm run build` | `tsc --noEmit && vite build` | 类型检查 + 生产构建（Terser 压缩、显式分包） |
| `npm run preview` | `vite preview` | 预览构建产物 4173 |
| `npm run e2e` | `playwright test` | Playwright 冒烟（需先 `npm run build` 且后端已启动） |

---

## 4. 后端测试

```bash
cd travel-agent-server
mvn test
```

约定（详见 [docs/DEVELOPMENT.md](../DEVELOPMENT.md) 的「测试」章）：

- 所有集成测试继承 `IntegrationTestSupport`：**真实 Spring 上下文 + MockMvc + `test` profile**（内存 H2，关闭 LLM 与高德）；
- **测试不允许访问网络、不调用真实大模型**；
- 用户名用 `uniqueUsername()` 保证用例互不干扰；
- 错误语义是测试契约：参数错误 400、未登录 401、无权限 403、资源不存在 404、数据冲突 409、兜底 500 不泄露内部信息。

## 5. 演示闭环 walkthrough（跑通即算上手）

启动后端 + 前端并登录 `demo / demo123` 后，按顺序操作：

| # | 操作 | 预期结果 |
| --- | --- | --- |
| 1 | 首页输入：「帮我安排一个上海三日游，预算3000元，酒店离地铁近一点」 | 跳转规划页，聊天面板依次展示 `intent → planning → tool_call → tool_result → rag_result → plan_update → confirmation`；`rag_result` 带 `citations` |
| 2 | 等待方案生成 | 弹出 **Plan Diff 确认弹窗**：原方案 → 新方案、预算对比、风险等级；方案文案含「知识依据」citations |
| 3 | 点「确认修改」 | 行程落库，自动跳转行程主界面（左地图 / 右 AI 助手 / 下时间轴） |
| 4 | 行程页说：「明天下雨，把户外景点换掉」 | Agent 调用 `query_weather` → **只替换仍为 `PENDING` 的室外景点**，已开始/完成/跳过的条目保持不动 → Diff 确认后地图与时间轴更新 |
| 5 | 点选时间轴上某个安排（如博物馆），再说：「这个换成类似的」 | Agent 通过 `selectedElement` 上下文准确定位目标，而不是猜 |
| 6 | 对某个行程项推进状态「开始 / 完成 / 跳过 / 重置」 | 调 `PUT /api/itinerary/{id}/status`：完成写 `startedAt/completedAt/visitedAt`，追加 `CHECK_IN` 事件，并进入撤销链；卡片显示实际开始/完成时间 |
| 7 | 点行程页「旅行回顾」（或侧栏「旅行回顾」列表） | `GET /api/trips/{id}/review`：完成率、计划/实际花费、计划地点与实际到访、`actualRoute`、照片、事件、修改历史与 `aiSummary`；足迹只把 `visitedAt` 非空的条目算作「去过」 |
| 8 | 点右上角撤销按钮 | 基于 `operation_history` 的 before/after 快照还原上一步，**打卡状态与实际时间也能还原** |
| 9 | 问：「现在花了多少钱？」 | 走预算服务回答（与「预算分析」抽屉同一份数据） |
| 10 | 问：「上海有什么适合亲子游的地方？」 | 走 RAG 检索回答；旅行知识库页可录入文档、检索、查看版本与回滚 |
| 11 | 说：「记一笔午餐 45 元」/「记一下明天办签证」/「明天下午3点开评审会」 | 分别产生记账 / 备忘 / 日程 |
| 12 | 行程页点「路书」 | 下载 PDF（行程概况 + 每日安排表 + 预算小结） |

> 未配置 `LLM_API_KEY` 时以上全部可跑通（规则引擎），仅「目录外城市」（如海口）需要模型联网生成城市数据。

### 自动化版本的同一个闭环

仓库里有两套端到端脚本：

```bash
# 1) 手工 e2e：需先启动后端（8080），脚本直连 REST API
node .e2e/e2e.mjs            # 登录 → 规划 → 确认 → 行中调整 → 撤销 → RAG 问答 → SSE 流式

# 2) Playwright 冒烟：需先 npm run build，且后端已启动
cd travel-agent-web
npm run build && npm run e2e
```

`.e2e/` 下还有若干针对性验证脚本（`verify-modules.mjs`、`verify-expense.mjs`、`verify-memo.mjs`、
`debug-sse.mjs` 等），都是「本地起后端 → node 跑一遍 → 看输出」的用法。

Playwright 用例 `travel-agent-web/e2e/smoke.spec.ts` 覆盖：未登录重定向 → API 登录注入 Cookie →
首页 → 建行程 → 行程页标题与「路书」按钮 → PDF 真实导出（断言首字节为 `%PDF` 的 `%`）→ PWA 的
`manifest.webmanifest` 与 `sw.js` 可达。CI 中由 `.github/workflows/ci.yml` 的 `e2e` job 执行。

---

## 6. 常见问题排查

| 现象 | 原因 | 处理 |
| --- | --- | --- |
| `npm` 报 `Cannot run a document in the middle of a pipeline` | PATH 里有同名残留文件遮蔽了真正的 npm（例如 `D:\npm` 是 Node 安装包里的 bash 脚本） | 临时用 `npm.cmd run dev`；彻底解决就删掉残留文件并重开终端 |
| 重启后端后所有人被登出 | 未配置固定的 `JWT_SECRET`，每次启动生成一次性随机密钥 | 写入 `.env.local`（或环境变量）。`run-dev.ps1` 首次运行会自动生成并写回 |
| 想重置本地数据 | H2 文件库里残留了旧数据 | 停后端 → 删除 `travel-agent-server/data/travel-agent.mv.db` → 重启（重新建表 + 演示账号 + 目录 + RAG 引导） |
| 启动报端口占用 8080 / 5173 | 上一个进程没退干净 | `netstat -ano \| findstr :8080` 找到 PID 后结束进程；前端端口可在 `vite.config.ts` 改 |
| Agent 回答比较「模板化」、目录外城市不支持 | 没配 `LLM_API_KEY`，整条链路走规则引擎 | 配 Key 后重启；`/actuator/metrics/llm.calls` 的 `result=skipped` 表示仍在走规则引擎 |
| 路书 PDF 导出报错（不是乱码） | 系统里找不到中文字体 | 放入 `travel-agent-server/src/main/resources/fonts/cjk.*`，或在容器/Linux 装 `fonts-noto-cjk`；服务端探测顺序 classpath → Windows → Linux/macOS |
| 地图整块空白，DOM 里有 canvas 但尺寸为 0 | 父容器高度未确定，maplibre 容器塌成 0 高 | 给地图容器**显式写 height**（不要用 `flex:1` / `absolute + inset` 让高度解析成 auto），详见 DEVELOPMENT.md「地图容器必须给确定高度」 |
| 前端刷新后误跳登录页 | 登录态靠 httpOnly Cookie + `GET /api/auth/me` 恢复 | 检查浏览器是否禁用 Cookie、`COOKIE_SECURE` 是否与协议匹配（https 才设 true） |
| `npm install` 报 arborist / `edgesOut` 错误 | 本机 npm 10.8.2 的已知 bug | 安装/更新依赖加 `--legacy-peer-deps` |
| H2 控制台生产环境暴露 | 默认 profile 放行 `/h2-console/**` | mysql profile 已关闭；生产建议从 `SecurityConfig` 放行列表移除 |

---

## 7. 我该先读哪篇文档

| 你的目标 | 读这篇 |
| --- | --- |
| 先把项目跑起来 | **本文**（getting-started） |
| 理解 Agent 引擎 / RAG / 工具层 / SSE 协议 | [architecture.md](architecture.md) |
| 对接后端接口 | [api-reference.md](api-reference.md) |
| 改数据结构、写查询、做迁移 | [data-model.md](data-model.md) |
| 改前端页面、加组件、理解 SSE 消费链路 | [frontend.md](frontend.md) |
| 上线部署、配环境变量、运维排障 | [deployment.md](deployment.md) |
| 了解产品范围、P0/P1 完成度与验收 | [../PRODUCT_DEVELOPMENT.md](../PRODUCT_DEVELOPMENT.md) |
| 开发规范、Agent/工具/RAG/数据库契约 | [../DEVELOPER_HANDBOOK.md](../DEVELOPER_HANDBOOK.md) |
| 开发约定、关键不变量、设计系统 | [../DEVELOPMENT.md](../DEVELOPMENT.md) |
| 某个行为为什么变了 / 何时改的 | [../CHANGELOG.md](../CHANGELOG.md) |
| 项目定位、功能全貌、API 速查 | [../../README.md](../../README.md) |

---

## 8. 提交前自检清单

```bash
# 后端
cd travel-agent-server && mvn -B verify

# 前端
cd travel-agent-web && npm run typecheck && npm test && npm run build
```

- 改动 schema → 新增 `db/migration/V*__*.sql`，脚本必须同时兼容 H2（MODE=MySQL）与 MySQL 8；
- 新增会改变行程的入口 → **必须同时写 `operation_history`**，否则撤销会跨过这次改动；
- 新增 URL 携带凭证的场景（`<img>` / `EventSource`）→ 复用 `api/ticket.ts` 的 10 分钟只读票据，不要塞登录 JWT；
- 新增写数据的能力 → 走 `AgentPlan` 操作项 + 确认提交，**不要**注册成 `AgentTool`（`ToolRegistry` 会拒绝写工具）。
