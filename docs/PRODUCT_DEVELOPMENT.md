# AI 智能旅行助手：产品开发文档

## 1. 文档目的

本文定义 `travel-agent` 从“通用 AI 个人助理”收敛为“以旅行为唯一核心场景的 AI Agent 产品”后的产品范围、用户流程、系统能力、数据边界、验收标准和迭代计划。文档同时标出当前代码基线与目标能力之间的差距。

## 2. 产品定位

用户用自然语言提出旅行需求，AI Agent 理解意图、追问缺失信息、拆解任务并调用真实工具，生成可解释、可修改、需用户确认的旅行方案；确认后同步行程、日程、预算、消费和足迹，并在旅行中根据事件动态调整，旅行后生成可追溯的回顾。

核心原则：一个旅行场景、一套 Agent 引擎、工具结果可解释、写操作必须确认、所有变更可撤销可审计、数据按旅行聚合。

## 3. 当前完成度判断

按新目标而不是旧模块数量评估，截至 **2026-11-19 工作树** 约为 **85%**。判断依据是源码与可检索契约，不是路线图口径。

| 能力域 | 判断 | 说明 |
| --- | --- | --- |
| Agent 核心引擎 | 高，约 90%+ | 四 Agent、任务 DAG、工具循环、取消、失败观察、审核后重规划和确认门已具备 |
| 行前规划 | 高，约 85%~90% | 天气、路线、POI、预算、地图、Diff、确认和同步已具备 |
| 旧模块清理（P0） | 高，约 95% | Controller/实体/仓库/服务/意图/配置/前端页面与请求已删除；残留仅兼容重定向与旧表数据 |
| RAG 进入规划（P1） | 高，约 85% | Planner 规划期直接检索并带引用，`query_travel_knowledge` 进只读工具白名单；已有 7 条评估集（本地哈希基线），真实 Embedding 调优未做 |
| 行中动态管理（P1） | 高，约 85% | 状态机、行中锁定、trip_event、旅行关联、前端状态操作均已落地 |
| 行后回顾（P1） | 高，约 85% | 回顾聚合 API、回顾页 `/trip/{id}/review` 与回顾列表 `/review` 已落地；评估与真机验收未做 |
| 真实工具口径 | 中，约 60%~70% | 高德路线和部分 POI 已真实接入；餐厅、景点、酒店仍有静态目录或参考价降级 |

结论：P0 收缩已完成代码层清理，P1 前后端闭环已打通（H2 全量回归通过）；下一阶段的重点是真实 Embedding 质量验收、真实服务逐项验收，再进入 P2 上线质量。

## 4. 用户与核心流程

```text
自然语言需求 -> 意图识别/槽位抽取 -> 缺失信息追问
-> Planner 并行研究（RAG / POI / 天气 / 路线 / 预算）
-> 结构化方案 + 来源引用 + 风险 -> Plan Diff / 用户确认
-> Executor 单事务提交（行程 / 日程 / 预算 / 足迹）
-> 行中事件触发局部重规划 -> 行后回顾与 AI 总结
```

核心用户故事：

| 编号 | 用户故事 | 验收结果 |
| --- | --- | --- |
| US-01 | 安排一个上海三日游，预算 3000 元，亲子友好 | 识别目的地、天数、预算、偏好；缺失项可追问 |
| US-02 | 明天下雨，把户外景点换掉 | 查询对应日期天气，只修改未开始条目，展示 Diff 并再次确认 |
| US-03 | 记一笔午餐 45 元 | 写入消费，实际支出和预算执行金额同步更新 |
| US-04 | 明天下午提醒我办理签证 | 创建与旅行关联的日程或备忘 |
| US-05 | 我已经去过博物馆 | 标记实际到访，后续重规划不得覆盖已完成条目 |
| US-06 | 这次旅行怎么样 | 返回完成率、计划/实际花费、照片、到访地点、修改历史和 AI 总结 |

## 5. 产品范围

### 5.1 保留并强化

- 行程规划与管理、天气/路线/景点/餐厅/酒店研究
- 预算分析与消费记账、日程与备忘、提醒
- 地图足迹、照片和旅行回顾
- Agent Run、任务 DAG、工具调用追踪
- RAG 旅行知识库、MCP 工具桥接、认证和权限

### 5.2 从产品主线移除

邮件管理、会议纪要、自动日报、发票识别与管理、学习辅助、健康数据管理。

移除不仅是删页面，还包括意图枚举、首页聚合、菜单、权限、配置、接口文档、测试、存储和部署说明。

### 5.3 本次已完成的重构（不再是待办）

`LearningController` 已拆分：RAG 文档能力迁入 `TravelKnowledgeController`（`/api/knowledge/**`），学习产物业务删除；
`AssistantIntentHandler` 只保留备忘/日程，日报与健康分支删除；
`Workbench` 已改造成 **Agent 运行观察页**（run / 任务 DAG / 工具调用）。

## 6. 目标产品信息架构

```text
AI 智能旅行助手
├── 行程规划
├── 当前行程（地图/时间线、日程备忘、预算消费、动态调整）
├── 旅行知识库
├── 足迹与照片
├── Agent 运行观察
└── 旅行回顾
```

首页应展示今日 Agent Run、进行中的旅行、待确认方案、下一项提醒、预算异常和最近旅行回顾，不再展示健康摘要、会议纪要、日报或学习产物。

## 7. Agent 产品契约

| Agent | 负责 | 不负责 |
| --- | --- | --- |
| IntentAgent | 意图、目的地/日期/预算/偏好、追问判断 | 直接写数据库 |
| PlannerAgent | 组合研究结果、生成 PlanOperation、计算冲突和预算 | 直接提交写操作 |
| ExecutorAgent | 预览风险、提交确认后的操作、写历史 | 擅自改变未确认方案 |
| ReflectAgent | 校验时间冲突、预算、工具结果和降级说明 | 代替用户确认 |

Planner 研究链路必须接入 RAG、POI、Weather、Route 和 Budget。RAG 结果至少包含 `documentId`、`version`、`chunkId`、`score`、`source` 和引用片段，并可在方案与 SSE 事件中追溯。

方案生成只产生 preview；用户确认时校验 `contentHash` 与 `planRevision`；提交使用单事务并写操作历史；撤销恢复 before 快照。

## 8. 旅行数据模型要求

1. `itinerary_item` 增加 `PENDING / IN_PROGRESS / COMPLETED / SKIPPED`，记录 `startedAt`、`completedAt`、`visitedAt`。
2. 增加 `trip_event`，记录天气突变、拥堵、闭馆、用户打卡和 Agent 调整。
3. `schedule_item`、`memo` 增加可空 `tripId`、`itineraryItemId`、`source`、`travelDay`。
4. 足迹区分 `planned`、`visited`、`actualRoute`，不能把未来计划显示为“去过”。
5. 增加 `GET /api/trips/{id}/review`，聚合完成率、实际支出、照片、路线、天气、备忘和修改历史。
6. 已发布 Flyway 迁移不可直接修改；旧表退役使用新的 forward-only migration。

> 状态：第 1–4 项已由 `V19__travel_closed_loop.sql` + `TripEventService` / `TripReviewService` 落地；
> 第 5 项 `GET /api/trips/{id}/review` 已实现；第 6 项旧表**未在 V19 删除**，等待按环境决策的后续迁移。

## 9. 真实工具与数据来源口径

| 能力 | 产品口径 |
| --- | --- |
| 高德路线、地图和部分 POI | 真实 Provider，展示获取时间和来源 |
| 静态旅行目录 | 本地目录 fallback，标注 `catalog` |
| 酒店价格 | 参考价，不表示实时库存；提供来源和深链 |
| 天气 | 真实服务成功时标注 provider，失败时标注模拟/降级 |
| RAG | 展示文档来源、版本、引用片段和相似度 |
| MCP | 展示 server/tool 名称、调用结果和失败原因 |

每个工具结果建议统一包含 `provider`、`source`、`fetchedAt`、`expiresAt`、`degraded`、`degradeReason`。

## 10. 分阶段开发计划

### P0：产品收缩与语义统一（代码/配置/前端清理完成，回归已复跑）

已完成：

- [x] 删除 `OfficeController`（邮件/纪要/发票）、`ReportController`（日报）、`HealthController`、旧 `LearningController`，
      以及 `MailItem` / `MeetingMinutes` / `DailyReport` / `Invoice` / `LearningArtifact` / `HealthRecord` 及其 repository/service。
- [x] 删除 `travel.invoices.*` / `travel.ocr.*` 配置；`/api/system/status` 不再返回 `ocrConfigured` / `ocrHint`。
- [x] 意图枚举删除 `GEN_DAILY_REPORT` / `QUERY_HEALTH`；票据只读拦截只剩记账/备忘/日程。
- [x] 前端删除 `/office`、`/learning`、`/health` 页面与 API/类型；路由重定向到 `/`；首页不再请求健康/日报/纪要/学习产物。
- [x] Workbench 改为「Agent 运行观察」；品牌改「AI 智能旅行助手」；路书 PDF 页脚与设计系统注释同步改名。
- [x] RAG 接口迁入 `TravelKnowledgeController`，知识库页面保留并改为旅行知识管理。
- [x] README、API 参考、数据模型、前端、部署等文档同步退役模块与新增契约。
- [x] **后端全量回归**：`mvn test` **540** 个用例全部通过（2026-11-20 复核；含 1 条无 Docker 时按设计跳过的 MySQL 迁移测试）。
- [x] **前端全量回归**：`npm run typecheck && npm test && npm run build` 通过（46 个测试文件 / 468 个用例；覆盖率阈值已按 ratchet 上调）。
- [x] **迁移实跑**：全新 H2 文件库从 V1 迁到 V19，`ddl-auto=validate` 启动通过。

未完成 / 残留：

- [ ] MySQL 8 / Docker / CI / 关键浏览器流程本轮未重新验收（P2 范围）。
- [x] **真实 HTTP 冒烟**：`.e2e/verify-modules.mjs` 在全新 H2 库 + 生产 jar 上跑通
      （建行程 → 加条目 → 打卡 → 回顾 → 事件 → 足迹 → 知识库入库/检索/引用 → Agent 问答）。
- [ ] 旧表 `daily_report` / `mail_item` / `meeting_minutes` / `invoice` / `learning_artifact` / `health_record`
      数据保留，代码已不再映射；退役由后续 forward-only 迁移按环境决定。

完成标准对照：代码、配置、测试和文档中**已不再有已退役业务的有效入口**；兼容重定向与旧表数据不构成入口。

### P1：旅行闭环（前后端已落地，验收与真实数据待补）

已实现：

- [x] **RAG 接入 Planner**：`knowledgeCitations` 直接检索并挂引用；`AgentResult.citations`、方案 extras、SSE `rag_result`
      均含 `documentId/version/chunkId/score/source`；新增只读工具 `query_travel_knowledge`。
- [x] **行程项状态机**：`PENDING/IN_PROGRESS/COMPLETED/SKIPPED` + `startedAt/completedAt/visitedAt`；
      `PUT /api/itinerary/{id}/status`；完成写 `CHECK_IN` 事件并进撤销链。
- [x] **旅行事件**：`trip_event`（V19）+ `TripEventService` + `GET /api/trips/{id}/events`；当前写入天气/Agent 调整/打卡/手工修改，`CONGESTION`/`CLOSURE` 为预留类型。
- [x] **旅行关联**：`schedule_item` / `memo` 增加 `trip_id` / `itinerary_item_id` / `source` / `travel_day`。
- [x] **行中锁定**：自动重规划只允许 `PENDING`；结构化规划命中已开始/完成/跳过条目回退规则路径。
- [x] **旅行回顾 API**：`GET /api/trips/{id}/review` 聚合完成率、计划/实际花费、计划/实际到访、`actualRoute`、照片、事件、修改历史与 AI 总结。
- [x] **足迹口径**：`GET /api/trips/map` 增 `phase/plannedPlaces/visitedPlaces/visited/actualRoute`，只把 `visitedAt` 非空算「去过」。
- [x] **撤销快照修复**：before 快照在变更前生成，撤销可还原打卡状态与实际时间。
- [x] **前端 P1 交互**：行程项状态操作（开始/完成/跳过/重置）、`/trip/:id/review` 回顾页、`/review` 回顾列表已落地；
      方案确认弹窗展示知识依据 citations。
- [x] **前端契约层**：`updateItemStatus` / `getTripReview` / `getTripEvents` / review 类型与知识库页面。

未实现（保持未勾选）：

- [x] **RAG 评估集**：`RagEvaluationTest` 建立 7 条旅行问答评估（Recall@5=0.857、MRR=0.714，本地哈希 Embedding 基线）；
      `RagServiceThresholdTest` 用桩向量验证低相似度拒答逻辑。阈值由 `travel.rag.min-score`（默认 0.05）控制；
      **这是离线降级实现的保守基线，不构成高质量语义检索证明**。
- [ ] **真实 Embedding 质量验收**：接入 OpenAI 兼容 Embedding 后需重跑评估集并上调阈值。
- [ ] **真实 OTA 库存 / 实时房态价格**：无服务商授权与库存接口，仍为参考价 + 深链。
- [ ] **P2 全量验收**：真实模型、Safari/iOS 真机、弱网/断网、多端并发确认尚未完成。

完成标准对照：「一句话需求产生带 RAG/POI/天气/路线证据的方案」已达成；「行中只重排未开始条目」已达成；
「行后可查看实际完成和消费」前后端均已达成（真实数据验收与 P2 全量回归仍待做）。

### P2：真实服务与上线质量

逐一验收 MCP 工具和真实密钥路径；用真实模型复验；进行 Safari/iOS、弱网、断网、超时和多端并发确认测试；获得 OTA 授权后再接入实时库存。

## 11. 产品验收清单

- [x] 自然语言需求能抽取槽位，缺失信息会追问。
- [x] Planner 能调用 RAG、POI、天气和路线（含规划期知识引用）。
- [x] 方案显示来源、预算、时间冲突和降级原因。
- [x] 用户不确认时不写入行程核心数据。
- [x] 确认后同步行程、日程、预算快照和足迹。
- [x] 过期方案无法覆盖新版本行程。
- [x] 行中自动调整只影响未开始条目（后端锁定已实现；天气重替换与结构化规划都走该规则）。
- [x] 消费记账更新实际支出但不修改计划成本。
- [x] 行后回顾区分计划地点与实际到访地点。
- [x] 旧模块页面、API、菜单、权限和首页聚合全部移除（兼容重定向除外）。
- [ ] H2 已验证（全量测试 + 全新库 V1→V19 迁移 + validate 启动 + 生产 jar 真实 HTTP 冒烟）；MySQL、Docker、CI 和关键浏览器流程本轮未重新验收。
- [x] 前端 P1 页面与状态操作已落地（typecheck/test/build 通过；真实浏览器回归仍待 P2）。
- [x] RAG 评估集已建立（本地哈希基线；真实 Embedding 质量验收待补）。
- [ ] 真实 OTA 库存与实时房态价格接入。
- [ ] P2 全量验收（真实模型、iOS/Safari、弱网、多端并发确认）。

> 勾选口径：只勾源码/契约可直接验证且本轮确认过的项；全量回归未复跑、RAG 评估与 P2 未完成，保持未勾选，不以「代码已写」代替验收。

