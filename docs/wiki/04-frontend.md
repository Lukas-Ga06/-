# 04 · 前端结构（travel-agent-web）

> React 18 + TypeScript + Vite 6 · antd 5 · zustand 4 · axios · react-router 7 · echarts 6 · maplibre-gl。
> 源码目录：[travel-agent-web/src/](../../travel-agent-web/src/)

## 4.1 目录职责

| 目录 | 职责 |
| --- | --- |
| [api/](../../travel-agent-web/src/api/) | axios 封装与按域 API 模块；`generated/schema.d.ts` 由 OpenAPI 生成 |
| [pages/](../../travel-agent-web/src/pages/) | 15 个路由级页面，全部 `lazy()` 懒加载 |
| [components/](../../travel-agent-web/src/components/) | 30+ 领域组件（每个一个文件夹） |
| [store/](../../travel-agent-web/src/store/) | zustand 全局状态（每个 store 配单测） |
| [hooks/](../../travel-agent-web/src/hooks/) | 通用 hooks |
| [lib/](../../travel-agent-web/src/lib/) | 纯函数库（坐标/图表/提醒/状态队列/冲突检测等） |
| [styles/](../../travel-agent-web/src/styles/) | 全局样式与主题变量 |
| [types/](../../travel-agent-web/src/types/) | 共享类型 |
| [test/](../../travel-agent-web/src/test/) | 测试基础设施 |

## 4.2 路由（App.tsx）

[App.tsx](../../travel-agent-web/src/App.tsx) 集中定义，业务页面用 `Protected` 包裹（未登录跳 `/login`）：

| 路径 | 页面 | 用途 |
| --- | --- | --- |
| `/login` | Login | 登录/注册 |
| `/` | Home | 首页：自然语言入口 + 快捷任务 |
| `/planner` | Planner | 规划工作台（聊天 + Diff 确认） |
| `/trip/:id` | Trip | 行程主界面（地图 / AI 助手 / 时间轴） |
| `/trip/:id/review` | TripReview | 单行程回顾 |
| `/review` | Reviews | 回顾列表 |
| `/expenses` | Expenses | 记账与预算执行 |
| `/memos` | Memos | 备忘 |
| `/assistant` | Assistant | AI 助手独立页 |
| `/travel` | Travel | 行程列表 |
| `/knowledge` | Knowledge | 知识库检索/问答 |
| `/workbench` | Workbench | 工作台 |
| `/schedule` | Schedule | 日程 |
| `/footprint` | Footprint | 足迹地图 |
| `/settings/diagnostics` | （内嵌） | 系统诊断（能力边界/LLM 状态） |
| `/profile` | Profile | 账户（导出/注销） |

`/office`、`/learning`、`/health` 仅存到 `/` 的兼容重定向（已从产品主线移除）。

## 4.3 状态管理（store/，zustand）

| Store | 职责 |
| --- | --- |
| [agentStore](../../travel-agent-web/src/store/agentStore.ts) | ★ 核心：Agent 对话消息、SSE 流式状态、stage 文案、待确认方案、conversationId 与作用域、运行记录、活动事件、警告、执行结果。`send()` 构造用户消息并按作用域调 `streamAgentChat*`，逐事件更新 UI |
| [authStore](../../travel-agent-web/src/store/authStore.ts) | 登录态、当前用户、`/me` 会话恢复 |
| [tripStore](../../travel-agent-web/src/store/tripStore.ts) | 当前行程与条目、状态推进、撤销提示 |
| [themeStore](../../travel-agent-web/src/store/themeStore.ts) | 暗色模式：写入 `html[data-theme]` + antd `darkAlgorithm`，持久化 localStorage |
| [uiStore](../../travel-agent-web/src/store/uiStore.ts) | 全局 UI（抽屉、Toast 等） |
| [statusQueueStore](../../travel-agent-web/src/store/statusQueueStore.ts) | 状态提示队列 |
| [stageText](../../travel-agent-web/src/store/stageText.ts) | Agent 阶段文案映射 |
| [sessionCleanup](../../travel-agent-web/src/store/sessionCleanup.ts) | 登出/切换账号时清理各 store |

## 4.4 API 层（api/）

- [client.ts](../../travel-agent-web/src/api/client.ts)：`axios.create({baseURL:'/api'})`；请求拦截注入 `Authorization`；响应拦截统一解包 `{code,message,data}` 信封；扩展配置 `keepEnvelope`（分页信封保留）、`skipAuthRedirect`；401 统一登出跳转。
- 按域模块：[agent.ts](../../travel-agent-web/src/api/agent.ts)（同步 + SSE 流式 + 确认/撤销）、[auth.ts](../../travel-agent-web/src/api/auth.ts)、[trip.ts](../../travel-agent-web/src/api/trip.ts)、[expense.ts](../../travel-agent-web/src/api/expense.ts)、[memo.ts](../../travel-agent-web/src/api/memo.ts)、[assistant.ts](../../travel-agent-web/src/api/assistant.ts)、[knowledge.ts](../../travel-agent-web/src/api/knowledge.ts)、[photo.ts](../../travel-agent-web/src/api/photo.ts)、[reminder.ts](../../travel-agent-web/src/api/reminder.ts)、[ticket.ts](../../travel-agent-web/src/api/ticket.ts)。
- **类型生成**：`npm run api:generate` 用 openapi-typescript 从 `docs/api/openapi.json` 生成 `api/generated/schema.d.ts`；`npm run api:check` 校验契约一致性（CI 门禁）。

## 4.5 关键组件（components/）

| 组件 | 职责 |
| --- | --- |
| [ChatPanel](../../travel-agent-web/src/components/ChatPanel/) | Agent 聊天面板；空对话时展示快捷任务（规划旅行/天气调整/记账/查攻略） |
| [PlanDiff](../../travel-agent-web/src/components/PlanDiff/) | 方案差异渲染：按 REMOVE/MODIFY/ADD 分组，展示标题/详情/成本/天数，配确认/取消按钮 |
| [AgentActivityPanel](../../travel-agent-web/src/components/AgentActivityPanel/) | Agent 事件流展示（run/intent/planning/replan/tool_call/confirmation/degrade… 图标映射） |
| [AgentRunsDrawer](../../travel-agent-web/src/components/AgentRunsDrawer/) | 运行记录抽屉（含降级任务标注） |
| [MapView](../../travel-agent-web/src/components/MapView/) | 地图（maplibre + 高德 JS API，未配 Key 时降级） |
| [FootprintMap](../../travel-agent-web/src/components/FootprintMap/) | 足迹地图（仅 `visitedAt` 非空条目） |
| [Timeline](../../travel-agent-web/src/components/Timeline/) | 行程时间轴：开始/完成/跳过推进 |
| [ItineraryCard](../../travel-agent-web/src/components/ItineraryCard/) | 行程条目卡片 |
| [BudgetChart](../../travel-agent-web/src/components/BudgetChart/) / [BudgetExecution](../../travel-agent-web/src/components/BudgetExecution/) | 预算图表 / planned vs actual 执行视图 |
| [ExpensePanel](../../travel-agent-web/src/components/ExpensePanel/) | 记账面板 |
| [CitationList](../../travel-agent-web/src/components/CitationList/) | RAG 引用列表（documentId/chunkId/score/source） |
| [ExecutionResultPanel](../../travel-agent-web/src/components/ExecutionResultPanel/) | 工具执行结果展示 |
| [UndoBar](../../travel-agent-web/src/components/UndoBar/) | 撤销条：提交后出现，倒计时内可撤销（走后端 OperationHistory 快照） |
| [ReminderBell](../../travel-agent-web/src/components/ReminderBell/) | 提醒铃铛 |
| [VoiceButton](../../travel-agent-web/src/components/VoiceButton/) | 语音输入 |
| [ConversationDrawer](../../travel-agent-web/src/components/ConversationDrawer/) / [CityDrawer](../../travel-agent-web/src/components/CityDrawer/) | 会话管理 / 城市选择抽屉 |
| 通用 | ErrorBoundary / EmptyState / Skeleton / Toast / PaginationBar / SwipeRow / CountUp / PageTransition / OfflineBanner / ResultCard / RunStatus |

`pages/Planner/components` 与 `pages/Trip/components` 存放页面私有子组件。

## 4.6 hooks 与 lib

- hooks：[useAgentScope](../../travel-agent-web/src/hooks/useAgentScope.ts)（会话作用域）、[useOnlineStatus](../../travel-agent-web/src/hooks/useOnlineStatus.ts)、[useIntersection](../../travel-agent-web/src/hooks/useIntersection.ts)。
- lib：[coords](../../travel-agent-web/src/lib/coords.ts)（坐标系转换）、[echarts/chartTheme](../../travel-agent-web/src/lib/echarts.ts)（图表主题）、[reminderNotify](../../travel-agent-web/src/lib/reminderNotify.ts)（浏览器通知）、[statusQueue](../../travel-agent-web/src/lib/statusQueue.ts)、[systemStatus](../../travel-agent-web/src/lib/systemStatus.ts)、[tripConflict](../../travel-agent-web/src/lib/tripConflict.ts) / [tripFocus](../../travel-agent-web/src/lib/tripFocus.ts)、[resource](../../travel-agent-web/src/lib/resource.ts)。

## 4.7 构建 / PWA / 主题

- [vite.config.ts](../../travel-agent-web/vite.config.ts)：Vite 6 + `VitePWA`（manifest、navigate fallback、`/api` 排除、maplibre vendor 排除、行程相关 GET API runtime caching）→ 离线可查看行程；analyze 模式接 rollup-plugin-visualizer。
- [main.tsx](../../travel-agent-web/src/main.tsx)：入口挂载 `Root`，读取 `themeStore` 并 `applyTheme(mode)` 同步 `html[data-theme]` 与 antd 算法。
- 质量脚本：`typecheck`（tsc）、`test`（vitest + happy-dom + Testing Library）、`lint`（eslint 9）、`format:check`（prettier）、`build:check`（含 bundle 预算校验 `scripts/check-bundle-budget.mjs`）。
- E2E：Playwright（[playwright.config.ts](../../travel-agent-web/playwright.config.ts)），默认桌面 Chromium 套件 + 弱网 / 多端并发 / device 汇总套件；`e2e:isolated` 用隔离后端（独立 H2，LLM/MCP/AMAP 全关，由 `scripts/start-e2e-backend.mjs` 启动，不读开发者 `.env.local`）；另有视觉回归配置 `playwright.visual.config.ts`（含 axe 可访问性）。
