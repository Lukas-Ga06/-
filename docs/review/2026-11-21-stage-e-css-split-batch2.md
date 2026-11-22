# 阶段 E 第三刀验收：首页 / 行程 / 足迹的页面级样式拆分（2026-11-21）

> 快照日期：2026-11-21 ｜ 验证范围：`global.css` 剩余页面规则按组件归属逐条搬到 `home.css` / `trip.css` / `footprint.css`；规则级等价性与 11 张视觉基线复核 ｜ 环境：Windows / Node 20.17.0 / Playwright 1.64.1 / Chromium（1440×900 + 390×844）｜ 结论：**逐规则等价（0 丢失 / 0 声明变化）、11/11 视觉基线 0 像素差**；环境共享层（v5 覆盖块的极少数条目）按「必须同文件」留在 global，理由逐条列出

## 1. 承接关系

上一刀（[2026-11-21-stage-e-css-split-visual.md](2026-11-21-stage-e-css-split-visual.md)）只搬了「能被规则级证明安全」的页面独占段，
把剩下约 5.9k 行里的**共享层与历史覆盖层**留给了下一批，并写明它「不能按整段搬走，需要按组件归属逐条重建」。
本批做的就是这件事：首页、行程、足迹三个页面。

## 2. 交付内容

| 页面 | 新文件 | 行数 | 规则数 | 搬走的内容 |
| --- | --- | --- | --- | --- |
| 首页 | `src/pages/Home/home.css` | 220 | 33 | `.assistant-quick` 系、`.aq-*` 输入行、`.hero-grid` 根规则 + 1100/900px 媒体 + v5 覆盖、阶段 B 收敛段、左列待办/快捷入口、1080/720px 中只含首页的选择器 |
| 行程 | `src/pages/Trip/trip.css` | 176 | 24 | 行程工作台栅格与移动端分段视图、`.next-up` 卡片 + 1080/720px |
| 足迹 | `src/pages/Footprint/footprint.css` | 530 | 75 | 足迹页 900px 段、v6 深色 3D 地图块、v5 `.fp-visits`、城市抽屉照片墙、`.fp-theme-dark` 深色适配、v5 足迹浅色覆盖层、`.fp-canvas` 纸面组 |

`src/styles/global.css`：**5,964 → 5,090 行（−874，−14.7%）**
（交接时的一刀已把 6,721 降到 5,964；两刀合计 **−1,631 行，−24.3%**）

三个页面模块各自 `import './xxx.css'`（`Home/index.tsx:32`、`Trip/index.tsx:38`、`Footprint/index.tsx:22`），
随路由 chunk 加载。

## 3. 等价性论证

### 3.1 规则级 diff（主证据）

用 postcss 把「拆前 `git show HEAD:...global.css`」与「拆后 global + 三个页面文件」展开成
`(at-context, selector) → 声明序列` 的多重集比较：

```
拆前 global.css 规则数 : 793
丢失规则数             : 0
本批新增规则数         : 0
声明变化条数           : 0
同名选择器"末条声明"不一致: 0
```

**没有出现「搬走后覆盖顺序变了」的情况**，所以不需要像上一刀那样用「属性不相交」来辩解，
也不需要为某条规则单独写例外。核对脚本与输出留在 `.evidence/css-split-batch2/`
（`verify.cjs` / `split.cjs` / `report-rules.txt`），可复跑。

`.hero-grid` 是上一轮点名的风险点（v6 基础 + 1100/900px 媒体 + v5 覆盖）。最终**三条全部归 `home.css` 且保持原相对顺序**
—— 与其让它跨文件再论证「属性不相交」，不如让覆盖留在同一文件里，顺序本身就是证明。

### 3.2 像素级复核（辅助证据）

`npx playwright test -c playwright.visual.config.ts`，基线一张未改、`maxDiffPixelRatio: 0`：

**11/11 PASS，0 像素差。**

覆盖：登录、首页、旅行列表、行程详情（1440×900 + 390×844）、规划器、AI 助手、旅行回顾、攻略库、
设置·诊断、个人资料。

> 方法学备注：中途曾尝试「把 dist 入口 CSS 换成 HEAD 原始 global.css」来对照，那次实验是**无效**的
> —— 拆分后页面 CSS 是路由 chunk，仍然会加载，「HEAD global + 拆分页面 CSS」同时生效的结果
> 既不等于 HEAD 也不等于拆分版。本批最终没有采用这个对照，只用规则级 diff + 同一套基线比较。

## 4. 首屏收益

入口 CSS（`dist/index.html` 引用的那一个）：

| | 字节 | 说明 |
| --- | --- | --- |
| HEAD（未拆分） | 80,507 B | `-win32` 同机同版本构建 |
| 本批（拆分后） | 69,656 B | **−10,851 B（−13.5%）** |

搬走的类名已不在入口 CSS（抽样：`next-trip` / `today-row` / `aq-*` / `hero-grid` / `hero-side` /
`hg-head` / `hg-timeline` / `tl-chip` / `hss-item` / `recent-trip` / `fp-visits` / `fp-theme-dark` /
`city-stat` / `photo-empty` / `trip-workbench` / `trip-chat-body` / `trip-side-body` /
`trip-view-switch` / `next-up` / `nu-main`）。包体预算门禁 `npm run build:check` 全过。

## 5. 为什么有些规则必须留在 global

逐条实测得出的结论，不是「懒得搬」：

| 规则 | 留在 global 的原因 |
| --- | --- |
| `.hg-map` 两条（v6 基础 + v5 `height:380px`） | 基础与覆盖必须同文件且基础在前；搬走任一 → 首页地图塌高（实测 0px / 552px） |
| `.fp-canvas` 三条（基础 + v5 `box-shadow` + `::after`） | 同上，v5 覆盖仍在 global |
| `.footprint-slim` / `.footprint-slim-map` | 与 `.hg-map` 的 380px 覆盖同文件才能保住覆盖 |
| `.mini-tag` v5 覆盖 | **全局类名**，旅行列表页也在用；搬进 `trip.css` 后列表页徽标丢覆盖（实测 5px 差） |
| `.fp-page … .fp-state-text` 足迹页骨架段 | vite 会把 `footprint.css` 拆成入口 + 页面两个 chunk，首帧缺样式（实测提示标签偏移 2px） |
| `.assistant-quick` 基础规则 | v5「卡片与面板」组里含它且在其后（圆角 10px ↔ 14px） |
| `.dash-card` / `.dc-link` / `.block-link` / `.pending-row` / `.today-strip` | **一条都未搬**：它们是 dash-card 通用件，旅行列表与首页都在用 |

这七类是「拆了会坏、且坏了不报错」的典型（CSS 不会报错，只会悄悄塌高、丢覆盖）。
把它们留在 global 是**有意的收敛**，写在这里是为了下一个想继续拆的人不必重新踩一遍。

## 6. 门禁（本轮实际执行）

| 命令 | 结果 |
| --- | --- |
| `npm run typecheck` | 通过 |
| `npm run lint` | **0 error**（48 warning 均为既有） |
| `npm run format:check` | 全部符合 |
| `npm run test:coverage` | **48 文件 / 500 用例全过**，全局行覆盖 58.11% |
| `node scripts/check-core-coverage.mjs` | 核心目录四条阈值全达标（行 80.78% / 分支 77.65% / 函数 69.82% / 语句 79.58%） |
| `npm run build:check` | 构建成功，包体预算全过 |
| `npm run api:check` | paths=54 / schemas=103，无漂移 |
| `npm run test:visual` | **11/11 PASS，0 像素差** |
| `npx playwright test`（主 e2e） | **13 passed** |

## 7. 边界

- 视觉基线仍带 `-win32` 平台后缀，**只在 Windows 生成**；Linux runner 字体渲染不同，不能直接复用，
  因此该套件暂不进 CI 阻塞路径（原因与接入方案见 [docs/guide/ci.md](../guide/ci.md)）。
- 本轮只处理三个页面。global 里剩下的主要是**真正跨页共享**的外壳与组件规则，
  继续拆的收益低于风险 —— 建议到此为止，除非有明确的包体指标需要。
- 首次访问某个路由会多一个 CSS chunk 请求（HTTP/2 下与既有 JS chunk 同源，实测视觉与 e2e 无回归）；
  PWA precache 项数变化已由 `build:check` 的 2.30 MiB 预算覆盖（当前 79 项 / 2.09 MiB）。