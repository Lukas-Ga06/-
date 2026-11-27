# 前端结构与页面说明（travel-agent-web）

> 状态：当前事实 | 适用版本：0.1.0（Spring Boot 3.5.16 / Node 20） | 最后验证：2026-11-19 | 事实来源：travel-agent-web/src/、travel-agent-web/vite.config.ts、travel-agent-web/package.json

> 面向**要改界面的前端开发者**（路由、组件、两大链路）与**参与评审的产品/设计同学**（页面清单、交互约定）。
> 内容基于 `travel-agent-web/` 当前 HEAD 的真实源码，路径均可直接打开。

前端是整个「AI 智能旅行助手」的唯一交互入口。后端（`travel-agent-server`）提供 Agent 引擎与 REST/SSE 接口；
前端把 Agent 的**过程**（意图、RAG 引用、工具调用、方案对比）可视化，并守住一条底线 ——
**任何 AI 修改都不直接落库，必须经 Diff → 确认 → 提交三步**。

---

## 1. 技术栈与依赖

版本取自 `travel-agent-web/package.json`。

| 依赖 | 版本 | 用途 |
| --- | --- | --- |
| react / react-dom | ^18.3.1 | 视图层，`StrictMode` + createRoot |
| react-router-dom | ^7.18.4 | `BrowserRouter` 客户端路由 |
| typescript | ^5.6.2 | `strict: true`，`build` 先跑 `tsc --noEmit` |
| vite | ^6.4.5 | 开发服务器与生产构建（Terser 压缩） |
| vite-plugin-pwa | ^1.3.0 | app shell 预缓存，`autoUpdate` |
| antd / @ant-design/icons | ^5.21.4 / ^5.5.1 | 组件库（`ConfigProvider` + `zh_CN`）与图标 |
| axios | ^1.7.7 | REST 请求，`baseURL: '/api'` |
| echarts | ^6.1.0 | 预算图、记账分类图、回顾统计 |
| 高德 JS API / maplibre-gl | 2.0 / **6.15.0**（精确锁定，无 `^`） | 行程页 2D/3D 地图（高德）/ 足迹球体投影（MapLibre + 高德栅格底图） |
| zustand | ^4.5.5 | 全局状态（5 个 store，见第 5 节） |
| dayjs | ^1.11.13 | 日期格式化与 Day 映射 |
| vitest / happy-dom | ^4.1.12 / ^21.0.4 | 单元测试；组件类用例的 DOM 环境 |
| @testing-library/react | ^16.3.3 | 组件测试（现有用例偏逻辑，见第 9 节） |
| @playwright/test | ^1.64.1 | e2e 冒烟 |

**依赖安全结论（第九批更正）**：`maplibre-gl` 固定为精确 `6.15.0`（去掉 `^`，`npm install` 不会漂移）。`npm audit --omit=dev` 为 **0 条**；
相关 CVE 的影响区间是 `<= 6.4.0`，当前 `6.15.0` **不受影响**。旧文档写的「官方暂无修复、通过不用 HTML 字符串接口规避」**已过期且理由不准确**
（该公告的 sink 在 attribution 控件，不是 `Popup.setHTML`）。代码侧仍保持「用 `setDOMContent` / `textContent`、不用 HTML 字符串」的写法。

运行时硬约束：**Node ≥ 20**（`.github/workflows/ci.yml` 固定 20）。

---

## 2. 目录结构

```
travel-agent-web/
├── index.html                 # 首帧前内联脚本预设 <html data-theme>，避免暗色闪白
├── package.json / tsconfig.json
├── vite.config.ts             # /api→8080 反代、manualChunks 分包、PWA
├── vitest.config.ts / playwright.config.ts
├── nginx.conf / Dockerfile    # 生产静态托管（/api 需反代到后端 8080）
├── e2e/smoke.spec.ts          # 冒烟：守卫跳转 → API 登录 → 建行程 → 行程页 → PWA 资源
├── e2e/planner-form.spec.ts   # 结构化规划表单两条闭环（规则路径，不依赖 LLM）
└── src/
    ├── main.tsx               # ConfigProvider（色板/暗色算法）+ AntApp + 挂载 Root
    ├── App.tsx                # 路由表、Protected、登录态恢复、ErrorBoundary、Suspense
    ├── global.d.ts            # Web Speech API 等缺失类型补声明
    ├── api/                   # 15 个文件（含 knowledge / review 契约与测试）
    │   ├── client.ts          # axios 实例、{code,message,data} 解包、401 分流
    │   ├── agent.ts           # SSE（GET EventSource / POST fetch）与方案确认接口
    │   ├── auth.ts            # 登录/注册/me/登出；signOut() 是唯一登出入口
    │   ├── ticket.ts          # 短期只读票据：缓存 + 并发合并 + useAccessTicket()
    │   ├── trip.ts / expense.ts / memo.ts      # 行程与条目（含状态推进）、回顾/事件、记账、备忘
    │   ├── knowledge.ts       # 旅行知识库：录入 / 检索 / 版本 / 回滚
    │   ├── assistant.ts       # 日程 + 系统状态（办公类接口已删除）
    │   ├── reminder.ts / photo.ts              # 提醒看板、行程照片（原图带票据）
    ├── components/            # 48 个文件（27 个目录，含组件测试），见第 7 节
    ├── hooks/                 # useIntersection（CountUp 入视口才动画）、useOnlineStatus（离线提示条）
    ├── lib/                   # reminderNotify.ts、coords.ts、echarts.ts、chartTheme.ts 及其测试
    ├── pages/                 # 15 个页面目录（较重的 Planner/Trip 已拆出 components/ 子目录），见第 4 节
    ├── store/                 # 11 个文件（5 个 store + 测试）
    ├── styles/                # 固定 @import 顺序的样式入口（见 §2.1）
    ├── test/setup.ts          # node 环境补内存 Storage，供 zustand persist 使用
    └── types/                 # 7 个类型文件：trip / agent / expense / memo / assistant / knowledge / review
```

### 2.1 样式结构（2026-11-23 收敛）

`styles/global.css` 只做入口与 reset，按**固定顺序**导入（改顺序等于改级联）：

| 层 | 文件 | 内容 |
| --- | --- | --- |
| 基础 | `tokens.css` | `:root` 令牌、暗色覆盖、antd 令牌对齐 |
| 外壳 | `shell.css` | 侧栏 / 底部标签栏 / 页面框架 |
| 原子 | `buttons.css`、`cards.css`、`status.css` | 按钮、卡片、状态徽标 |
| 共享布局 | `shared.css` | 跨页页头、390px 防溢出契约、antd 覆盖 |
| 跨页组件 | `components.css` | ChatPanel / PlanDiff / AgentActivityPanel / FootprintMap / Skeleton / Toast / Citation / SwipeRow / ReminderBell / memo |
| 动效 | `animations.css` | PageTransition、通用入场、`prefers-reduced-motion` |
| 无独立 CSS 的页面 | `pages-travel.css`、`pages-expenses.css` | Travel / Expenses 私有规则 |

页面级规则放在 `src/pages/<Page>/<page>.css`，由页面组件自己 import（随路由 chunk 懒加载）：
Home / Planner / Trip / Footprint / Reviews / Knowledge / Login / Profile / Workbench / Assistant。
组件级规则放在 `src/components/<Name>/*.css`。三处都不再回写 `global.css`；新增窄屏规则要写进 `shared.css` 的 390px 段
（否则会被更早的 `@media (max-width: 900px)` 块抢先覆盖）。

---

## 3. 路由表

唯一来源是 `src/App.tsx` 的 `<Routes>`，共 **20 条 `<Route>`**（顺序与源码一致）；下表只列**有效入口**，退役模块的兼容重定向见下方说明。

| # | path | 组件 | 受保护 | 备注 |
| --- | --- | --- | --- | --- |
| 1 | `/login` | `pages/Login` | 否 | 登录/注册 Tab 同页 |
| 2 | `/` | `pages/Home` | ✅ | 总览首页 |
| 3 | `/planner` | `pages/Planner` | ✅ | 结构化规划表单，走 POST 版 SSE |
| 4 | `/trip/:id` | `pages/Trip` | ✅ | 行程详情：地图 + 时间轴 + 聊天 + 预算抽屉 + 状态操作 |
| 5 | `/trip/:id/review` | `pages/TripReview` | ✅ | 单次旅行回顾：完成率/计划与实际/到访/路线/照片/事件 |
| 6 | `/review` | `pages/Reviews` | ✅ | 旅行回顾列表（按阶段排序） |
| 7 | `/expenses` | `pages/Expenses` | ✅ | 跨行程支出总览 |
| 8 | `/memos` | `pages/Memos` | ✅ | 备忘，支持左滑操作 |
| 9 | `/schedule` | `<Navigate to="/travel?tab=schedule" replace />` | — | 旧链接兼容重定向，无独立页面 |
| 10 | `/assistant` | `pages/Assistant` | ✅ | 统一对话入口 |
| 11 | `/travel` | `pages/Travel` | ✅ | 旅行规划主页（Tab：行程/日程/足迹） |
| 12 | `/footprint` | `<Navigate to="/travel?tab=footprint" replace />` | — | 旧链接兼容重定向，无独立页面 |
| 13 | `/knowledge` | `pages/Knowledge` | ✅ | 旅行知识库：录入 / 检索 / 版本 / 回滚 |
| 14 | `/settings/diagnostics` | `pages/Workbench` | ✅ | Agent 运行观察：run / 任务 DAG / 工具调用（运行诊断已迁入「设置 > 诊断」） |
| 15 | `/profile` | `pages/Profile` | ✅ | 个人中心、系统状态、退出登录 |
| 16 | `*` | `<Navigate to="/" replace />` | — | 通配兜底，未知路径回首页 |

> **退役模块重定向（保留但不入上表）**：`/office`、`/learning`、`/health` 均指向 `/`，避免旧书签 404。
> `/workbench` 旧链接重定向到 `/settings/diagnostics`。
> 它们不是功能入口；对应页面目录、API client、类型与首页请求已删除。
> 新增路由要放在 `*` 之前——react-router v7 按「评分最优」匹配，放后面可能永远命中不到。
> `/ui-demo` 组件预览页已在 2026-11-23 下线（评审要求收缩与旅行无关的复杂度）；自研基础组件改由单测与页面 e2e 覆盖。

**重定向的意义**：日程与足迹曾是两个独立侧栏入口，现分别并入 `/travel?tab=schedule` 与
`/travel?tab=footprint`（`pages/Travel/index.tsx` 用 `useSearchParams` 读 `tab`，非法值回落 `trips`）。
旧书签、微信里发过的链接继续可用。

**守卫与容错链**（`AnimatedRoutes` 的嵌套顺序）：

```tsx
<ErrorBoundary key={location.pathname}>          {/* 按 pathname 重置，异常不锁死整站 */}
  <Suspense fallback={<RouteLoading />}>          {/* 懒加载期间居中 Spin */}
    <PageTransition variant="slide" duration={280}>
      <Routes location={location}> … </Routes>
    </PageTransition>
  </Suspense>
</ErrorBoundary>
```

`Protected` 的判断顺序是 `authChecked` → `user`：恢复未完成时只渲染 `Spin`、**不跳转**，
否则刷新瞬间会被误判成未登录而弹回登录页。

---

## 4. 页面清单

`src/pages/` 共 **15 个页面目录**；`App.tsx` 有 **19 条 `<Route>`**，其中 **13 条渲染页面组件**
（`/login` + 12 个受保护页：Home、Planner、Trip、TripReview、Reviews、Expenses、Memos、Assistant、Travel、Knowledge、Workbench、Profile）、
2 条有效重定向（`/schedule`、`/footprint`）、3 条退役重定向（`/office`、`/learning`、`/health`）与 1 条通配兜底。
`Schedule` / `Footprint` 仍有目录与组件，但只作为 `Travel` 的 Tab 内容渲染，不再有独立路由。

### 4.1 Login（`/login`）

登录/注册两个 Tab，左侧三条产品卖点；成功后 `setAuth` 再跳首页。
API：`loginApi`、`registerApi`。组件：antd `Tabs/Form/Input/Button/Typography`。

### 4.2 Home（`/`，`AppShell active="home"`）

一屏总览 —— 问候语与日期、今日日程、未完成备忘（可勾选）、支出概览、足迹卡片、最近 Agent 运行。
顶部输入框把一句话带到 `/assistant?q=…` 自动发送。
API：`getTripsMap`、`getExpenseOverview`、`getMemos`/`toggleMemo`、`listSchedule`、`getRuns`、`getReminders`。
**P0 起不再请求**健康摘要、日报、会议纪要、学习产物。
组件：`AppShell`、`CityDrawer`、`VoiceButton`、`CountUp`、`Skeleton`；足迹地图用
`lazy(() => import('../../components/FootprintMap'))` 二次懒加载，统计纯函数来自 `FootprintMap/footprintData.ts`。
**足迹口径（P1）**：`visitedPoints()` 只保留后端 `visited === true`（依据是 `visitedAt` 实际打卡）的行程；
未来/计划行程不算「去过」，缺失 `visited` 字段一律按未到访处理。

### 4.3 Planner（`/planner`）

**三步流程**（§8.3）：① 基本信息（目的地、日期或天数、同行人数，必填）→ ② 偏好与约束（预算、节奏、兴趣、
攻略正文、不可修改项、特别要求，可空）→ ③ 确认需求（只读结构化摘要 + 冲突 + 待核实字段）。
两种模式：`guides`（粘贴参考攻略正文整合）与 `scratch`（从零规划）；攻略模式在第 2→3 步之间要求至少一篇正文。
API：不直接调 REST，全部经 `useAgentStore.send({ transport: 'post' })`。
布局：桌面右侧吸顶需求摘要，移动端（`useIsMobile`）底部固定「上一步 / 步骤 x/3 / 继续·生成计划」；
`?prompt=` 进入时仍先跑 `parsePrompt` 回填，第 3 步会列出低置信字段与「方案会替用户做的假设」。

**草稿**：`planDraft.ts`（纯逻辑，可单测）负责 localStorage 读写与校验，
key `travel:planner-draft:v1` 带版本号；空白表单不产生草稿；进入页面**只提示不自动恢复**
（`恢复上次未完成规划 / 忽略`），`?prompt=` 场景以解析结果为准；提交成功后清草稿。
保存失败（隐私模式/配额满）会明确提示，不静默丢弃。

组件：`ChatPanel`、`PlanDiff`；纯逻辑在同目录 `planForm.ts` —— `compilePlanPrompt`（表单 → 中文需求文本）、
`compilePlanSummary`（聊天气泡短摘要）、`describeRequirementRows`（第 3 步摘要与右侧摘要同一口径）、
`clampPlanMessage`（兜底截断）。页面主体为 `index.tsx`（状态、副作用、提交门禁），
步骤视图与页脚拆在 `Planner/components/`：`PlanStepShell`、`PlanStepBasics`、`PlanStepPreferences`、
`PlanReview`、`PlannerFooter`、`WorkspacePane`、`types.ts`。
样式在 `Planner/planner.css`（`plan3-` 前缀，**不进 global.css**）。

### 4.4 Trip（`/trip/:id`）

行程详情主战场 —— 桌面：时间轴主栏 + 地图/AI 侧栏；移动：`行程 / 地图 / AI` 分段视图（默认行程）。
顶部按阶段收敛一级动作（行中=记账，行前=预算分析），其余进「更多」菜单。

**行中执行流程（§7.3）**：
- 完成一项后 `lib/tripFocus.ts` 的 `nextFocusAfter` 算出下一项，页面切到它所在 Day 并 `scrollToItem` 滚过去；
  同时弹 `UndoBar`（8s 倒计时）提供短时撤销 —— 已同步的变更把状态写回上一个值（`PUT /itinerary/{id}/status` 是绝对值语义，天然幂等），
  仍在队列里的变更直接出队。撤销同时走服务端，不是只改本地。
- **离线待同步**：离线（或请求发不出去）时不报错，变更进 `store/statusQueueStore`，
  时间轴卡片显示「待同步」，页头显示「N 项待同步」；恢复在线后由 `OfflineBanner` 的 `usePendingSync` 逐条重放，
  并显示逐项结果（成功/失败+原因+重试）。服务端明确拒绝（带状态码）不入队，直接提示原因。
- **侧栏 keep-alive**：地图与 AI 面板一次挂载、只切可见性（`data-active`），切走再切回不会重新初始化地图、不丢缩放；
  切回可见时调用 `MapProvider.resize?.()` 校正画布（隐藏期间窗口可能变过尺寸）。
API：`getTrip`/`getItinerary`/`getBudgetExecution`、`undoTrip`、`updateItemStatus`、`getTripReview`/`getTripEvents`（见 `api/trip.ts`）。
组件：`MapView`、`Timeline`、`ChatPanel`、`PlanDiff`、`BudgetChart`、`BudgetExecution`、`ExpensePanel`。
P1 交互：`ItineraryCard` 提供「开始 / 完成 / 跳过 / 重置」状态按钮（`statusBusy` 防连点，`status/startedAt/completedAt/visitedAt` 展示实际时间）；
顶部「旅行回顾」按钮进入 `/trip/{id}/review`。
`BudgetExecution` 展示预算 / 计划 / 实际 / 偏差，并新增**预算使用率**（`executionRate = actual / budget`，后端保留 4 位小数，
无预算时为 `null`，前端 ×100 显示百分比）；`daily` 在 `types/expense.ts` 里是**必填**字段（旧后端缺失时组件按空数组兜底，不会崩）。

### 4.5 Expenses（`/expenses`）

跨行程支出总览，按分类/支付方式聚合，进度条对照预算。API：`getExpenseOverview`。
组件：`CountUp`、`Skeleton`；颜色常量在 `types/expense.ts`（`CATEGORY_COLORS`/`PAYMENT_COLORS`）。

> 行程页里的**账单列表**是 `components/ExpensePanel`（按行程分页，默认 20 条 + 底部 `PaginationBar`），
> 与本节的总览聚合接口不是一回事（见 6.5）。

### 4.6 Memos（`/memos`）

新增、勾选完成、删除备忘，左滑露出操作按钮。勾选做并发保护 —— `pending` Set 挡住连点，
因为 toggle 是取反语义，连点两次会互相抵消。API：`getMemosPage`/`addMemo`/`toggleMemo`/`deleteMemo`。
列表分页：默认 20 条，底部「共 N 条 · 第 k 页」+ 上一页 / 下一页（`PaginationBar`，见 6.5）；后端没返回 `pagination` 时降级为「单页、无更多」，两个按钮禁用。
组件：`SwipeRow`、`Skeleton`、`AppShell`。P1 起备忘可带可空 `tripId`/`itineraryItemId`/`travelDay` 归属旅行（当前 UI 尚未提供关联入口）。

### 4.7 Schedule（`/travel?tab=schedule`）

日程增删改查、按时辰与重复规则（`NONE`/…）设置；行内 toggle 同样做并发保护。
API：`listSchedule`/`addSchedule`/`toggleSchedule`/`deleteSchedule`。组件：`DatePicker`/`TimePicker`/`Select`；
被 `Travel` 复用，`AppShell` 检测到已在外壳内会直接返回 children。
P1 起日程可带可空 `tripId`/`itineraryItemId`/`travelDay`，确认后的 Agent 同步会按 `sourceKey` 幂等写入。

### 4.8 Footprint（`/travel?tab=footprint`）

全屏足迹地图 + 行程列表 + 按时间回放；统计由 `footprintData.ts` 在本地算。API：`getTripsMap`。
**只统计 `visited === true` 的行程**：计划地点、计划日期与「当前行程」都不计入「去过」。
组件：`FootprintMap`（maplibre 球体投影 + 高德栅格底图，运行时探测 `setProjection`，旧版本退回平面 3D；
底图由 `VITE_FOOTPRINT_BASEMAP` 控制，默认 `amap`，设 `osm` 可退回原 OSM/OpenFreeMap）。

### 4.9 Assistant（`/assistant`）

统一对话入口，**URL 参数即状态**：`?q=…` 从首页/城市抽屉带问题跳转（`startedRef` 保证只自动发一次）；
`?planId=…` 刷新后用 `getPendingPlan` 找回待确认方案；`pendingConfirm` 出现时把 `planId` 写回 URL（`replace: true`）。
API：`getPendingPlan`，发送走 `useAgentStore.send`。组件：`ChatPanel`、`PlanDiff`。

### 4.10 Travel（`/travel`）

三个 Tab —— `trips` 行程列表、`schedule` 日程、`footprint` 足迹；行程卡片支持状态流转
（进行中/已完成/取消）与删除（`Popconfirm`）。API：`listTrips`/`deleteTrip`/`updateTripStatus`。
组件：`VoiceButton`、`AppShell`，内嵌 `Schedule`、`Footprint`。

### 4.11 Knowledge（`/knowledge`）

旅行知识库管理：录入自定义攻略文档、检索命中片段、查看版本列表与版本详情、回滚到指定版本。
API：`addKnowledge`、`searchKnowledge`、`listKnowledgeVersions`、`getKnowledgeVersion`、`rollbackKnowledge`；
类型在 `types/knowledge.ts`。组件：antd `Input/InputNumber/Modal/Popconfirm/Tag` + `AppShell`。
该页只做旅行域 RAG 文档管理，**不提供学习产物生成**。

### 4.12 Reviews（`/review`）

旅行回顾列表：按阶段（进行中 / 已结束 / 待出发 / 已取消）排序，每行展示行程名、目的地、日期与阶段，
「查看回顾」进入 `/trip/{id}/review`。API：`listTrips`（复用行程列表，回顾数据按需加载）。组件：`AppShell`、antd `Empty/Spin/Button`。

### 4.13 TripReview（`/trip/:id/review`）

单次旅行回顾页：完成率进度、计划/实际花费与偏差、计划地点 vs 实际到访、`actualRoute`、照片墙（票据鉴权）、
事件流（天气/拥堵/闭馆/打卡/Agent 调整/手动修改）、修改历史与 `aiSummary`。
API：`getTripReview`，照片原图 `photoSrc` + `useAccessTicket('media-read')`；类型在 `types/review.ts`。
组件：`AppShell`、antd `Progress/Tag/Alert/Empty/Spin`；页面强调 **visited 只认 `visitedAt`**，计划地点不冒充到访。

### 4.14 Workbench（`/workbench`）

**Agent 运行观察页**（P0 改造，不再是办公/学习工作台）：左侧最近运行列表，右侧选中 run 的任务 DAG
（依赖边、尝试次数、`CONFLICT` 行）与工具调用摘要（脱敏 digest，不渲染原始参数）。
API：`getRuns`、`getRunDetail`；组件：`AppShell` + 列表/详情布局，含运行状态徽标与错误重试。

### 4.15 Profile（`/profile`）

用户信息、行程统计、系统状态（模型/数据源可用性）、退出登录。
API：`systemStatus`、`signOut`、`useTripStore.loadTrips`。组件：antd `Tag`；
退出必须走 `signOut()`，不要直接调 store 的 `logout()`。

---

## 5. 状态管理

zustand v4，每个 store 一个文件一个职责，组件按 `useXStore((s) => s.field)` 单字段订阅。

| store | 文件 | 持久化 | 职责 |
| --- | --- | --- | --- |
| auth | `store/authStore.ts` | ❌ 纯内存 | 登录态（user/token/authChecked） |
| agent | `store/agentStore.ts` | ✅ sessionStorage（仅 `conversationId`） | 对话、SSE 生命周期、待确认方案 |
| trip | `store/tripStore.ts` | ❌ | 行程列表与当前行程/条目 |
| ui | `store/uiStore.ts` | ❌ | 当前 Day、选中元素、提醒抽屉开关 |
| theme | `store/themeStore.ts` | ✅ localStorage `travel-agent-theme` | 亮/暗主题 |
| statusQueue | `store/statusQueueStore.ts` | ✅ localStorage `travel:status-queue:v1` | 行程项状态的离线待同步队列与逐项同步结果 |

### 5.1 authStore

`token` 仅内存（只兼容极少数调用方，登录凭证在 httpOnly Cookie 里）；`user` 为 `UserInfo = {id, username, nickname}`；
`authChecked` 表示启动恢复是否完成，`Protected` 据此决定“等待”还是“跳转”。
action：`setAuth(token, user)`（登录成功后写入）、`setUser(user)`（`meApi` 恢复时只补 user）、
`markAuthChecked()`（恢复结束，无论成败）、`logout()`（清 token 与 user，**不动 authChecked**）。

### 5.2 agentStore（最复杂的 store）

| 字段 / 方法 | 说明 |
| --- | --- |
| `messages` | `ChatMessage[]` 聊天气泡 |
| `streaming` | 是否在流式接收；为 true 时输入框禁用 |
| `stage` | 阶段文案，由 `STAGE_TEXT` 映射事件名 |
| `pendingConfirm` | `status === 'NEED_CONFIRMATION'` 时挂起，驱动 `PlanDiff` |
| `lastFocus` | 地图/时间轴要飞到的目标（`AgentFocus`） |
| `committedTripId` | 确认后新建行程的 id，供跳转 |
| `conversationId` | 会话记忆标识（UUID），后端据此做指代消解 |
| `send(payload)` | 发起一轮对话，按 `transport` 选 GET/POST SSE |
| `confirm()` / `cancel()` | `POST /agent/confirm` / `POST /agent/cancel` |
| `restorePending(result)` | 刷新后按 planId 取回的方案重新挂起弹窗 |
| `reset()` / `newConversation()` / `clearCommitted()` | 只清界面（保留 id） / 清空并换 id（同时清服务端历史） / 跳转后清 committedTripId |

**两个内部序号**是正确性的关键：`closeFn`（当前 SSE 的关闭函数）与 `streamSeq`（流式请求序号）。
因为 `getAccessTicket()` 是异步的，`send()` 返回时连接可能还没建立。若期间用户点了「新对话」或发了新消息，
旧回调会把过期结果写回 store。解法：每次发送 `const seq = ++streamSeq`，回调里 `seq !== streamSeq` 直接丢弃；
`.then(close => …)` 时若序号过期则**立刻 `close()`** 掉这条迟到的连接。

### 5.3 tripStore / uiStore / themeStore

| store | 字段 | 方法 |
| --- | --- | --- |
| trip | `trips`、`tripsLoading`、`currentTrip`、`items` | `loadTrips()`、`loadTrip(id)`、`loadItinerary(id)`、`clearCurrent()` |
| ui | `selectedDay`(默认 1)、`selectedElement`、`remindersOpen` | `setSelectedDay()`、`setSelectedElement()`、`setRemindersOpen()` |
| theme | `mode`(默认跟随系统) | `setMode()`、`toggle()`；模块级 `applyTheme()` 写 `<html data-theme>` |

`selectedElement`（`{type, id, title}`）是「多模态上下文」：`Timeline` 里点一个条目，`ChatPanel` 发送时带上它，
后端即可理解“把**这个**换成别的”这类指代。

### 5.4 statusQueue（离线待同步队列，§7.3 第 4 条 / §10.3）

| 层 | 文件 | 职责 |
| --- | --- | --- |
| 纯逻辑 | `lib/statusQueue.ts` | `PendingStatusChange` 类型、`requestId` 生成、按 `itemId` 合并（last write wins）、`overlayStatuses` 把待同步状态叠加到服务端数据上、localStorage 容错读写（坏 JSON/字段非法逐条丢弃，绝不抛错） |
| store | `store/statusQueueStore.ts` | `entries`/`syncing`/`lastResults`/`hydrated`；`hydrate`/`enqueue`/`drop`/`dropByItem`/`clear`/`flush`/`clearResults`；`flush` 顺序发送、成功即出队、失败保留并写 `lastError` |
| hook | `usePendingSync()` | 在 `OfflineBanner` 挂载：`hydrate` + 在线时 `flush`，并监听 `window online` 自动重放 |

`flush` 的错误分类决定「继续还是中止」：命中 `ERR_NETWORK/ECONNABORTED/ETIMEDOUT/ERR_CANCELED` 或拿不到
1–599 的 HTTP 状态码 = 网络仍不可用 → **中止后续**（剩余条目原样保留）；服务端明确回应（400/404/409/5xx）= 业务拒绝
→ 该条保留待重试、继续处理下一条。`flush` 不 reject，结果只体现在 `lastResults` 与条目的 `lastError` 上；
并发调用由 `syncing` 挡住。`lastResults` 不持久化（刷新即空），条目的 `lastError` 持久化。

---

## 6. API 层

### 6.1 client.ts：拦截器与 401 分流

```ts
export const http = axios.create({ baseURL: '/api', timeout: 30000 });

http.interceptors.request.use((config) => {
  const token = useAuthStore.getState().token;      // 通常为 null，凭证靠 Cookie
  if (token) config.headers.Authorization = `Bearer ${token}`;
  return config;
});
http.interceptors.response.use(unwrapApiResponse, handleResponseError);
```

**成功分支 `unwrapApiResponse`**：后端统一返回 `{code, message, data}`。`code === 0` 直接返回 `data`
（所以 `request<T>()` 的泛型是 data 类型）；`code !== 0` 转成 `Error(message)` 抛出；无 `code` 字段则原样返回。

**错误分支 `handleResponseError` —— 401 必须分流**：

| 条件 | 行为 | 原因 |
| --- | --- | --- |
| 401 且 URL 含 `/auth/login`、`/auth/register`、`/auth/me` | **不登出、不跳转**，只 reject | 密码错误就是 401；`/me` 是启动探活，天生会 401 |
| 401 且当前 pathname 已是 `/login` | **不跳转** | 否则形成 401 → 跳 `/login` → 又 401 的刷新循环 |
| 其余 401 | `logout()` + `window.location.href = '/login'` | 会话过期，清本地态硬跳 |
| 其他错误 | 只 reject `new Error(message)` | 由调用方 `message.error` 展示 |

### 6.2 httpOnly Cookie 登录态恢复（主线一）

**核心约定：前端不持久化 token。** 登录凭证是服务端下发的 httpOnly Cookie（`SameSite=Lax`，前端 JS 读不到），
所以刷新后内存里的 `user` 一定为空，必须重新问后端「我是谁」。

```
① 页面加载，App 挂载
② useEffect → meApi() = GET /api/auth/me   （同源请求自动携带 httpOnly Cookie）
        │
   ┌────┴──────────────────────────────┐
   │ 200 + {id,username,nickname}      │ 401（未登录/过期）
   ▼                                   ▼
③ setUser({...})                   handleResponseError：URL 含 '/auth/me' → 命中白名单，
        │                          不 logout、不跳转，只 reject；App 里 .catch(() => {}) 静默吞掉
        ▼                                   │
④ finally → markAuthChecked()  ◄────────────┘
        │
⑤ Protected 重渲染：authChecked=true
   ├─ user 存在 → 渲染页面      └─ user 为空 → <Navigate to="/login" replace />
```

- **第 ③ 步的 `.catch(() => {})` 不是偷懒**：未登录是正常状态，不应产生全局错误提示。
- **白名单与 `authChecked` 缺一不可**：白名单让 401 不硬跳转，`authChecked` 让 `Protected` 在结果未知时不跳转。
  少了任一，刷新受保护页面都会闪一下登录页。
- **登出必须用 `signOut()`**：先 `POST /api/auth/logout` 清服务端 Cookie，再 `logout()` 清本地态；
  服务端失败也照常本地登出（Cookie 可能早已失效）。导航由调用方负责。
- `authStore.logout()` 里会调用 `clearAccessTicketCache()`：票据按 `authKey|purpose` 分桶缓存，登出必须整体清掉，
  否则换账号/重新登录后仍可能命中上一会话的票据。

### 6.3 SSE 客户端：消费 `/api/agent/stream`（主线二）

Agent 的执行过程通过 SSE 推给前端，前端再把「过程」变成阶段文案与地图动作。
事件名清单见 `api/agent.ts` 的 `EVENT_NAMES`（顺序即 `addEventListener` 注册顺序）：
`start`、`intent`、`planning`、`tool_call`、`tool_result`、`rag_result`、`plan_update`、
`focus`、`confirmation`、`complete`、`error`。

| 事件 | data 载荷 | 后端何时发 | 前端反应 |
| --- | --- | --- | --- |
| `start` | `{sessionId}` | 会话开始 | stage「已连接 Agent…」 |
| `intent` | `{intent, target}` | 意图识别完成 | stage「意图识别：PLAN_TRIP」 |
| `planning` | `{message}` | 进入规划/重规划 | stage「正在规划行程…」 |
| `tool_call` / `tool_result` | `{tool, args}` / `{tool, result}` | 工具调用前后（`ToolInvoker`） | stage「调用工具 X…」/「工具 X 执行完成」；`search_city_online` 特判为联网查询文案 |
| `rag_result` | `{sources}` | 知识库检索后 | stage「检索旅游知识库…」 |
| `plan_update` | `{diff, budget}` | 方案预览产出 | stage「生成方案对比…」 |
| `focus` | `AgentFocus` | 需要定位时 | **除 stage 外还写 `lastFocus`**，驱动地图 `flyTo` 与时间轴滚动 |
| `confirmation` | `ConfirmationRequest` | 需要用户确认 | stage「等待你的确认…」 |
| `complete` | `AgentResult` | **每轮必有，最后一条** | 终止流、追加 assistant 气泡、按 status 设 `pendingConfirm`/`committedTripId` |
| `error` | `{message}` | 服务端主动报错 | 只 `onError` 提示，**不终止**（后端随后还会发 `complete`） |

**`complete` 携带的 `AgentResult`（`types/agent.ts`）是渲染核心**：`status`（`COMPLETED` / `NEED_CONFIRMATION` / `FAILED`）、`focus`、`diff`（`{changes[], budgetBefore, budgetAfter}`）、`budget`、`confirmation`（`{planId, summary, riskLevel}`）、`warnings`。前端几乎不按 intent 分支渲染，只按 **`status` + `diff` + `confirmation` + `focus`** 决定 UI —— 这是后端的明确契约。

**两条传输通道**，同一套 handlers：

| | `streamAgentChat`（GET，`ChatPanel` 常规聊天） | `streamAgentChatPost`（POST，`Planner` 带攻略正文） |
| --- | --- | --- |
| 实现 | `new EventSource('/api/agent/stream?' + qs)` | `fetch(..., {credentials:'same-origin'})` + `ReadableStream` 手工解析帧 |
| 认证 | 查询串 `ticket=…`（票据） | 同源 Cookie，无需票据 |
| 承载量 | 消息在 URL 里，约 ≤500 字 | 请求体，约 ≤2 万字 |
| 关闭 | `es.close()` | `AbortController.abort()` |

POST 版按 `\n\n` 切帧、识别 `event:` / `data:` 行、`data:` 多行用 `\n` 拼接，语义与 GET 版逐条对齐；
两版共用同一个 `finish()` 包装，用 `finished` 标志保证 `onComplete` / `onError` **只回调一次**（GET 版 `es.close()`，
POST 版 `controller.abort()`）。

**一个曾经踩过的坑**：旧实现只在 `readyState === CLOSED` 时才回调 error。服务端 180s 超时或网络抖动时，浏览器只会触发 `onerror`，于是既不 `complete` 也不 `error`，`streaming` 永远停在 `true`、输入框永久禁用。现在 `es.onerror` **无条件** `finish()` 并提示「与 Agent 的连接已中断，请重试」；POST 版同理，流正常结束却**没收到 `complete`** 也按中断处理。

**前端消费链路**：

```
ChatPanel 输入 → doSend()
  → useAgentStore.send({ message, tripId?, currentDay?, selectedElement?, conversationId?, transport? })
    → 追加 user 气泡；streaming=true；stage='连接 Agent…'；pendingConfirm=null
    → streamAgentChat(Post)({...}, handlers)
       ├─ onEvent(name, data) → STAGE_TEXT[name](data) 写 stage；name==='focus' 时写 lastFocus
       ├─ onComplete(result)  → 追加 assistant 气泡；streaming=false
       │                        NEED_CONFIRMATION → pendingConfirm=result → <PlanDiff> 弹出
       │                        COMPLETED && result.tripId && 无既有 tripId → committedTripId
       └─ onError(text)       → 追加「⚠ 文本」气泡；streaming=false
```

**确认闭环**：`PlanDiff` 展示 `diff.changes`（ADD/REMOVE/MODIFY 三色行 + 预算前后对比）与
`riskLevel === 'HIGH'` 的风险提示；点「确认修改」→ `store.confirm()` → `POST /api/agent/confirm` →
后端单事务落地 → 返回新的 `AgentResult`；取消走 `/agent/cancel`。
**刷新不丢**：`Assistant` 把 planId 写进 URL，重进时用 `getPendingPlan` 取回。

### 6.4 ticket.ts：为什么需要短期票据

`EventSource` 与 `<img>` 都**无法自定义请求头**，只能把凭证塞进 URL。而 URL 会进访问日志、浏览器历史、Referer，
所以绝不能放登录 JWT。方案是换一张**短期只读票据**（后端 `AccessTicketService`，默认 **300 秒**）：

```ts
export type AccessTicketPurpose = 'agent-stream' | 'media-read';
export async function getAccessTicket(purpose?: AccessTicketPurpose): Promise<string>; // 缺省 'agent-stream'
export function useAccessTicket(purpose?: AccessTicketPurpose): string | null;          // 组件用，随登录态自动取
export function clearAccessTicketCache(): void;                                        // 登出/登录态变化时清空
```

**票据按用途签发，且用途与路径必须匹配**（后端强校验，用错一律 401）：`agent-stream` → `GET /api/agent/stream`（GET 版 SSE），
`media-read` → `GET /api/photos/{id}/raw`（照片墙、`CityDrawer`）。前端因此**按 `purpose` 分桶缓存**：`cached` / `inflight` 的键是
`authKey|purpose`，两种票据互不覆盖、也不会互相串用。

四个易被忽略的设计点：① **缓存键是 `authKey = user.id + '|' + token`（再拼用途）**，换账号必须重新签票；
② **提前 30 秒续期**（`REFRESH_MARGIN_MS`），避免踩在过期边界上；
③ **并发合并在途请求**（`inflight` Promise），否则照片墙每张图都会各签一次票据；
④ **登出要清缓存**（`authStore.logout()` → `clearAccessTicketCache()`），否则重新登录后可能命中上一会话的票据。
使用方：`api/agent.ts`（GET 版 SSE，`agent-stream`）、`api/photo.ts` 的 `photoSrc(photo, ticket)`、`CityDrawer` 的照片墙（`media-read`）。

### 6.5 列表分页：`getPage` + `PaginationBar`

后端列表接口新增可选 `limit`（默认 `20`、上限 `200`）/ `offset`（默认 `0`），并在响应顶层带可选
`pagination`（`{total, limit, offset, hasMore}`），`data` 仍是数组。前端分两层接：

**API 层**（`api/client.ts`）：

- `getPage<T>(path, params)` → `PageResult<T> = { items, pagination }`；`pagination` 缺失或格式非法时为 `null`（`readPagination`
  归一化，`hasMore` 缺失时用 `offset + 本页条数 < total` 推断）。
- `DEFAULT_PAGE_SIZE = 20`、`MAX_PAGE_SIZE = 200`；`compactParams` 剔除 `undefined` / `null`，避免拼出 `?offset=undefined`。

**UI 层**（`components/PaginationBar`）：文案「共 N 条 · 第 k 页」+ 上一页 / 下一页；`paged=false`（后端没返回 `pagination`）时
文案降级为「共 N 条 · 单页」、两个按钮都禁用——**老后端不会让页面报错**。翻页请求在途只禁用按钮、不动列表，
避免翻页时整块骨架屏导致滚动位置丢失。

已接入的列表：备忘页（`pages/Memos`，`getMemosPage`）与行程页记账面板（`components/ExpensePanel`，`getExpensesPage`），
都是 `limit=20` + `offset` 翻页。

---

## 7. 核心组件

`src/components/` 的核心组件如下，地图与足迹辅助文件见 7.2。

### 7.1 组件清单

| 组件 | 路径 | props | 职责 |
| --- | --- | --- | --- |
| `AppShell` | `AppShell.tsx` | `active: NavModule`, `children` | 应用外壳：分组侧栏（备忘角标、提醒铃铛、用户区、主题切换）、内容区、窄屏底部标签栏；已在壳内（`InsideShell` context）时**只渲染 children** |
| `OfflineBanner` | `OfflineBanner/index.tsx` | 无 | 断网时置顶显示「离线：显示缓存数据」+「N 项待同步」，恢复在线自动消失（`useOnlineStatus` + 原生 `online`/`offline` 事件）；**同时是待同步队列的全局出口**：`usePendingSync()` 自动重放，显示「正在同步 / N 项同步失败（重试）/ N 项待同步（立即同步）/ N 项离线记录已同步」；可手动关闭，恢复在线后重置关闭状态（见 11.3、5.4） |
| `ChatPanel` | `ChatPanel/index.tsx` | `tripId?`, `placeholder?` | 聊天面板：消息列表 + 语音输入 + 发送；自动滚到底；带 `tripId`/`currentDay`/`selectedElement` 上下文 |
| `MapView` | `MapView/index.tsx` | `items`, `focus`, `focusDay`, `routeLegs?`, `active?=true` | 地图容器与引擎切换（见第 8 节），含底图失败降级提示与 3D 巡航按钮；`active=false` 不卸载地图，重新可见时 `resize()`（侧栏 keep-alive） |
| `Timeline` | `Timeline/index.tsx` | `trip`, `items`, `focus`, `routeLegs?`, `onItemStatusChange?`, `statusBusyId?`, `pendingSyncIds?` | 时间轴：按 Day 分组，点击选中元素写入 `uiStore`；收到 `focus` 自动切 Day 并滚动；顶部「下一项」卡片给主动作；`pendingSyncIds` 命中的条目显示「待同步」 |
| `ItineraryCard` | `ItineraryCard/index.tsx` | `item`, `selected?`, `onClick?`, `onStatusChange?`, `statusBusy?`, `pendingSync?` | 行程条目卡片：类型徽标 + 标题 + 花费 + 标签 + 简介；P1 状态徽标与「开始/完成/跳过/重置」按钮，展示实际开始/完成时间；`pendingSync` 时状态旁标「待同步」并给卡片 `data-pending` |
| `PlanDiff` | `PlanDiff/index.tsx` | `open`, `result`, `onConfirm`, `onCancel`, `loading?` | 方案确认弹窗：ADD/REMOVE/MODIFY diff 行、预算前后、HIGH 风险提示；P1 起展示 `result.citations`（知识依据），空数组不渲染 |
| `BudgetChart` | `BudgetChart/index.tsx` | `items`, `budget?` | ECharts 双图：按类型饼图 + 按天柱图 |
| `BudgetExecution` | `BudgetExecution/index.tsx` | `data: BudgetExecution \| null` | 预算执行摘要：预算/计划/实际/偏差四宫格 + 分类对账（`data` 为空不渲染） |
| `ExpensePanel` | `ExpensePanel/index.tsx` | `tripId`, `budget`, `planned` | 行程记账面板：增删支出、分类统计图、预算对比进度 |
| `FootprintMap` | `FootprintMap/index.tsx` | 见 7.3 | 全屏足迹地图（球体/平面 3D、城市标记、按时间回放） |
| `ReminderBell` | `ReminderBell/index.tsx` | 无 | 侧栏铃铛：60s 轮询 `/reminders`、抽屉列表、「知道了」忽略、后台时发系统通知 |
| `VoiceButton` | `VoiceButton/index.tsx` | `onText(text: string)` | Web Speech API 语音输入；**不支持的浏览器直接 `return null`** |
| `DraggableList` | `DraggableList/index.tsx` | `<T>{ items, itemKey, renderItem, onReorder, rowClassName? }` | 原生 HTML5 拖拽排序，松开后按新顺序回调 |
| `SwipeRow` | `SwipeRow/index.tsx` | `children`, `rightActions?`, `leftActions?`, `threshold?=80`, `maxSlide?=160`, `resistance?=0.5`, `onActionClose?`, `className?` | 左右滑动露出操作按钮，带阻尼回弹与吸附 |
| `CityDrawer` | `CityDrawer/index.tsx` | `city`, `points`, `spendByTrip`, `onClose`, `onOpenTrip`, `onAskAi` | 城市足迹详情抽屉：到访记录、预算 vs 实际、本地拼装的总结、照片墙（带票据）、一键问 AI |
| `Toast` | `Toast/index.tsx` | `ToastContainer` 无 props | 全局轻提示单例：`toast.success/warning/error/info/ai(content, title?)` |
| `UndoBar` | `UndoBar/index.tsx` | `token`, `message`, `detail?`, `actionLabel?='撤销'`, `durationMs?=8000`, `onAction`, `onExpire` | 短时撤销条（§7.3 第 3 条）：底部浮动、带倒计时进度条与剩余秒数，`key={token}` 重挂即重新计时；点撤销/关闭会先停表，避免超时回调重复触发 |
| `Skeleton` | `Skeleton/index.tsx` | `{ loading, children, className? }` | 骨架屏：`Skeleton.Text/Paragraph/Circle/Card/ListItem/StatRow/ChatMessage` + `Bone` |
| `Collapse` | `Collapse/index.tsx` | `open`, `children`, `duration?=280`, `unmountOnClose?=false`, `className?` | 高度 0→auto 的平滑折叠（先测真实高度再转 px 动画） |
| `CountUp` | `CountUp/index.tsx` | `value`, `duration?=900`, `easing?`, `decimals?`, `prefix?`, `suffix?`, `thousandSeparator?`, `playOnVisible?=true`, `className?`, `style?` | 数字滚动动画，默认 `easeOutExpo`，进入视口才开始 |
| `PageTransition` | `PageTransition/index.tsx` | `children`, `variant?='fade'\|'slide'\|'scale'`, `duration?=300` | 路由切换过渡，按 `pathname` 换 key 重新挂载 |
| `PaginationBar` | `PaginationBar/index.tsx` | `total`, `offset`, `limit`, `hasMore`, `paged`, `loading?`, `onPrev`, `onNext` | 列表底部翻页条：「共 N 条 · 第 k 页」+ 上一页/下一页；`paged=false` 时降级为单页、按钮禁用（见 6.5） |
| `ErrorBoundary` | `ErrorBoundary/index.tsx` | `children` | 路由级错误边界（class 组件）：渲染 `Result status="error"` + 刷新按钮，不再白屏 |

### 7.2 其余 10 个文件

`MapView/MapProvider.ts`（地图抽象接口）、`MapView/AMapProvider.ts`（高德 2D/3D 实现）、
`MapView/amapLoader.ts`（高德脚本单例加载与最小类型声明）、`MapView/markerElement.ts`（标记 DOM 纯函数）、
`MapView/markerElement.test.ts`（3 用例）、`MapView/AMapProvider.test.ts`（6 用例，happy-dom）、
`FootprintMap/basemap.ts`（高德/OSM 底图样式与回退开关）、`FootprintMap/footprintData.ts`（足迹统计纯函数）、
`FootprintMap/footprintData.test.ts`、`lib/coords.ts`（WGS-84 ↔ GCJ-02）、`lib/coords.test.ts`、
`BudgetExecution/index.test.tsx`（预算使用率百分比、行程外与正负号断言）、
`OfflineBanner/index.test.tsx`（离线提示条的显示/关闭/恢复在线后重置 + 待同步队列四种状态，9 用例）、
`UndoBar/index.test.tsx`（倒计时/撤销/关闭，6 用例）、`lib/tripFocus.ts`（下一项判定、完成后的滚动目标、滚动容错，14 用例）。

### 7.3 FootprintMap 的 props

| prop | 类型 | 说明 |
| --- | --- | --- |
| `points` | `TripMapPoint[]` | 所有已定位行程点 |
| `mode` | `'trip' \| 'city'` | 按行程或按城市聚合 |
| `theme` | `'light' \| 'dark'` | 由 `basemap.ts` 生成高德/OSM 对应底图样式 |
| `basemap` | `'amap' \| 'osm'` | 底图；默认取 `VITE_FOOTPRINT_BASEMAP`（缺省 `amap`） |
| `focusTripId` | `number \| null` | 要高亮的行程 |
| `playSignal` | `number` | 递增即触发「按时间回放」 |
| `onCityClick` | `(city: string) => void` | 点城市标记 → 打开 `CityDrawer` |
| `onMapReady` | `() => void` | 地图就绪回调 |

---

## 8. 行程地图：高德 Provider 抽象

行程页统一使用**高德 JS API 2.0**，`AMapProvider` 实现 `MapProvider`；2D/3D 只是同一服务商的两种视角。
`FootprintMap` 是独立组件，仍用 MapLibre 提供球体投影，不走这个接口；底图默认换成高德栅格瓦片
（`basemap.ts` 的 `buildAmapRasterStyle`），因此它是“高德底图 + MapLibre 球体”，不是高德 JS API 渲染的球体。

**坐标契约**：存储 / API / 业务计算一律 **WGS-84**；只在渲染边界转换。`AMapProvider` 把入参转成 GCJ-02；
足迹地图在 `basemap=amap` 时把展示点转成 GCJ-02，`basemap=osm` 时保持 WGS-84。转换函数在
`src/lib/coords.ts`（后端对应 `util/CoordinateTransform.java`），两端公式一致。高德瓦片直连不是面向第三方
引擎的正式开放服务，商用前需确认高德授权；`VITE_FOOTPRINT_BASEMAP=osm` 是一键回退开关。

```ts
export interface MapProvider {
  init(container: HTMLDivElement, onBaseMapStatus?: (status: 'ok' | 'failed') => void): void;
  render(items: ItineraryItem[], focusDay: number | null): void;
  flyTo(lat: number, lng: number, zoom?: number): void;
  destroy(): void;
  // 可选扩展：巡航与俯仰角
  startTour?(items: ItineraryItem[]): void;
  stopTour?(): void;
  setPitched?(pitched: boolean): void;
}
```

**实现要点**（`MapView/index.tsx`、`AMapProvider.ts`、`amapLoader.ts`）：

- **配置**：`travel-agent-web/.env.local` 提供 `VITE_AMAP_KEY` 与 `VITE_AMAP_SECURITY_CODE`。
  前者必须是高德「Web 端(JS API)」Key，不能复用后端的「Web 服务」`AMAP_KEY`。
- **按需加载**：`amapLoader` 在脚本执行前写入 `_AMapSecurityConfig`，再从 `webapi.amap.com` 加载 2.0 SDK；
  Promise 单例保证 2D/3D 切换不重复下载，失败或 15 秒超时会清理脚本并允许重试。
- **默认 `'3d'`**：`pitch: 55`、`rotation: -18`、3D 楼块；切到 2D 时销毁当前地图并以 `viewMode: '2D'` 重建。
  `Scale`、`ToolBar` 两种视角都有，`ControlBar` 只在 3D 加载。
- **路线与标记**：按 `dayIndex` 分组，以两条 `AMap.Polyline` 叠加辉光与实线，当前 Day 加粗；
  标记继续使用 `buildMarkerElement` 的 DOM + `textContent` 安全构造，颜色沿用 `DAY_COLORS`。
- **真实道路几何**（V2.2 收口）：`RouteLeg.polyline`（后端给的 WGS-84 `[lat,lng]`，已抽稀 ≤200 点）存在且 ≥2 点时，
  `AMapProvider` 逐点 `toAmapLngLat()` 转 GCJ-02 后作为 `path` 绘制；缺省/空数组/单点时回退「两端点直连」。
  `source=amap` 实线、`estimated` 虚线不变——**几何缺省时空着，绝不补假点**（ADR-009）。
- **渲染去重**：Provider 对完整渲染内容（含标题、简介、类型、坐标、时间与 `focusDay`）生成 key；
  同 id 的坐标或标题被 Agent 修改后也会重绘。几何另用「点数 + FNV-1a 摘要（1e-5° 量化）」入 key，
  既不把整条坐标串塞进 key，又能在中段点变化时重绘。
- **异步挂起**：SDK 未就绪时保存 `pendingItems`、`pendingDay`、定位目标与巡航请求，初始化后补齐；
  加载完成前 destroy 不会继续建图。
- **Focus 联动**：`focus.latitude/longitude` 优先；缺失时退回 `focus.itemId` 在 `items` 里找坐标，
  通过 `setZoomAndCenter` 定位；巡航中来了 focus 会先 `stopTour()`。
- **失败与恢复**：加载异常或 12 秒无 `complete` 时显示高德配置/网络提示；失败后若底图恢复，
  `complete` 会再次上报 `ok` 并清除提示。成功之后忽略个别瓦片错误。
- **替换服务商**：新实现满足 `MapProvider` 即可；`setPitched`/`startTour` 仍是可选扩展。

**两条必须遵守的样式约束**（来自 `docs/DEVELOPMENT.md`）：`.fp-map` / `.maplibregl-map` 是 maplibre 容器，
maplibre 会给它加 `position: relative`，设尺寸**必须显式写 `height`**，不能用 `absolute + inset`、也不能只给
`flex: 1`，否则容器塌成 0 高、地图完全不渲染；另外足迹球体投影依赖 maplibre v5+，`FootprintMap` 用
`typeof Map.prototype.setProjection === 'function'` 做运行时探测，旧版本退回平面 3D。

---

## 9. 构建与测试

### 9.1 npm scripts

| 命令 | 实际内容 | 说明 |
| --- | --- | --- |
| `npm run dev` | `vite` | 开发服务器 5173，`/api` 反代到 `http://localhost:8080` |
| `npm run build` | `tsc --noEmit && vite build` | **先类型检查再构建**，类型错直接失败 |
| `npm run preview` | `vite preview` | 预览 4173，同样反代 `/api` → 8080 |
| `npm run typecheck` | `tsc --noEmit` | 仅类型检查 |
| `npm test` | `vitest run` | 单跑一次单元测试 |
| `npm run e2e` | `playwright test` | 端到端冒烟 |

`dev` 与 `preview` **都**配了 `/api` 反代，所以 e2e 跑在 preview 上不需要另做 mock 后端。

### 9.2 vite 分包策略

`manualChunks` 只接管三类打体积的依赖，其余交回 Rollup：

```ts
if (id.includes('echarts') || id.includes('zrender'))     return 'echarts';
if (id.includes('maplibre-gl')) return 'maps';
if (id.includes('antd') || id.includes('@ant-design') || id.includes('rc-')) return 'antd';
return undefined;
```

- `echarts` / `maps` / `antd` 体积大但更新频率低，可长期缓存；配合路由懒加载，首屏只下 react + 当前页。
- **其余依赖刻意不塞进 `vendor`**：早期把剩余依赖统一归到 vendor，会与 react chunk 互相引用，形成 `vendor → react → vendor` 循环。
- `target: 'esnext'`、`minify: 'terser'`：该 Windows 环境下 esbuild 压缩时清理临时目录会被系统拒绝（Access is denied），故改用 Terser。
- PWA 只预缓存 app shell：`navigateFallback: '/index.html'`，`navigateFallbackDenylist: [/^\/api\//]`；
  **`/api` 不做导航回退、SSE 不进任何缓存**；运行时缓存只有一条 `NetworkFirst` 规则、显式 `method: 'GET'`，
  覆盖 **3 个只读端点**（行程列表 / 行程安排 / 预算执行）；写操作一律不缓存，详见 **11.3**。

### 9.3 单元测试（vitest）

`vitest.config.ts`：`environment: 'node'`、`include: ['src/**/*.test.{ts,tsx}']`、
`setupFiles: ['./src/test/setup.ts']`、`restoreMocks: true`。

**node / DOM 环境差异**：默认 node 覆盖 store、API 客户端等纯逻辑，快且无需 DOM。需要 DOM 时在文件顶部加 `// @vitest-environment happy-dom`（见 `MapView/markerElement.test.ts` 第一行）—— 仓库用 `happy-dom` 而非 jsdom（jsdom 依赖 `require(ESM)`，对 Node 版本更敏感）。另外 `src/test/setup.ts` 在 node 环境补了一个**内存版 `Storage`**：zustand 的 `persist` 中间件依赖 `localStorage`/`sessionStorage`，node 环境下不存在，不补就会在导入 store 时抛错。

当前 **48 个测试文件、490 个用例**（`npm test` 实测，以实际汇总为准）：`api/client.test.ts`、`footprintData.test.ts`、
`agentStore.test.ts`、`statusQueueStore.test.ts`（离线待同步队列：入队合并/持久化/逐项重放/网络错误中止）、
`lib/statusQueue.test.ts`、`lib/tripFocus.test.ts`（下一项判定与完成后的滚动目标）、`UndoBar/index.test.tsx`、
`pages/Planner/planDraft.test.ts`（草稿读写容错与步骤门禁）、`pages/Planner/index.test.tsx`（三步流程）、
`pages/Trip/index.test.tsx`（自动滚动 + 短时撤销 + 离线入队 + 侧栏 keep-alive）、`BudgetExecution/index.test.tsx`、
`planForm.test.ts`、`themeStore.test.ts`、`api/ticket.test.ts`、`AMapProvider.test.ts`、`api/pagination.test.ts`、
`reminderNotify.test.ts`、`api/agent.test.ts`、`OfflineBanner/index.test.tsx`、`Timeline/index.test.tsx`、
`markerElement.test.ts`、`basemap.test.ts`、`Workbench/index.test.tsx`、回顾/知识库契约相关测试等。
> 测试写法上的一个坑：`api/agent.test.ts` 的 mock 用**普通函数**而非 `vi.fn()`，
> 因为全局开了 `restoreMocks: true`，`vi.fn()` 的实现会在每个用例前被重置。

### 9.4 Playwright e2e

`playwright.config.ts`：`testDir: './e2e'`、`baseURL: 'http://localhost:4173'`、`trace: 'retain-on-failure'`、`locale: 'zh-CN'`、单 chromium project、`timeout: 30_000`；`webServer.command = 'npm run preview'` 自动起 preview（CI 下 `reuseExistingServer: false`）。
跑法：终端 1 `cd travel-agent-server && mvn spring-boot:run`；终端 2 `cd travel-agent-web && npm run build && npm run e2e`。

当前 **4 个 spec、13 个用例**（`npm run e2e` 实测 13/13 通过：8 条功能 + 5 条无障碍扫描）：

- `e2e/smoke.spec.ts` **3 例**：① **未登录访问首页 → 跳登录页**（`browser.newContext()` 显式空 `storageState`，才是真正的匿名访客）；
  ② **登录 → 建行程 → 行程页与路书可达** —— 登录态来自 `globalSetup` 写入的 `storageState`，用 `page.request.post` 建行程（数据准备不走 UI 表单），
  `/trip/:id` 断言标题与「路书」按钮，再 `GET /api/trips/:id/guidebook.pdf` 断言首字节 `0x25`（`%PDF`）；
  ③ **PWA 资源可达** —— `/manifest.webmanifest` 的 `name` 为「AI 智能旅行助手」，`/sw.js` 含 `precache`。
- `e2e/planner-form.spec.ts` **2 例**：**攻略整合模式**（空提交校验 → 填表 → POST 流式 → 确认 → 新行程）与
  **从零规划模式**（填目的地与天数 → 生成 → 确认 → 新行程），都走规则路径、不依赖 LLM 与外部服务。
- `e2e/a11y.spec.ts` **5 例**：`@axe-core/playwright` 扫登录/首页/助手/旅行/回顾，WCAG 2.0/2.1 A+AA，critical/serious 必须为 0。
- `e2e/maplibre-vendor.spec.ts` **3 例**：地图引擎 vendor 文件可发布、浏览器可 import、worker 与样式相对路径可解析。

另有**独立的视觉回归套件**（不在此列，因为不需要后端与登录）：`npm run test:visual`，
`playwright.visual.config.ts` + `e2e/visual/`，11 张关键页面截图基线，说明见 [frontend-quality.md §6](frontend-quality.md#6-视觉回归截图基线)。

---

## 10. 前端约定

| 主题 | 约定 |
| --- | --- |
| **国际化** | **无国际化，文案硬编码中文**。antd 侧只装了 `zhCN` locale（日期/分页等内置文案），业务文案全写在 JSX 里 |
| **错误提示** | 接口层 `request()` 已把失败归一成 `Error(message)`；页面用 `App.useApp()` 的 `message.error(e.message)` 展示操作类错误 |
| **Toast vs message** | 业务操作结果用 `message`（紧跟操作元素）；跨页/异步完成/Agent 相关提示用 `toast`，因为 `ToastContainer` 挂在 `App` 根部、不随页面卸载 |
| **加载态 / 过渡** | 路由级 `Suspense fallback={<RouteLoading/>}`（居中 `Spin`）；列表用 `Skeleton`；全站统一 `PageTransition variant="slide"`，不再单独加进入动画 |
| **鉴权** | 不存 token；新接口无需关心凭证来源（Cookie 自动带）。登出只能 `signOut()` |
| **URL 凭证** | 只能用 `api/ticket.ts` 的票据，**禁止把登录 JWT 拼进 URL** |
| **懒加载** | 新页面一律 `React.lazy` + `Suspense`；页面内重型组件（echarts/地图）再 `lazy` 一层，别回到单包 3MB |
| **样式令牌** | `styles/tokens.css` 的 `:root` 是唯一来源。主用名 `--bg/--surface/--border/--text/--text-2/--text-3/--accent/--ok/--warn/--danger/--r-*/--sp-*`，旧名（`--ink/--line/--blue/--lg-*`）是别名 |
| **色板与暗色** | `main.tsx` 的 `PALETTE` 与 `tokens.css` 是同一套值（主色 `#1f6feb`、圆角 8、边框 `#e4e6eb`），**改色板两处一起改**；`[data-theme='dark']` 只覆盖令牌层，`index.html` 首帧前预设属性避免闪白；canvas 图表（echarts）读不到 CSS 变量，**必须按主题给两套色值**。完整机制（含 `localStorage` 键、三处入口）见 **11.1** |
| **响应式** | ≥1080px 侧栏 232px；720–1080px 收成 76px 图标栏；<720px 换底部标签栏 `.app-tabbar`。**新增窄屏规则写在 `shared.css` 的 390px 段**，否则会被旧的 `@media (max-width: 900px)` 抢先覆盖；390px 实测场景与溢出修复清单见 **11.2** |
| **外壳复用** | 子页面（`Schedule`/`Footprint`）自带 `AppShell`，被嵌套复用时靠 `InsideShell` context 退化为纯内容，不必删壳 |
| **纯逻辑抽离** | 复杂计算抽成同目录 `.ts`（`planForm.ts`、`footprintData.ts`、`reminderNotify.ts`、`markerElement.ts`），组件只做渲染 —— 这些也是单测的主要目标 |

**设计预览**：`docs/design/preview/index.html` + `preview.css` 是「方向 B：内容优先」改版的**静态视觉稿**
（纯 HTML/CSS，不含 React），用于动代码前对齐版式与信息密度；`styles/tokens.css` 的令牌层是它的落地实现。
它不参与构建，只在改界面时作为参照。

---

## 11. 暗色主题、窄屏适配与离线（PWA）

三件事都在本批落地，互不依赖：暗色只改**令牌层**，窄屏只加**末尾 `@media` 规则**，离线只加**workbox 的 3 条 GET 运行时缓存**。

### 11.1 暗色主题

| 项 | 实现 | 位置 |
| --- | --- | --- |
| 颜色令牌 | 浅色在 `:root` 定义，暗色由 `[data-theme='dark']` **只覆盖令牌层**（组件样式一律引用变量） | `src/styles/tokens.css` |
| antd 组件 | `ConfigProvider` 在 `darkAlgorithm` / `defaultAlgorithm` 间切换，同时切一套 `PALETTE` token | `src/main.tsx` |
| 持久化 | zustand `persist`，key **`localStorage['travel-agent-theme']`**，值形如 `{"state":{"mode":"dark"}}` | `src/store/themeStore.ts` |
| 无存值 | 跟随系统 `prefers-color-scheme`（没有 `matchMedia` 时按浅色） | `themeStore.systemPrefersDark` |
| 存了但非法/损坏 | **回落 light**（绝不把非法值写进 `data-theme`） | `themeStore.readStoredTheme` |
| 首帧不闪白 | `index.html` 的 `<head>` 内联脚本在样式表加载**之前**写 `document.documentElement.dataset.theme`；三条规则与 `themeStore` 逐条一致 | `index.html` |
| 入口 | 侧栏/底栏的主题按钮（`AppShell`）+「我的 → 外观」（`pages/Profile`） | `AppShell.tsx`、`pages/Profile/index.tsx:96-110` |

对外 API（`themeStore.ts`）：`mode` / `setMode` / `toggle`、`THEME_STORAGE_KEY`、`isThemeMode`、`readStoredTheme`、
`resolveInitialMode`、`applyTheme(mode)`。`main.tsx` 在 `useEffect` 里把 `mode` 同步到 `<html data-theme>`。

> **canvas 图表读不到 CSS 变量**：echarts（预算图、支出分类图、回顾统计）必须按主题给两套色值，不能指望 `var(--...)`。
> 目前仍有个别图表是页内手写配色，新增图表请照 `BudgetChart` 的做法从 `themeStore.mode` 取色。

### 11.2 窄屏（390px）防横向滚动

- **安全区**：`index.html` 的 `<meta name="viewport" ... viewport-fit=cover>` 让 iOS 安全区生效；
  顶部栏用 `env(safe-area-inset-top)` 吃掉刘海，底栏/抽屉底部用 `env(safe-area-inset-bottom)` 避开 home 指示条。
- **实测口径**：390×844 视口下 **7 个场景** `document.documentElement.scrollWidth == clientWidth == 390`
  （规划页、行程页、账单抽屉、预算抽屉、备忘页、账单总览、暗色行程页）。
- **修掉的溢出点**（都在 `styles/shared.css` 的 390px `@media` 块里）：

| 溢出源 | 修法 |
| --- | --- |
| antd `Drawer` 的宽度是桌面写死的 480/560，窄屏超出视口 | `.ant-drawer-content-wrapper { max-width: 100vw }`（inline `width` 不覆盖 `max-width`）；`.ant-drawer-mask` / `.ant-modal-wrap` 同样夹住 |
| 长标题/长文本把父容器撑破（flex 子项默认 `min-width: auto`） | `.dash-greeting`、`.trip-header`、`.th-title` 等 `min-width: 0` + `overflow-wrap: anywhere` |
| 列表行 / 账单行 / 表格单元格 | `.tr-title`、`.tr-sub`、`.memo-content`、`.ant-list-item(-meta)` 加 `min-width: 0` + 断行；`.ant-table-wrapper` 允许容器内部横滚而不是撑宽整页 |
| 表单页脚/页头操作区放不下 | `.plan-footer`、`.page-actions { flex-wrap: wrap }` |
| 抽屉底部被 home 指示条压住 | `.ant-drawer-body { padding-bottom: calc(16px + env(safe-area-inset-bottom)) }` |

> **新窄屏规则必须写在 `styles/shared.css` 的 390px 段**：否则会被更早的 `@media (max-width: 900px)` 块抢先覆盖（同优先级后写胜出）。

### 11.3 PWA 离线：只缓存 3 条只读 GET

`vite.config.ts` 的 `workbox.runtimeCaching` **只注册一条路由规则、只匹配 GET**，URL 正则同时覆盖三个只读端点：

```ts
// 整串匹配 URL（含 query）
const ITINERARY_API_PATTERN =
  /^https?:\/\/[^/]+\/api\/trips(?:\/\d+\/(?:itinerary|budget))?(?:\?[^#]*)?$/;
```

| 命中端点 | 含义 |
| --- | --- |
| `GET /api/trips` | 行程列表 |
| `GET /api/trips/<id>/itinerary` | 某趟行程的安排 |
| `GET /api/trips/<id>/budget` | 某趟行程的预算执行 |

**故意不匹配** `GET /api/trips/<id>`（行程详情）：契约没要求，少缓存一类就少一类脏数据。

| 参数 | 值 | 为什么 |
| --- | --- | --- |
| `method` | `'GET'`（**显式写死**） | 不写的话 workbox 会为非 GET 也注册路由，一旦 POST/PUT/DELETE 被缓存，离线时会拿旧响应冒充「写入成功」 |
| `handler` | `NetworkFirst` | 在线优先拿最新数据，弱网才回缓存 |
| `networkTimeoutSeconds` | `3` | 网络 3 秒拿不到就走缓存（弱网下不让用户干等 30 秒） |
| `cacheName` | `'itinerary-v1'` | 改缓存策略/数据结构时 **+1**，旧容器由 `cleanupOutdatedCaches` 清 |
| `expiration` | `{ maxEntries: 50, maxAgeSeconds: 300 }` | 行程数据实时性要求高，宁可只离线可用 5 分钟、最多 50 条 |
| `cacheableResponse` | `{ statuses: [200] }` | **只缓存 200**：401/5xx 不进缓存，避免把「登录失效」页面缓存下来反复出现 |
| `cleanupOutdatedCaches` | `true` | 版本升级后自动清掉上一版 precache / runtime 容器（只清 workbox 自己建的），用户不用手清站点数据 |

**写操作不缓存**：`dist/sw.js` 里搜不到 `POST` / `PUT` / `DELETE`（发布前可自查），SSE 的 `/api/agent` 更不进任何缓存。

**离线提示**：`components/OfflineBanner`（挂在 App 顶部）+ `hooks/useOnlineStatus`（`navigator.onLine` 初值 + 原生
`online`/`offline` 事件，挂载时再校准一次真实状态），断网时显示 **「离线：显示缓存数据」**，恢复在线自动消失并重置手动关闭状态。

边界：离线**只能看已经打开过**的行程（列表/安排/预算三者之一被请求过才会进缓存）；离线写操作照常失败并报错；
超过 5 分钟或超过 50 条的缓存会被淘汰。iOS/Safari 的 `viewport-fit` / `safe-area` 行为**未实机验证**（只有 Chromium 模拟）。

### 11.4 相关测试

| 覆盖 | 用例 |
| --- | --- |
| 主题持久化与首帧规则 | `store/themeStore.test.ts` **8** 用例（读写、非法值回落 light、跟随系统、`applyTheme`） |
| 离线提示条 | `components/OfflineBanner/index.test.tsx` **4** 用例（显示/关闭/恢复在线后重置/在线不渲染） |
| 全量 | **30 个文件 / 216 用例**（见 9.3），`npm test` 以实际汇总为准；e2e 5 例 |

---

## 附：快速定位指南

| 我想… | 从这里开始 |
| --- | --- |
| 加一个页面 | `pages/<Name>/index.tsx` + `App.tsx` 加 lazy 与 `<Protected>` 路由 + `AppShell` 的 `NavModule` / `GROUPS` |
| 改聊天阶段文案 / SSE 事件处理 | `store/agentStore.ts` 的 `STAGE_TEXT` / `api/agent.ts` 的 `EVENT_NAMES` 与 handlers |
| 改确认弹窗 | `components/PlanDiff/index.tsx` |
| 改列表分页 | `api/client.ts` 的 `getPage` / `DEFAULT_PAGE_SIZE` + `components/PaginationBar` |
| 换行程地图服务商 | `components/MapView/` 下的 `MapProvider`；当前高德实现见 `AMapProvider` 与 `amapLoader` |
| 改登录态逻辑 | `App.tsx` 的 `Protected` + `api/auth.ts` + `api/client.ts` 的 401 分流 |
| 改配色 | `styles/tokens.css` 的 `:root` + `main.tsx` 的 `PALETTE`（两处一起） |

**组件统计**：`src/components/` 共 **29 个目录**（含组件测试），含地图、足迹、预算、聊天与基础组件；
核心组件见 7.1，辅助文件见 7.2。

**页面统计**：`src/pages/` 共 **15 个目录**；`App.tsx` 共 **20 条 `<Route>`** —— 13 条渲染页面组件
（12 个受保护页 + `/login`）、6 条重定向（`/office`、`/learning`、`/health`、`/schedule`、`/footprint`、`/workbench`）、1 条通配兜底 `*`。
较重的 Planner / Trip 已把步骤视图与工作区拆到各自的 `components/` 子目录（`index.tsx` 只留状态、副作用与提交门禁）。
