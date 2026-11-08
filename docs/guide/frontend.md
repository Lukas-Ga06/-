# 前端结构与页面说明（travel-agent-web）

> 面向**要改界面的前端开发者**（路由、组件、两大链路）与**参与评审的产品/设计同学**（页面清单、交互约定）。
> 内容基于 `travel-agent-web/` 当前 HEAD 的真实源码，路径均可直接打开。

前端是整个「AI 个人助理」的唯一交互入口。后端（`travel-agent-server`）提供 Agent 引擎与 REST/SSE 接口；
前端把 Agent 的**过程**（意图、工具调用、方案对比）可视化，并守住一条底线 ——
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
| echarts | ^6.1.0 | 预算图、记账分类图、健康 7 天趋势 |
| leaflet / maplibre-gl | ^1.9.4 / ^6.15.0 | 2D 地图引擎 / 3D 倾斜地图 + 足迹球体投影 |
| zustand | ^4.5.5 | 全局状态（5 个 store，见第 5 节） |
| dayjs | ^1.11.13 | 日期格式化与 Day 映射 |
| vitest / happy-dom | ^4.1.11 / ^21.0.4 | 单元测试；组件类用例的 DOM 环境 |
| @testing-library/react | ^16.3.3 | 组件测试（现有用例偏逻辑，见第 9 节） |
| @playwright/test | ^1.64.1 | e2e 冒烟 |

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
└── src/
    ├── main.tsx               # ConfigProvider（色板/暗色算法）+ AntApp + 挂载 Root
    ├── App.tsx                # 路由表、Protected、登录态恢复、ErrorBoundary、Suspense
    ├── global.d.ts            # Web Speech API 等缺失类型补声明
    ├── api/                   # 13 个文件（9 个模块 + 4 个测试）
    │   ├── client.ts          # axios 实例、{code,message,data} 解包、401 分流
    │   ├── agent.ts           # SSE（GET EventSource / POST fetch）与方案确认接口
    │   ├── auth.ts            # 登录/注册/me/登出；signOut() 是唯一登出入口
    │   ├── ticket.ts          # 短期只读票据：缓存 + 并发合并 + useAccessTicket()
    │   ├── trip.ts / expense.ts / memo.ts      # 行程与条目、记账、备忘
    │   ├── assistant.ts       # 日程/邮件/纪要/日报/发票/学习/健康/系统状态
    │   ├── reminder.ts / photo.ts              # 提醒看板、行程照片（原图带票据）
    ├── components/            # 29 个文件（22 个组件 + 7 个接口/工具/测试），见第 7 节
    ├── hooks/useIntersection.ts   # IntersectionObserver 封装（CountUp 入视口才动画）
    ├── lib/                   # reminderNotify.ts + 其测试
    ├── pages/                 # 16 个页面目录，见第 4 节
    ├── store/                 # 6 个文件（5 个 store + agentStore 测试）
    ├── styles/global.css      # 4272 行设计系统：:root 令牌 + 暗色覆盖 + 页面样式
    ├── test/setup.ts          # node 环境补内存 Storage，供 zustand persist 使用
    └── types/                 # 5 个类型文件：trip / agent / expense / memo / assistant
```

---

## 3. 路由表

唯一来源是 `src/App.tsx` 的 `<Routes>`，共 **17 条 `<Route>`**（顺序与源码一致）。

| # | path | 组件 | 受保护 | 备注 |
| --- | --- | --- | --- | --- |
| 1 | `/login` | `pages/Login` | 否 | 登录/注册 Tab 同页 |
| 2 | `/` | `pages/Home` | ✅ | 总览首页 |
| 3 | `/planner` | `pages/Planner` | ✅ | 结构化规划表单，走 POST 版 SSE |
| 4 | `/trip/:id` | `pages/Trip` | ✅ | 行程详情：地图 + 时间轴 + 聊天 + 预算抽屉 |
| 5 | `/expenses` | `pages/Expenses` | ✅ | 跨行程支出总览 |
| 6 | `/memos` | `pages/Memos` | ✅ | 备忘，支持左滑操作 |
| 7 | `/office` | `pages/Office` | ✅ | 邮件/纪要/日报/发票 |
| 8 | `/learning` | `pages/Learning` | ✅ | 学习辅助 + 知识库 |
| 9 | `/health` | `pages/Health` | ✅ | 健康记录 + 7 天 ECharts 趋势 |
| 10 | `/schedule` | `<Navigate to="/travel?tab=schedule" replace />` | — | 旧链接兼容重定向，无独立页面 |
| 11 | `/assistant` | `pages/Assistant` | ✅ | 统一对话入口 |
| 12 | `/travel` | `pages/Travel` | ✅ | 旅行规划主页（Tab：行程/日程/足迹） |
| 13 | `/footprint` | `<Navigate to="/travel?tab=footprint" replace />` | — | 旧链接兼容重定向，无独立页面 |
| 14 | `/workbench` | `pages/Workbench` | ✅ | AI 工作台（Tab 复用 Office / Learning） |
| 15 | `/profile` | `pages/Profile` | ✅ | 个人中心、系统状态、退出登录 |
| 16 | `*` | `<Navigate to="/" replace />` | — | 通配兜底，未知路径回首页 |
| 17 | `/ui-demo` | `pages/UIDemo` | ✅ | 组件厨房（SwipeRow/DraggableList/Collapse/CountUp…） |

> `*`（第 16 条）在源码里排在 `/ui-demo` 之前。react-router v7 按“评分最优”而非书写顺序匹配：
> `/ui-demo` 是静态完整路径、评分高于 `*`，因此仍能正常命中。新增路由建议统一放到 `*` 之前，避免误解。

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

### 4.1 Login（`/login`）

登录/注册两个 Tab，左侧三条产品卖点；成功后 `setAuth` 再跳首页。
API：`loginApi`、`registerApi`。组件：antd `Tabs/Form/Input/Button/Typography`。

### 4.2 Home（`/`，`AppShell active="home"`）

一屏总览 —— 问候语与日期、今日日程、未完成备忘（可勾选）、支出概览、足迹卡片；
顶部输入框把一句话带到 `/assistant?q=…` 自动发送。
API：`getTripsMap`、`getExpenseOverview`、`getMemos`/`toggleMemo`、`listSchedule`、`listArtifacts`、
`listMinutes`、`listReports`、`healthSummary`、`getReminders`。
组件：`AppShell`、`CityDrawer`、`VoiceButton`、`CountUp`、`Skeleton`；足迹地图用
`lazy(() => import('../../components/FootprintMap'))` 二次懒加载，统计纯函数来自 `FootprintMap/footprintData.ts`。

### 4.3 Planner（`/planner`）

结构化规划表单（目的地、时间两形态、同行人、预算、节奏、偏好、锁定项），
两种模式：`guides`（粘贴参考攻略正文整合）与 `scratch`（从零规划）。
API：不直接调 REST，全部经 `useAgentStore.send({ transport: 'post' })`。
组件：`ChatPanel`、`PlanDiff`；纯逻辑在同目录 `planForm.ts` —— `compilePlanPrompt`（表单 → 中文需求文本）、
`compilePlanSummary`（聊天气泡短摘要）、`clampPlanMessage`（兜底截断）。

### 4.4 Trip（`/trip/:id`）

行程详情主战场 —— 左侧地图（3D/2D 可切、沿路线巡航）、右侧时间轴（按 Day 分组）、
底部聊天面板可对当前行程发起修改；顶部可撤销、导出路书、打开预算抽屉。
API：`getTrip`/`getItinerary`/`getBudgetExecution`、`undoTrip`。
组件：`MapView`、`Timeline`、`ChatPanel`、`PlanDiff`、`BudgetChart`、`BudgetExecution`、`ExpensePanel`。

### 4.5 Expenses（`/expenses`）

跨行程支出总览，按分类/支付方式聚合，进度条对照预算。API：`getExpenseOverview`。
组件：`CountUp`、`Skeleton`；颜色常量在 `types/expense.ts`（`CATEGORY_COLORS`/`PAYMENT_COLORS`）。

### 4.6 Memos（`/memos`）

新增、勾选完成、删除备忘，左滑露出操作按钮。勾选做并发保护 —— `pending` Set 挡住连点，
因为 toggle 是取反语义，连点两次会互相抵消。API：`getMemos`/`addMemo`/`toggleMemo`/`deleteMemo`。
组件：`SwipeRow`、`Skeleton`、`AppShell`。

### 4.7 Office（`/office`）

邮件分类、会议纪要生成、日报生成、发票识别与「一键转记账」，四块用 `Tabs` 分栏。
API：`classifyMail`、`generateMinutes`/`listMinutes`、`generateReport`/`listReports`、
`extractInvoice`/`uploadInvoice`/`listInvoices`/`invoiceToExpense`。
组件：antd `Upload/Modal/Select/Tabs`；本地 `MdView` 以 `pre-wrap` 渲染纯文本产出。

### 4.8 Learning（`/learning`）

知识框架、学习路线、重点归纳的生成与归档，以及知识库录入与检索。
API：`generateLearning`、`listArtifacts`/`deleteArtifact`、`addKnowledge`、`searchKnowledge`。
组件：`Tabs`；结果类型在 `types/assistant.ts`（`KnowledgeHit`）。

### 4.9 Health（`/health`）

手环数据录入/导入、7 天趋势图、健康建议、演示数据一键填充。
API：`upsertHealth`、`listHealth`、`healthSummary`、`seedHealthDemo`。
组件：直接 `import * as echarts` 手写 chart；**canvas 读不到 CSS 变量**，按 `themeStore.mode` 传两套色值。

### 4.10 Schedule（`/travel?tab=schedule`）

日程增删改查、按时辰与重复规则（`NONE`/…）设置；行内 toggle 同样做并发保护。
API：`listSchedule`/`addSchedule`/`toggleSchedule`/`deleteSchedule`。组件：`DatePicker`/`TimePicker`/`Select`；
被 `Travel` 与 `Workbench` 复用，`AppShell` 检测到已在外壳内会直接返回 children。

### 4.11 Footprint（`/travel?tab=footprint`）

全屏足迹地图 + 行程列表 + 按时间回放；统计由 `footprintData.ts` 在本地算。API：`getTripsMap`。
组件：`FootprintMap`（maplibre 球体投影，运行时探测 `setProjection`，旧版本退回平面 3D）。

### 4.12 Assistant（`/assistant`）

统一对话入口，**URL 参数即状态**：`?q=…` 从首页/城市抽屉带问题跳转（`startedRef` 保证只自动发一次）；
`?planId=…` 刷新后用 `getPendingPlan` 找回待确认方案；`pendingConfirm` 出现时把 `planId` 写回 URL（`replace: true`）。
API：`getPendingPlan`，发送走 `useAgentStore.send`。组件：`ChatPanel`、`PlanDiff`。

### 4.13 Travel（`/travel`）

三个 Tab —— `trips` 行程列表、`schedule` 日程、`footprint` 足迹；行程卡片支持状态流转
（进行中/已完成/取消）与删除（`Popconfirm`）。API：`listTrips`/`deleteTrip`/`updateTripStatus`。
组件：`VoiceButton`、`AppShell`，内嵌 `Schedule`、`Footprint`。

### 4.14 Workbench（`/workbench`）

把 Office 与 Learning 合成一页用 Tab 切换（`?tab=learning`），无自己的请求。
组件：`Tabs` + `Office`/`Learning`，是 `InsideShell` 上下文的主要消费者。

### 4.15 Profile（`/profile`）

用户信息、行程统计、系统状态（模型/数据源可用性）、退出登录。
API：`systemStatus`、`signOut`、`useTripStore.loadTrips`。组件：antd `Tag`；
退出必须走 `signOut()`，不要直接调 store 的 `logout()`。

### 4.16 UIDemo（`/ui-demo`）

自研基础组件的可视化验收台，改 `SwipeRow/DraggableList/Collapse/CountUp/Skeleton/Toast` 后先在这里肉眼回归。
组件：`SwipeRow`、`DraggableList`、`Collapse`、`CountUp`、`Skeleton`、`Toast`、`useIntersection`。

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
所以绝不能放登录 JWT。方案是换一张**短期只读票据**（后端 `AccessTicketService`，默认 10 分钟，scope `media-read`）：

```ts
export async function getAccessTicket(): Promise<string>;   // POST /api/access-ticket
export function useAccessTicket(): string | null;           // 组件用，随登录态自动获取
export function clearAccessTicketCache(): void;             // 登录态变化时清缓存
```

三个易被忽略的设计点：① **缓存键是 `authKey = user.id + '|' + token`**，换账号必须重新签票；
② **提前 30 秒续期**（`REFRESH_MARGIN_MS`），避免踩在过期边界上；
③ **并发合并在途请求**（`inflight` Promise），否则照片墙每张图都会各签一次票据。
使用方：`api/agent.ts`（GET 版 SSE）、`api/photo.ts` 的 `photoSrc(photo, ticket)`、`CityDrawer` 的照片列表。

---

## 7. 核心组件

`src/components/` 共 **29 个文件** = 下表 22 个组件 + 7 个非组件文件（见 7.2）。

### 7.1 组件清单

| 组件 | 路径 | props | 职责 |
| --- | --- | --- | --- |
| `AppShell` | `AppShell.tsx` | `active: NavModule`, `children` | 应用外壳：分组侧栏（备忘角标、提醒铃铛、用户区、主题切换）、内容区、窄屏底部标签栏；已在壳内（`InsideShell` context）时**只渲染 children** |
| `AssistantNav` | `AssistantNav.tsx` | `active: NavModule` | 旧版顶部导航，保留给需要顶栏的页面 |
| `ChatPanel` | `ChatPanel/index.tsx` | `tripId?`, `placeholder?` | 聊天面板：消息列表 + 语音输入 + 发送；自动滚到底；带 `tripId`/`currentDay`/`selectedElement` 上下文 |
| `MapView` | `MapView/index.tsx` | `items`, `focus`, `focusDay` | 地图容器与引擎切换（见第 8 节），含底图失败降级提示与 3D 巡航按钮 |
| `Timeline` | `Timeline/index.tsx` | `trip`, `items`, `focus` | 时间轴：按 Day 分组，点击选中元素写入 `uiStore`；收到 `focus` 自动切 Day 并 `scrollIntoView` |
| `ItineraryCard` | `ItineraryCard/index.tsx` | `item`, `selected?`, `onClick?` | 行程条目卡片：类型徽标（酒店/景点/餐饮/交通）+ 标题 + 花费 + 标签 + 简介 |
| `PlanDiff` | `PlanDiff/index.tsx` | `open`, `result`, `onConfirm`, `onCancel`, `loading?` | 方案确认弹窗：ADD/REMOVE/MODIFY diff 行、预算前后、HIGH 风险提示 |
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
| `Skeleton` | `Skeleton/index.tsx` | `{ loading, children, className? }` | 骨架屏：`Skeleton.Text/Paragraph/Circle/Card/ListItem/StatRow/ChatMessage` + `Bone` |
| `Collapse` | `Collapse/index.tsx` | `open`, `children`, `duration?=280`, `unmountOnClose?=false`, `className?` | 高度 0→auto 的平滑折叠（先测真实高度再转 px 动画） |
| `CountUp` | `CountUp/index.tsx` | `value`, `duration?=900`, `easing?`, `decimals?`, `prefix?`, `suffix?`, `thousandSeparator?`, `playOnVisible?=true`, `className?`, `style?` | 数字滚动动画，默认 `easeOutExpo`，进入视口才开始 |
| `PageTransition` | `PageTransition/index.tsx` | `children`, `variant?='fade'\|'slide'\|'scale'`, `duration?=300` | 路由切换过渡，按 `pathname` 换 key 重新挂载 |
| `ErrorBoundary` | `ErrorBoundary/index.tsx` | `children` | 路由级错误边界（class 组件）：渲染 `Result status="error"` + 刷新按钮，不再白屏 |

### 7.2 其余 7 个文件

`MapView/MapProvider.ts`（地图抽象接口）、`MapView/LeafletMapProvider.ts`（2D 实现）、
`MapView/MapLibre3DProvider.ts`（3D 实现）、`MapView/markerElement.ts`（标记 DOM 纯函数）、
`MapView/markerElement.test.ts`（3 用例，happy-dom）、`FootprintMap/footprintData.ts`（足迹统计纯函数，180 行）、
`FootprintMap/footprintData.test.ts`（14 用例）。

### 7.3 FootprintMap 的 props

| prop | 类型 | 说明 |
| --- | --- | --- |
| `points` | `TripMapPoint[]` | 所有已定位行程点 |
| `mode` | `'trip' \| 'city'` | 按行程或按城市聚合 |
| `theme` | `'light' \| 'dark'` | 由 `buildRasterStyle()` 生成对应底图样式 |
| `focusTripId` | `number \| null` | 要高亮的行程 |
| `playSignal` | `number` | 递增即触发「按时间回放」 |
| `onCityClick` | `(city: string) => void` | 点城市标记 → 打开 `CityDrawer` |
| `onMapReady` | `() => void` | 地图就绪回调 |

---

## 8. 地图双引擎抽象

两套引擎（Leaflet 2D / MapLibre 3D）共享一个 24 行的接口 `MapProvider`，组件只依赖接口。

```ts
export interface MapProvider {
  init(container: HTMLDivElement, onBaseMapStatus?: (status: 'ok' | 'failed') => void): void;
  render(items: ItineraryItem[], focusDay: number | null): void;
  flyTo(lat: number, lng: number, zoom?: number): void;
  destroy(): void;
  // 可选扩展：3D 才有
  startTour?(items: ItineraryItem[]): void;
  stopTour?(): void;
  setPitched?(pitched: boolean): void;
}
```

| | `MapLibre3DProvider`（278 行） | `LeafletMapProvider`（105 行） |
| --- | --- | --- |
| 底图 | OSM 栅瓦片 + `raster-saturation: -0.12` 降饱和贴合浅色主题 | OSM 瓦片 |
| 视角 | `pitch: 55`、`bearing: -18`，辉光路线 + 脉冲标记 | 平面俯视 |
| 扩展能力 | `startTour`（沿当天各点自动巡航）、`stopTour`、`setPitched` | 无 |
| 首帧 | `load` 事件前先挂起 `pendingItems`，load 后补渲染 | 直接建 `LayerGroup` |
| 主题色 | `DAY_COLORS = ['#007aff','#34c759','#ff9500','#af52de','#ff2d55']` | `['#1677ff','#52c41a','#fa8c16','#722ed1','#eb2f96']` |

**选择逻辑**（`MapView/index.tsx`）：

- **默认 `'3d'`**；用户点 `🗺 2D` 才切 Leaflet。`useEffect(…, [engine])` 负责建与毁：
  `provider.init(containerRef.current, statusCb)`，cleanup 里 `provider.destroy()` 并清 `providerRef`。
  **切换引擎 = 销毁重建**，不做原地换皮。
- 渲染去重靠 `renderedKeyRef`：`items` 的 `id@dayIndex` 拼串 + `focusDay`，key 不变不重绘。
- **Focus 联动**：`focus.latitude/longitude` 优先；缺失时退回 `focus.itemId` 在 `items` 里找坐标（`flyTo(lat, lng, 16)`）；巡航中来了 focus 会先 `stopTour()`。
- **底图失败降级**：两个 Provider 共用同一套判定 —— 成功占多数判 `ok`；失败达 5 次且多于成功、或长时间无响应判 `failed`。
  `failed` 时 `MapView` 显示「⚠️ 底图瓦片加载失败或不稳定」提示条，但**标记与路线仍可用**（弱网/外网受限场景）。
- **新增引擎**：实现 `MapProvider` 四件套即可，`MapView` 里加一个 `Engine` 分支。`setPitched`/`startTour`
  是可选方法，组件侧统一 `provider.startTour?.(…)` 可选调用，不需要改已有实现。

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
if (id.includes('maplibre-gl') || id.includes('leaflet')) return 'maps';
if (id.includes('antd') || id.includes('@ant-design') || id.includes('rc-')) return 'antd';
return undefined;
```

- `echarts` / `maps` / `antd` 体积大但更新频率低，可长期缓存；配合路由懒加载，首屏只下 react + 当前页。
- **其余依赖刻意不塞进 `vendor`**：早期把剩余依赖统一归到 vendor，会与 react chunk 互相引用，形成 `vendor → react → vendor` 循环。
- `target: 'esnext'`、`minify: 'terser'`：该 Windows 环境下 esbuild 压缩时清理临时目录会被系统拒绝（Access is denied），故改用 Terser。
- PWA 只预缓存 app shell：`navigateFallback: '/index.html'`，`navigateFallbackDenylist: [/^\/api\//]`，
  **整个 `/api` 前缀都不做 runtime 缓存**（数据带登录态且实时性强，SSE 更不能缓存）。

### 9.3 单元测试（vitest）

`vitest.config.ts`：`environment: 'node'`、`include: ['src/**/*.test.{ts,tsx}']`、
`setupFiles: ['./src/test/setup.ts']`、`restoreMocks: true`。

**node / DOM 环境差异**：默认 node 覆盖 store、API 客户端等纯逻辑，快且无需 DOM。需要 DOM 时在文件顶部加 `// @vitest-environment happy-dom`（见 `MapView/markerElement.test.ts` 第一行）—— 仓库用 `happy-dom` 而非 jsdom（jsdom 依赖 `require(ESM)`，对 Node 版本更敏感）。另外 `src/test/setup.ts` 在 node 环境补了一个**内存版 `Storage`**：zustand 的 `persist` 中间件依赖 `localStorage`/`sessionStorage`，node 环境下不存在，不补就会在导入 store 时抛错。

当前 **8 个测试文件、55 个用例**（按 `it(...)` 计数，无 `.skip`）：`agentStore.test.ts` 12
（发送/流式回调/确认/取消/reset/新会话/序号竞态）、`footprintData.test.ts` 14（足迹统计、年月筛选、城市聚合）、
`planForm.test.ts` 9（表单编译、摘要、截断）、`reminderNotify.test.ts` 5（通知筛选：权限/后台/去重）、
`api/agent.test.ts` 4（SSE 事件分发，complete/error/onerror 只回调一次）、`api/client.test.ts` 4（`{code}` 解包与 401 分流）、
`api/ticket.test.ts` 4（票据缓存、并发合并、登录态失效）、`markerElement.test.ts` 3（标记 DOM 结构）。

> `docs/DEVELOPMENT.md` 里写「前端 28 个用例」是旧数据；按当前源码统计为 **55**。
> 测试写法上的一个坑：`api/agent.test.ts` 的 mock 用**普通函数**而非 `vi.fn()`，
> 因为全局开了 `restoreMocks: true`，`vi.fn()` 的实现会在每个用例前被重置。

### 9.4 Playwright e2e

`playwright.config.ts`：`testDir: './e2e'`、`baseURL: 'http://localhost:4173'`、`trace: 'retain-on-failure'`、`locale: 'zh-CN'`、单 chromium project、`timeout: 30_000`；`webServer.command = 'npm run preview'` 自动起 preview（CI 下 `reuseExistingServer: false`）。
跑法：终端 1 `cd travel-agent-server && mvn spring-boot:run`；终端 2 `cd travel-agent-web && npm run build && npm run e2e`。

`e2e/smoke.spec.ts` 两个用例：① **登录 → 建行程 → 行程页与路书可达** —— `goto('/')` 断言跳 `/login`（守卫生效），用 `page.request.post('/api/auth/login')` **通过 API 登录**（`page.request` 与浏览器共享 Cookie），回首页断言 AppShell，API 建行程，`/trip/:id` 断言标题与「路书」按钮，再 `GET /api/trips/:id/guidebook.pdf` 断言首字节 `0x25`（`%PDF`）；刻意不走 UI 表单登录，只断言稳定区域，避免与登录页重构互相拖累。② **PWA 资源可达** —— `/manifest.webmanifest` 的 `name` 为「AI 个人助理」，`/sw.js` 含 `precache`。

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
| **样式令牌** | `styles/global.css` 顶部 `:root` 是唯一来源。主用名 `--bg/--surface/--border/--text/--text-2/--text-3/--accent/--ok/--warn/--danger/--r-*/--sp-*`，旧名（`--ink/--line/--blue/--lg-*`）是别名 |
| **色板与暗色** | `main.tsx` 的 `PALETTE` 与 `global.css` 是同一套值（主色 `#1f6feb`、圆角 8、边框 `#e4e6eb`），**改色板两处一起改**；`[data-theme='dark']` 只覆盖令牌层，`index.html` 首帧前预设属性避免闪白；canvas 图表（echarts）读不到 CSS 变量，**必须按主题给两套色值** |
| **响应式** | ≥1080px 侧栏 232px；720–1080px 收成 76px 图标栏；<720px 换底部标签栏 `.app-tabbar`。**新增窄屏规则写在 `global.css` 末尾**，否则会被旧的 `@media (max-width: 900px)` 抢先覆盖 |
| **外壳复用** | 子页面（`Schedule`/`Footprint`/`Office`/`Learning`）自带 `AppShell`，被嵌套复用时靠 `InsideShell` context 退化为纯内容，不必删壳 |
| **纯逻辑抽离** | 复杂计算抽成同目录 `.ts`（`planForm.ts`、`footprintData.ts`、`reminderNotify.ts`、`markerElement.ts`），组件只做渲染 —— 这些也是单测的主要目标 |

**设计预览**：`docs/design/preview/index.html` + `preview.css` 是「方向 B：内容优先」改版的**静态视觉稿**
（纯 HTML/CSS，不含 React），用于动代码前对齐版式与信息密度；`global.css` 的令牌层是它的落地实现。
它不参与构建，只在改界面时作为参照。

---

## 附：快速定位指南

| 我想… | 从这里开始 |
| --- | --- |
| 加一个页面 | `pages/<Name>/index.tsx` + `App.tsx` 加 lazy 与 `<Protected>` 路由 + `AppShell` 的 `NavModule` / `GROUPS` |
| 改聊天阶段文案 / SSE 事件处理 | `store/agentStore.ts` 的 `STAGE_TEXT` / `api/agent.ts` 的 `EVENT_NAMES` 与 handlers |
| 改确认弹窗 | `components/PlanDiff/index.tsx` |
| 换地图底图/加引擎 | `components/MapView/` 下的 `MapProvider` 与两个 Provider |
| 改登录态逻辑 | `App.tsx` 的 `Protected` + `api/auth.ts` + `api/client.ts` 的 401 分流 |
| 改配色 | `styles/global.css` 的 `:root` + `main.tsx` 的 `PALETTE`（两处一起） |

**组件统计**：`src/components/` 共 **29 个文件** = **22 个组件**（`AppShell`、`AssistantNav`、`BudgetChart`、
`BudgetExecution`、`ChatPanel`、`CityDrawer`、`Collapse`、`CountUp`、`DraggableList`、`ErrorBoundary`、`ExpensePanel`、
`FootprintMap`、`ItineraryCard`、`MapView`、`PageTransition`、`PlanDiff`、`ReminderBell`、`Skeleton`、`SwipeRow`、
`Timeline`、`Toast`、`VoiceButton`）+ **7 个非组件文件**（3 个地图 Provider/接口、`markerElement.ts`、
`footprintData.ts`、2 个测试文件）。

**页面统计**：`src/pages/` 共 **16 个目录**；`App.tsx` 共 **17 条 `<Route>`** —— 15 条渲染页面组件
（14 个受保护页 + `/login`）、2 条重定向（`/schedule`、`/footprint`）、1 条通配兜底 `*`。
