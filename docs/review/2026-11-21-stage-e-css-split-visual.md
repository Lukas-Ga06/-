# 阶段 E 第二刀验收：global.css 页面级拆分 + 视觉回归基线（2026-11-21）

> 快照日期：2026-11-21 ｜ 验证范围：页面级样式从 `global.css` 拆出、截图基线（11 个关键页面）｜ 环境：Windows / Node 20.17.0 / Playwright 1.64.1 / Chromium ｜ 结论：**首批拆分逐像素等价、视觉回归基线可用**；剩余共享层拆分批推进，真机与 CI 基线未验

## 1. 为什么拆、拆到什么程度

`global.css` 拆前 **6,721 行**，三类规则混在一起：

1. 设计令牌与外壳（`:root`、侧栏、底栏、离线条）—— 必须全局；
2. 通用组件（卡片、Tag、骨架、Toast、SwipeRow、时间轴）—— 跨页面复用；
3. 页面级规则（登录、攻略库、回顾、诊断、个人中心…）—— 只有打开该页才需要。

第 3 类在首屏 CSS 里是纯负担，也正是「历史覆盖」最多的地方。本轮只搬第 3 类中**能被规则级证明安全**的部分：
页面独占类名 + 不与其它段同优先级覆盖。判定标准写死在临时脚本里（拆分后已删除），
结论用 postcss 逐规则核对（见 §3），不靠肉眼。

## 2. 交付内容

| 页面 | 新文件 | 行数（含文件头） | 来源 |
| --- | --- | --- | --- |
| 登录 | `src/pages/Login/login.css` | 199 | `global.css` 登录段 + 「去掉玻璃卡片」覆盖 + 720px 窄屏 + AA 对比度修正 |
| 攻略库 | `src/pages/Knowledge/knowledge.css` | 142 | 「Knowledge：旅行知识库」整段（含 960px 窄屏） |
| 旅行回顾 | `src/pages/Reviews/reviews.css` | 273 | 「P1 旅行回顾」整段 + 原末尾无障碍块里的 `.review-counts .ant-tag` |
| 设置 · 诊断 | `src/pages/Workbench/workbench.css` | 102 | 「Workbench：Agent 运行观察」整段 |
| 个人中心 | `src/pages/Profile/profile.css` | 52 | 资料卡 / 头像 / 统计栅格（共享的 `.stat-box` 留在 global） |
| 规划页 | `src/pages/Planner/planner.css`（追加） | +29 | 单页骨架 `.planner-page/.planner-body/.planner-card` |

`src/styles/global.css`：**6,721 → 5,964 行（−757，−11.3%）**。每个页面文件在自己的页面模块里 `import`，
随路由 chunk 加载：构建产物中 `.login-page` 只出现在登录页的 CSS 资源里，入口 CSS 里为 0 处。

## 3. 等价性论证（为什么要相信「只是搬家」）

1. **规则级 diff**：用 postcss 把拆前（`git show HEAD:...global.css`）与拆后「global + 6 个页面文件」
   展开成 `(at-context, selector) → 声明序列` 的多重集比较：
   - 丢失规则：**0**；
   - 声明变化的只有 `.profile-head`：原 global 里 2 条规则（先 flex 布局、后卡片底色）拆到两个文件后顺序互换；
     两组声明**属性不相交**（`display/align-items/gap/margin` vs `background/border/border-radius/box-shadow`），
     因此渲染结果不变；
   - 其余差异来自 `planner.css` 原有的 `.plan3-*` 规则（本来就不在 global），不是漂移。
2. **像素级证明**：先对拆前代码（HEAD）构建并生成 11 张基线，再拆分、重建、用**同一套基线**比较：
   **11/11 PASS，0 像素差**（`maxDiffPixelRatio: 0`）。
3. **门禁**：`typecheck` / `lint`（0 error）/ `format:check` / `build:check`（包体预算全过）/ `npm test`
   （47 文件 **482** 用例）全绿。

## 4. 视觉回归基线

- 配置：`playwright.visual.config.ts`（独立于 `playwright.config.ts`，后者用 `testIgnore` 排除 `e2e/visual`）；
  spec：`e2e/visual/pages.spec.ts`；桩数据：`e2e/visual/fixtures.ts`；
  基线：`e2e/visual/pages.spec.ts-snapshots/*-chromium-win32.png`（11 张，共约 800 KB）。
- 覆盖页面：登录、首页、旅行列表、行程详情（桌面 1440×900 + 移动 390×844）、规划器、AI 助手、
  旅行回顾、攻略库、设置·诊断、个人资料。
- 确定性来源：
  - `/api/**` 全部由 Playwright 路由桩接管，**不需要后端、MySQL、真实模型**；
  - `page.clock.setFixedTime('2026-11-21T10:00+08:00')`，冻结「今天 / 还有 21 天出发」；
  - 外网请求一律 abort，地图按「加载失败」降级 —— 基线只覆盖应用自身布局；
  - `toHaveScreenshot({ maxDiffPixelRatio: 0, animations: 'disabled', caret: 'hide' })`。
- 用法：`npm run test:visual`（比较）/ `npm run test:visual:update`（有意改视觉时重生成）。

## 5. 边界与仍待办

- **基线带 `-win32` 平台后缀，只在 Windows 生成**；Linux/macOS 字体渲染不同，不能直接复用。
  因此本套件**暂不进 CI 门禁**，只作为本地/发布前的人工回归工具；接入 CI 需要按平台各存一套基线（或容器化）。
- 剩余约 5.9k 行里的**共享层与历史覆盖层**（v5 覆盖块约 1,700 行、首页/行程/足迹页规则）
  与多页交叉引用，不能按「整段搬走」处理，需要按组件归属逐条重建，留待下一批。
- 本轮**未实跑** `npm run e2e`（需要后端登录与数据库），e2e 结论沿用上一份快照；
  真机（iOS/Safari、弱网、多端并发）仍未验。