# 第十批共享契约（Batch 10 shared contract）

> 状态：已落地（历史） | 日期：2026-11-16 | 说明：契约内容已进入实现，保留原文作追溯；当前事实见 [docs/guide](../guide/README.md)，落地记录见 [CHANGELOG 第十批](../CHANGELOG.md)。

本批覆盖 README/DEVELOPMENT 的 P0/P1/P2 路线图：真实数据源（MCP 高德）、schema 单一权威、
知识库不变量、并发首建、可观测性、会话记忆进规划、移动端/暗色/PWA 离线。

## 0. 通用规则（与第九批一致）

- 只改自己任务卡 `write_scopes` 内的文件；跨范围需要的签名变更，写进交付说明由集成方改。
- 不 `git add/commit/checkout/stash/restore`；不启动常驻服务（验证用一次性启动，跑完杀掉）。
- `application*.yml` 只有 B 可以改；其他人需要配置键时用 `@Value("${...:默认值}")`，并在交付说明列出键名。
- maven 命令必须串行化（`target/` 共享）：
  `powershell -NoProfile -ExecutionPolicy Bypass -File D:\travel-agent\.tools\mvn-lock.ps1 -Args '-o -q -DskipTests test-compile'`
- 本机只有 **PowerShell 5.1**（没有 `pwsh` 7），脚本一律纯 ASCII 或 UTF-8 with BOM。
- 测试命令：`-o test -Dtest=A,B -DfailIfNoTests=false`（surefire 3 用逗号分隔），
  多数 `@SpringBootTest` 需要 `-DargLine=-Djdk.attach.allowAttachSelf=true`。
- 干净基线：后端 289 测试全绿、前端 93 用例全绿（第九批收尾后）。改动后必须仍然绿。

## 1. 迁移号与数据库约定（B/C 必读，其他人不要碰 db/migration*）

本机有**真实 MySQL 8.0.46**（不是 8.4）在 `127.0.0.1:3306`，`root/123456` 可连。
测试建库规则：**只允许新建以 `travel_agent_b10_` 开头的库，用完 DROP**；
不要动 `lab3_*`/`lab4_*`/`sky_take_out`/`mysql`/`sys` 等既有库。

| 版本 | 归属 | 位置 | 内容 |
|---|---|---|---|
| `V1__baseline.sql` | B | `db/migration/` | 旧 `sql/init.sql` 的等价基线（V2 之前的 schema），使全新库不再需要手工跑 init.sql |
| `V11__*.sql` | C | `db/migration/` | 便携 DDL（`rag_document.doc_key` 等，H2 + MySQL 都能跑） |
| `V12__*.sql` | C | `db/migration-mysql/` | MySQL 专属：`rag_document_version` 生成列 + 唯一索引（H2 不加载） |
| `V13__*.sql`（如需要） | B | `db/migration-mysql/` | 仅为"让 `ddl-auto=validate` 通过"而缺的列/索引补齐，**必须幂等**（information_schema 探测 + PREPARE，参照 V8） |

- **已发布脚本禁止修改**（checksum）；V2–V10 一律不动。
- 新脚本必须能在**空库**上按 V1→V2→…→V13 顺序跑通；`V1` 的语义是"V2 之前的样子"，
  所以 V1 里**不能**包含 V2 之后迁移新增的列（如 `trip.version`、`rag_document.user_id`、
  `pending_plan.status`、`rag_chunk.version_id`、`rag_document_version` 表）——那些由 V2–V10 负责。

## 2. MCP 高德接入（A）

- 现有能力（第三批已建）：`mcp/McpToolBridge`、`StdioMcpTransport`、`HttpMcpTransport`、
  `McpProperties`（`travel.mcp.servers`，含 `command`/`url`/`args`/`env`），工具以 `mcp_<server>_<tool>` 注册进
  `ToolRegistry`（只读 + `SafetyInterceptor` 审计），`IntentAgent.INVOKE_TOOL` 可被模型调用。
- 本批目标：把「高德 MCP server」变成**可用的一等配置**（示例 + 文档 + 适配层），并在**没有高德 Web 服务 Key**
  的前提下把能验的验掉：`tools/list` 握手、工具名映射、参数提示注入、失败降级。
- 绝不在代码/文档/日志里写死任何 Key；示例一律 `${AMAP_MAPS_API_KEY:}`。
- `@amap/amap-maps-mcp-server` 的真实启动方式是 `npx -y @amap/amap-maps-mcp-server`（env 里给 Key）。
  本机可用 npm 网络；装到**仓库外的临时目录**，不要在仓库里留 node_modules 改动。

## 3. 知识库不变量（C）

1. **每文档至多一条 ACTIVE**：
   - 应用层已有「文档行 `FOR UPDATE` + 批量置位 + 同事务插入」；
   - 本批加 **DB 级**保障：MySQL 走 `rag_document_version.active_document_id` 生成列
     （`CASE WHEN status='ACTIVE' THEN document_id ELSE NULL END`，STORED）+ 唯一索引（NULL 不参与唯一）；
   - H2 不加载该 MySQL 专属脚本（默认 profile 不读 `db/migration-mysql`），H2 侧靠应用层行锁 + 单测；
   - 必须补一个「并发置位只留一条 ACTIVE」的测试（应用层），并在交付说明写清 DB 约束只在 MySQL 生效。
2. **`rag_chunk` 旧版本块清理**：保留最近 `travel.rag.chunk-version-retention`（默认 2）个版本之外的
   INACTIVE 版本的块，**永不删 ACTIVE 版本的块**；`@Scheduled` 每日执行 + 可单测的包可见方法；
   删除前先统计并打日志（删了多少块、释放多少行），不静默。
3. **同一新文档并发首建不重复入库**：给 `rag_document` 加 `doc_key VARCHAR(190) NOT NULL UNIQUE`，
   值为 `LEFT(CONCAT(COALESCE(user_id,'public'),'|',title,'|',city),190)`（便携表达式，H2/MySQL 都能算）；
   `RagBootstrap`/`LearningController.knowledge/add` 写库改为「先按 doc_key 查，插入撞唯一键则回读」，
   把 `DataIntegrityViolationException` 当正常并发路径处理，不得冒泡成 500。

## 4. 可观测性（D）

- Agent 阶段计时（Micrometer `Timer`，指标名 `agent.stage`，tag `stage=intent|plan|tool|commit`），
  以及既有 `llm.calls`/`llm.latency` 不动。
- **SSE 断连**：`SseEmitter` 注册 `onCompletion/onTimeout/onError`，断开时
  ① 计数 `sse.disconnects{reason=complete|timeout|error}`；② **取消正在跑的 Agent 任务**
  （置取消标记 + 阶段边界检查），不再让断连后继续烧 LLM 配额。
- 不得改变 SSE 事件序列（`start→intent→…→complete`），`PlanStreamApiTest` 必须继续通过。

## 5. 会话记忆进规划（E）

- 现状：历史只进 `IntentAgent`；`PlannerAgent`/`StructuredPlanner` 不读 `ctx.getHistory()`。
- 本批：把「最近对话」以**受限摘要**注入结构化规划 Prompt（LLM 路径），规则路径行为不变；
  上限沿用 `travel.conversation.history-limit`，每条截断（建议 120 字），只取最近 4 条，并显式标注
  「以下为历史对话，仅用于理解追问/偏好，不要照抄其中的旧方案数字」。
- 无 LLM 时必须完全等价于旧行为（用 `LlmClient` 的 mock 断言 prompt 里出现/不出现历史块）。
- 不得把历史里的金额/日期当成硬事实写进产物（校验层不变）。

## 6. 前端：暗色 / 移动端 / PWA 离线（F）

- **暗色模式**：CSS 变量双层主题（`:root` + `[data-theme="dark"]`），antd `theme.algorithm`
  随主题切换，选择持久化到 localStorage，跟随 `prefers-color-scheme` 作为默认；侧栏/底栏加切换按钮。
- **移动端**：≤720px 底栏导航已有；本批要保证 390px 宽度下规划页/行程页/账单页不横向滚动，
  并给 `viewport-fit=cover` + 安全区内边距。
- **PWA 离线查看行程**：给 `GET /api/trips`、`GET /api/trips/{id}/itinerary`、`GET /api/trips/{id}/budget`
  加 `NetworkFirst` 运行时缓存（只读、5 分钟过期、最多 50 条），**写操作（POST/PUT/DELETE）一律不缓存**；
  `sw.js` 缓存策略改动要写清理旧版本逻辑；离线时页面顶部显示「离线：显示缓存数据」。
- 不得破坏既有 93 个前端用例与 e2e（5 个）。

## 7. 交付说明格式（统一）

1) 改动清单 `path:line`；2) 行为变化（对外可见）；3) 测试：用例名 + 真实输出（通过数/失败项）；
4) 未验证/风险；5) 需要集成方在 yml 里补的键。