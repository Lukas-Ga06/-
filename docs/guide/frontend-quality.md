# 前端质量门禁：ESLint / Prettier / 覆盖率

本文件讲清楚前端质量的「怎么跑、阈值多少、什么能豁免」。

> 状态：当前事实 | 适用版本：0.1.0（Vite 6 / ESLint 9 / Vitest 4.1） | 最后验证：2026-11-27（ESLint 存量 47 warning 清零 + CI 加 `--max-warnings 0`） | 事实来源：`travel-agent-web/vitest.config.ts`、`travel-agent-web/eslint.config.js`、`travel-agent-web/.prettierrc.json`、`travel-agent-web/scripts/check-core-coverage.mjs`、`travel-agent-web/package.json` scripts

## 1. 本地命令（与 CI 相同）

以下命令均在前端目录 `travel-agent-web/` 下执行。

```bash
npm run typecheck        # tsc --noEmit
npm run lint             # eslint src（0 error 才过；本地口径，warning 不阻断）
npm run lint:ci          # eslint src --max-warnings 0（CI 门禁：warning 也算失败，阈值只降不升）
npm run format:check     # prettier --check（CI 门禁）
npm run format:write     # 本地一键格式化
npm test                 # vitest run（单元测试）
npm run test:coverage    # vitest run --coverage（全局阈值不达标即 exit 1）
npm run build:check      # tsc + vite build + 包体预算（见 frontend-performance.md）
node scripts/check-core-coverage.mjs   # 核心目录阈值，需先跑 test:coverage
```

> `check-core-coverage.mjs` **不在** `test:coverage` 里自动执行（Vitest 只支持全局阈值），
> 它读 `coverage/coverage-summary.json`，所以顺序固定为 `test:coverage` → `check-core-coverage`。

### 只检查改动过的文件

全量跑一次约 40-75s，改单个文件时可以先只查改动范围：

```bash
# 只看本次改动的源码文件（本仓库无 remote，基准用 HEAD；有基准分支时换成 origin/main）
cd travel-agent-web
git -C .. diff --name-only --diff-filter=ACM HEAD -- 'travel-agent-web/src/**' \
  | sed 's#^travel-agent-web/##' > /tmp/changed.txt

npx prettier --check $(grep -E '\.(ts|tsx|css)$' /tmp/changed.txt)
npx eslint $(grep -E '\.(ts|tsx)$' /tmp/changed.txt)

# 只跑与改动相关的测试（按文件名子串匹配）
npx vitest run src/store/agentStore   # 或 `npx vitest` 交互式 watch
```

只查改动范围**不能替代**提交前的全量门禁：覆盖率是全局指标，任何改动都必须在全量 `test:coverage` 下仍达标。

> Windows 下 `git diff` 会顺带打印 `LF will be replaced by CRLF` 警告，这是 `core.autocrlf` 的提示，不影响文件列表；
> 仓库内格式契约是 **LF**（`.prettierrc.json` 的 `endOfLine: "lf"`），提交时以 Prettier 为准。

## 2. 覆盖率基线与 ratchet

- 工具：`@vitest/coverage-v8`，reporter `text` + `json-summary` + `html`，产物在 `travel-agent-web/coverage/`；范围 `src/**/*.{ts,tsx}`，排除 `**/*.test.*`、`src/test/**`、`src/main.tsx`、`src/api/generated/**`、`**/*.d.ts`、`src/types/**`（纯类型）（见 `vitest.config.ts`）。
- 基线（2026-11-21 复核，490 个测试全绿后统计）：见下表；阈值 = 基线向下取整 2-3 个点，写在 `vitest.config.ts` 的 `coverage.thresholds` 与 `scripts/check-core-coverage.mjs` 的 `THRESHOLDS`。
  - 2026-11-19 首版基线为 224 个测试下的 lines 39.55 / branches 39.81 / functions 34.11 / statements 38.31（核心目录 66.73 / 66.42 / 54.21 / 64.57）。
  - 本轮提升主要来自新落的纯逻辑 + 单测：离线待同步队列（`lib/statusQueue.ts`、`store/statusQueueStore.ts`，各 98%+）、
    行程聚焦（`lib/tripFocus.ts`）、规划器草稿（`pages/Planner/planDraft.ts`）。**这是 ratchet 上调，不是下调**。
- **只允许上调**（ratchet）：新增/修改代码必须带测试；下调阈值必须在 PR 说明里给出具体文件与原因，并同步本表。
- 不追求全仓统一高数字：大量页面组件由 Playwright e2e 覆盖（`e2e/smoke.spec.ts`），单测占比天然偏低，全局阈值防的是「存量单测悄悄退步」；`src/store`、`src/api`、`src/lib` 的业务状态与请求封装单独用 `node scripts/check-core-coverage.mjs` 卡更高阈值。

| 范围 | 指标 | 基线（2026-11-21 第二次 ratchet，490 tests） | 阈值 | 校验处 |
| --- | --- | --- | --- | --- |
| 全局 src/** | lines | 58.81% | ≥ 56% | `vitest.config.ts` thresholds |
| 全局 src/** | branches | 57.19% | ≥ 54% | 同上 |
| 全局 src/** | functions | 52.91% | ≥ 50% | 同上 |
| 全局 src/** | statements | 57.94% | ≥ 55% | 同上 |
| store/api/lib | lines | 80.55% | ≥ 78% | `scripts/check-core-coverage.mjs` |
| store/api/lib | branches | 77.35% | ≥ 75% | 同上 |
| store/api/lib | functions | 69.71% | ≥ 68% | 同上 |
| store/api/lib | statements | 79.34% | ≥ 77% | 同上 |

> `vitest.config.ts` 与 `check-core-coverage.mjs` 是唯一权威；表格随 ratchet 同步更新。
> 全局一行的数字取自 `coverage-summary.json` 的 `total.*.pct` 字段。reporter 输出到 stdout 的表和
> `covered/total` 直接算会差 0.01 个点（例如 lines：`pct` 39.55 而 `1267/3203 = 39.56`）；门槛按更低的
> `pct` 定，两者都远高于阈值，不影响判定。

## 3. ESLint 分工与豁免

- 配置：flat config（`eslint.config.js`），`@eslint/js` recommended + `typescript-eslint` recommended + `react-hooks` flat recommended + `react-refresh`；忽略 `dist`、`coverage`、`node_modules`、`src/api/generated`。
- `eslint.config.js` 只保留「能挡真实缺陷」的规则：`react-hooks`（依赖数组/顺序）、`react-refresh`（仅组件文件导出组件）、`no-undef`/`no-unused-vars`（tsc 之外的兜底）。
- 格式类规则（引号/缩进/分号）全部交给 Prettier，ESLint 不重复管。
- 关闭的规则与原因写在 `eslint.config.js` 注释里（`no-explicit-any`、`no-non-null-assertion` 等历史噪声）。
- **当前门禁是 0 error / 0 warning**（2026-11-27 实测，`npm run lint` 无输出）。存量 47 条已在本轮清零，逐规则构成与处置见下表：

| 规则 | 清零前 | 现在 | 处置 |
| --- | --- | --- | --- |
| `react-hooks/set-state-in-effect` | 21 | 0 | 15 处结构性修复（`useSyncExternalStore`、渲染期收敛 state、拆「只请求」与「先切加载态」两个入口），6 处带原因行内豁免（见 §3.1） |
| `react-hooks/exhaustive-deps` | 11 | 0 | 9 处补齐依赖（都是 zustand action / `navigate` / antd `message` 这类稳定引用）；`FootprintMap` 4 处按重建键显式豁免，`useCallback` 缺项改为补 `useCallback` 依赖 |
| `react-hooks/refs` | 6 | 0 | 渲染期写 ref 全部移入 effect（`FootprintMap` 5 处、`ReminderBell` 1 处），行为等价：这些 ref 只在异步回调里读 |
| `react-hooks/immutability` | 2 | 0 | `CountUp` 把 `animate` 声明移到使用之前；`Assistant/index.test.tsx` 用探针节点替代「渲染期写模块级变量」 |
| `react-refresh/only-export-components` | 4 | 0 | 拆出非组件导出：`Timeline/timelineText.ts`、`Toast/toastStore.ts`、`src/Root.tsx`（`main.tsx` 只留入口渲染） |
| 未使用的 `eslint-disable` | 3 | 0 | 删 2 处真失效（`Footprint`、`Reviews` 的取数 effect 已不需要依赖豁免）；`FootprintMap` 1 处是注释放错行（原因行夹在 `disable-next-line` 与代码之间），改正后生效 |

- CI 用 `npm run lint:ci`（= `eslint src --max-warnings 0`）作为**硬门禁**；`npm run lint` 保持原语义（只看 error），本地日常仍然可用。阈值只降不升——现在是 0，**没有任何上调空间**。
- 新增文件必须 0 warning；新增 `eslint-disable` 一律带 ` -- ` 原因（见 §3.1），并在本文件登记。

### 3.1 现存豁免（唯一清单，共 6 处）

全部是 `react-hooks/set-state-in-effect`，同一类问题：**「取数入口在请求前同步切加载态」**。彻底消除需要把页面取数收敛到统一数据层（React Query / `useSyncExternalStore` store），属跨页面重构，不在质量卡范围内。新增豁免必须在此表登记，否则不允许合入。

| 文件:行 | 场景 | 为什么不能就地消掉 |
| --- | --- | --- |
| `src/components/AgentRunsDrawer/index.tsx:288` | 打开抽屉即拉运行列表 | 打开动作本身就是取数触发点，`else` 分支还要清理详情态 |
| `src/components/CityDrawer/index.tsx:95` | 切换城市先清空旧照片 | `photos` 还要被上传/删除就地改写，不能退化成派生值 |
| `src/components/ConversationDrawer/index.tsx:91` | 每次打开重拉会话列表 | 打开即取数（跨标签页可能已变），缓存会让列表说谎 |
| `src/components/ExpensePanel/index.tsx:118` | `tripId` 变化重拉预算明细 | 依赖变化即取数，含同步切骨架屏 |
| `src/pages/Memos/index.tsx:61` | 挂载拉第一页备忘 | 与翻页共用 `fetchPage`，挂载路径与用户翻页共用同步进位 |
| `src/pages/TripReview/index.tsx:95` | `tripId` 变化重拉回顾 | 依赖变化即取数，含同步切骨架屏 |

> 本轮已结构性修掉 15 处同类问题（`Footprint`/`Reviews`/`Schedule`/`Workbench`/`Home` 拆「只请求」与「先切加载态」两个入口；`MapView`/`Travel`/`ChatPanel`/`PlanDiff`/`OfflineBanner`/`PageTransition` 改为渲染期收敛 state；`useAccessTicket` 把「未登录」改成返回值而不是写 state）。**新代码不许再新增这一类**：挂载/依赖变化取数时，加载态初值直接给 `true`，请求前不 setState。

### 豁免流程

1. 优先改代码：依赖数组缺项用 `useCallback`/`useMemo` 收敛，或把回调移进 effect 内部。
2. 确实无法消除时，**行内**豁免并写明原因，原因用 ` -- ` 隔开（`--` 之后是说明，不是规则名）：

   ```ts
   // eslint-disable-next-line react-hooks/exhaustive-deps -- 只在 tripId 变化时重新加载，其余 handler 每次渲染都会重建
   ```

3. **禁止整文件** `/* eslint-disable */`、禁止为了过门禁而关掉规则；`no-undef`、`no-loss-of-precision` 这类真实错误不允许豁免（现有 `src/lib/coords.ts` 的 `no-loss-of-precision` 带原因说明，属特例）。

## 4. Prettier

- 配置：singleQuote、printWidth 100、trailingComma all、semi true、endOfLine lf（`.prettierrc.json`）。
- `.prettierignore` 排除构建产物、coverage、生成代码（`src/api/generated`）与 lockfile。
- CI 跑 `format:check`；本地改完直接 `npm run format:write`。

## 5. 常见失败处理

| 现象 | 处理 |
| --- | --- |
| `npm run lint` 报 react-hooks 依赖缺失 | 修依赖数组或用 `useCallback`/`useRef` 收敛；不要 disable。store action / `navigate` / antd `message` 是稳定引用，直接补进依赖数组 |
| `npm run lint:ci` 因 warning 失败 | 本地 `npm run lint` 不阻断、CI 阻断是刻意设计；按 §3 表格消掉，确需豁免走 §3.1 登记 |
| `npm run lint` 有 error | 必须修；warning 也必须为 0（见 §3），豁免只能来自 §3.1 |
| coverage 低于阈值 | 补测试；确认不是删除了测试导致的退步 |
| `check-core-coverage.mjs` 报「找不到 coverage-summary.json」 | 先跑 `npm run test:coverage`（顺序不能反） |
| format:check 失败 | `npm run format:write` 后重跑 typecheck + test；格式化应只有空白/换行 diff |
| tsc 与 eslint 对同一行都报错 | 先修类型，再看 lint 是否还有意义 |
| `npm run build:check` 以 `Segmentation fault`(139) 结束但已打印 `✓ built` + `PWA files generated` | Windows 下 npm 外壳偶发崩溃（构建本身成功）；直接补跑 `node scripts/check-bundle-budget.mjs` 确认预算项 |

## 6. 视觉回归（截图基线）

`global.css` 已按职责拆成固定顺序的多个样式文件（`tokens / shell / buttons / cards / status / shared / components / animations`
+ 页面级 CSS，见 [前端结构](frontend.md) §2.1）。「只是搬家」不能靠肉眼确认。仓库因此有一份**独立的截图基线**套件：

- 配置：`playwright.visual.config.ts`（与 `playwright.config.ts` 解耦；后者用 `testIgnore` 排除 `e2e/visual`）；
- 用例：`e2e/visual/pages.spec.ts` —— 登录、首页、旅行列表、行程详情（1440×900）、规划器、
  AI 助手、旅行回顾、攻略库、设置·诊断、个人资料，共 10 张；
- 桩数据：`e2e/visual/fixtures.ts`，接管 `/api/**`，**不需要后端 / 数据库 / 真实模型**；
- 确定性：`page.clock.setFixedTime()` 冻结时间、外网请求全部 abort（地图按失败降级）、
  `animations: 'disabled'`、`maxDiffPixelRatio: 0`；
- 命令：`npm run test:visual`（比较，失败会在 `test-results/` 留下 expected/actual/diff）、
  `npm run test:visual:update`（**有意**改视觉时重生成基线，必须人工看一遍图片 diff 再提交）。

**CI 里的两套基线**：基线文件名带平台后缀，Windows 与 Linux 的字体渲染不同，同一张图无法跨平台复用。
2026-11-24 起仓库同时保存两套：`-chromium-win32`（Windows 本机人工回归）与 `-chromium-linux`
（由 [visual-baseline.yml](../../.github/workflows/visual-baseline.yml) 在 Playwright 固定容器 `v1.64.1-jammy`
生成，保证生成与比较用同一镜像）。CI 的 `visual` job 检测到 Linux 基线后由「跳过」转为逐像素比较，
目前仍是观察项（`continue-on-error: true`），观察稳定后再转硬门禁。

拆分前后的验收方式：先在拆前代码上生成基线，再拆分、重建、用**同一套基线**比较 ——
11/11 像素一致才算「等价迁移」，详见 [阶段 E 第二刀快照](../review/2026-11-21-stage-e-css-split-visual.md)。
