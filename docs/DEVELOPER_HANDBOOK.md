# AI 智能旅行助手：开发手册

## 1. 适用范围

本文面向后端、前端、Agent、工具、RAG、测试和部署开发者。它以 Spring Boot 3 + Java 17、React 18 + TypeScript + Vite 为基础，约束后续旅行化改造的实现方式。

## 2. 开发环境与启动

| 依赖 | 要求 |
| --- | --- |
| JDK | 17+ |
| Maven | 3.8+ |
| Node.js | 20（CI 版本） |
| 数据库 | 默认 H2；生产 MySQL 8 |
| 可选 | Docker、真实 LLM Key、高德 Web 服务/JS Key |

密钥只允许通过环境变量或 `travel-agent-server/.env.local` 注入。后端 `AMAP_KEY` 是 Web 服务 Key，前端 `VITE_AMAP_KEY` 是 Web 端 JS API Key，不能混用。

```powershell
cd D:\travel-agent\travel-agent-server
.\run-dev.ps1

cd D:\travel-agent\travel-agent-web
npm install
npm run dev
```

默认后端 `8080`、前端 `5173`。健康检查：`curl http://localhost:8080/actuator/health`。

测试命令：

```powershell
cd D:\travel-agent\travel-agent-server; mvn test
cd D:\travel-agent\travel-agent-web; npm run typecheck; npm test; npm run build
```

测试默认不得访问真实网络、真实模型或生产数据。

## 3. 代码边界与依赖方向

```text
Controller / Channel -> AgentEngine -> Intent / Planner / Reflect / Executor
                    -> ToolInvoker / RagService / LlmClient
                    -> Domain Service -> Repository / Storage
```

- Agent 编排层不直接操作 Repository。
- 工具层默认只读；写操作只能由确认后的 `ExecutorAgent.commit` 进入 Service。
- Controller 使用统一异常处理，不自行拼装错误响应。
- 网络解析、缓存、业务判定和编排必须分离。
- 旅行上下文通过 `tripId`、`itineraryItemId` 传递，并由服务端校验拥有权。
- 新功能优先放入旅行域，不创建新的通用个人助理模块。

## 4. Agent 开发规范

新增意图必须同步更新意图枚举、规则识别、LLM prompt、`AgentEngine` 分派、Planner/Handler、前端类型、SSE 消费、测试和 API 文档。已经退役的日报、健康意图不得重新加入；办公/学习/健康也不得再长出新的横向模块入口。

Planner 只生成 `AgentPlan` 和 `PlanOperation`，不落库；每次修改必须可生成 Diff；确认时校验 `contentHash` 和 `planRevision`；提交与 `OperationHistory` 在同一事务中完成；新增改变行程的入口必须补撤销测试。

行中重规划必须携带当前时间、旅行日、当前位置（经授权时）、已完成/进行中/未开始条目。只允许自动调整 `PENDING` 条目：已开始/已完成/已跳过是不可覆盖的既成事实；结构化规划若命中锁定条目必须回退规则路径。输出 Diff 并再次确认；失败时保留原计划。

行程项状态只能通过 `PUT /api/itinerary/{id}/status` 或 Service 层状态机推进，不要直接 `UPDATE status` 列：

- `PENDING`：清空实际时间，重新可被自动重规划调整。
- `IN_PROGRESS`：写 `startedAt`，清空 `completedAt/visitedAt`。
- `COMPLETED`：写 `startedAt/completedAt/visitedAt`，追加 `CHECK_IN` 事件。
- `SKIPPED`：写 `completedAt`，不写 `visitedAt`，不计入完成率。

任何状态变化必须与 `OperationHistoryService.withHistory` 同事务，`before` 快照在变更前生成，撤销要能还原状态与实际时间。

**旅行回顾口径**（`TripReviewService` / `TravelReviewDtos`）：

- 完成率 = `completedItems / totalItems`；`SKIPPED` 单列，不冒充完成。
- `plannedCost`（`itinerary_item.cost`）与 `actualCost`（`expense`）分开，`variance = actual - planned`。
- `plannedPlaces` 是全部有坐标的计划地点；`visitedPlaces` / `actualRoute` **只认 `visitedAt` 非空**，计划时间不算到访。
- `tripId` 关联的备忘/日程分别计入 `memoCount` / `scheduleCount`。
- `aiSummary` 只能用当前行程数据；LLM 不可用时退回确定性规则文案，不编造事实。

## 5. 工具与 MCP 规范

工具结果统一使用可解释结构：

```json
{
  "provider": "amap",
  "source": "web-service",
  "fetchedAt": "2026-11-19T10:00:00Z",
  "expiresAt": "2026-11-19T10:30:00Z",
  "degraded": false,
  "degradeReason": null,
  "data": {}
}
```

- 景点和餐厅优先真实 POI，静态目录只能是明确标记的 fallback。
- 无 OTA 授权时酒店只能返回参考价和深链，不称为实时库存。
- 超时、限流、空结果和凭证错误必须分类记录。
- MCP 单个 server 失败不能阻断应用启动。
- 新工具必须在 ToolRegistry 注册并通过只读安全拦截。
- trace 记录参数摘要、耗时、来源和错误，不记录密钥或完整隐私数据。

## 6. RAG 规范

RAG 是旅行知识和 Planner 研究能力，不是独立学习模块。

- 文档按用户隔离；公共知识和私有文档分开授权。
- 更新生成新版本，不静默覆盖；回滚切换 ACTIVE 版本后立即 `reload()` 索引，版本行状态即审计证据
  （`operation_history` 是 trip 维度，不承载知识库回滚，当前也不应硬塞）。
- 检索返回 `documentId`、`version`、`chunkId`、`score`、`source` 和引用片段。
- 规划期必须直接把 RAG 命中挂到方案：`plan.extras.citations`、`AgentResult.citations`、SSE `rag_result` 三处同构；
  空数组表示没有可靠引用，前端不得编造。引用只能来自真实检索，模型不得生成 `documentId/version/score`。
- 规划期检索先由 `RagService` 按 `travel.rag.min-score`（默认 0.05）过滤，`PlannerAgent` 另以 0.05 过滤噪声；低相似度命中不得进入方案。
- 指定城市时保留该城市文档和通用文档。
- 旅行问答评估集在 `RagEvaluationTest`（7 条，Recall@5/MRR），低相似度拒答逻辑在 `RagServiceThresholdTest`；
  两处都是**本地哈希 Embedding 的保守基线**，接入真实 Embedding 后必须重跑并调整阈值，不能拿基线当质量证明。
- `LocalHashEmbeddingService` 是离线降级实现，不能作为高质量语义检索证明。

## 7. 数据库与迁移

- Flyway `V*__*.sql` 是 schema 变更唯一通道。
- 已发布迁移不可修改，避免 checksum 失败。
- 旧办公、学习、健康表先代码下线，再按生产数据情况用新迁移退役；**P0 只做代码下线，V19 不删除旧表**，数据退役由后续 forward-only 迁移按环境决定。
- 新字段优先可空、兼容旧数据，再补回填和约束；P1 的状态/实际时间/旅行关联见 `V19__travel_closed_loop.sql`。
- 行程项状态、实际时间、`trip_event`、`schedule_item/memo` 的旅行关联是旅行域契约，不允许用旁路 SQL 绕过 Service。
- H2（MySQL 模式）和 MySQL 8 必须同时验证。
- 删除旅行时明确处理照片记录、文件、日程、备忘、旅行事件与操作历史级联（`TripService.delete`）。

## 8. 前端规范

- 路由页面懒加载；地图、图表等重依赖显式分包。
- Agent SSE 统一进入 `agentStore`，不要在页面重复解析。
- 计划明确区分 preview、待确认、已确认、失败和已撤销。
- 来源、降级和过期提示显示在结果附近。
- 日程、备忘、预算、照片和足迹优先按 `tripId` 读取；P1 的 `tripId`/`itineraryItemId`/`travelDay` 可空，旧数据不得强转。
- 行程项状态展示必须区分 `PENDING/IN_PROGRESS/COMPLETED/SKIPPED`；足迹与「去过」统计只认 `visited === true`。
- 方案下方展示 `AgentResult.citations`（文档/版本/片段/相似度/来源），空数组时不要显示「无引用」之外的臆测文案。
- 删除旧模块时同步清理 API client、类型、路由、测试和菜单。

## 9. 测试策略

单元测试覆盖日期映射、意图路由、预算计算、RAG 城市过滤、来源标记、事件状态转换和权限判断。

集成测试使用真实 Spring 上下文、MockMvc、测试数据库和关闭外部网络的 test profile，重点验证用户隔离、确认版本冲突 409、提交与历史同事务、工具失败原计划不变和退役 API 行为。

端到端至少覆盖：登录创建旅行；生成方案并确认；天气变化只调整未开始安排；记账更新预算；上传照片并标记到访；生成旅行回顾；多标签提交旧方案被拒绝。
P1 新增测试基线：`TripClosedLoopTest` 覆盖「完成写 visitedAt + 回顾」「已完成户外项不被自动替换」「Planner 挂知识引用」「备忘/日程旅行关联」；
前端 P1 交互（状态按钮、回顾页）已落地；补 E2E 前不得把「真实浏览器已覆盖 P1 交互」写成结论，现有单测/组件测试只证明逻辑与渲染契约。

## 10. 安全、隐私与排障

- 所有行程接口先校验资源归属。
- 照片依据文件头白名单判定，不信任客户端 MIME；响应带 `nosniff` 和 CSP。
- EventSource 和图片使用短时只读 ticket，不能把登录 JWT 放进 URL。
- 日志禁止记录 API Key、token、照片内容和完整用户原文。
- AI 总结只能使用当前行程数据及用户明确授权的照片、备忘和消费信息。
- 精确位置默认不发送给第三方工具，除非必要且获得授权。

排障顺序：先看 SSE 的 `intent/planning/tool_call/tool_result/rag_result/plan_update/confirmation`；再看 Agent Run 和任务 DAG；再看 provider、超时、限流、降级；然后看 Planner 操作和确认指纹；最后检查事务和数据库约束。

## 11. 评审清单

- [ ] 功能属于旅行域，没有新增横向个人助理模块。
- [ ] 复用了日期、城市、预算、认证和工具抽象。
- [ ] 有 preview、确认、撤销和操作历史。
- [ ] 行程项状态只走 Service 状态机，且进撤销链；已开始/完成条目不被自动重规划覆盖。
- [ ] 区分 planned、visited、actual 和 estimated。
- [ ] RAG 引用三处同构（extras / AgentResult / SSE），没有模型编造引用。
- [ ] 标出真实来源和降级原因。
- [ ] 补齐后端、前端、数据库、测试和文档。
- [ ] 未修改历史 Flyway migration，未泄露 Key/token/隐私数据。
- [ ] 通过 `mvn test`、`npm run typecheck`、`npm test` 和 `npm run build`。

