# 前端质量门禁：ESLint / Prettier / 覆盖率

本文件讲清楚前端质量的「怎么跑、阈值多少、什么能豁免」。

> 状态：当前事实 | 适用版本：0.1.0（Vite 6 / ESLint 9 / Vitest 4.1） | 最后验证：2026-11-20（覆盖率阈值按 ratchet 上调） | 事实来源：`travel-agent-web/vitest.config.ts`、`travel-agent-web/eslint.config.js`、`travel-agent-web/.prettierrc.json`、`travel-agent-web/scripts/check-core-coverage.mjs`、`travel-agent-web/package.json` scripts

## 1. 本地命令（与 CI 相同）

以下命令均在前端目录 `travel-agent-web/` 下执行。

```bash
npm run typecheck        # tsc --noEmit
npm run lint             # eslint src（0 error 才过；warning 不阻断）
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
- 基线（2026-11-20 复核，447 个测试全绿后统计）：见下表；阈值 = 基线向下取整 2-3 个点，写在 `vitest.config.ts` 的 `coverage.thresholds` 与 `scripts/check-core-coverage.mjs` 的 `THRESHOLDS`。
  - 2026-11-19 首版基线为 224 个测试下的 lines 39.55 / branches 39.81 / functions 34.11 / statements 38.31（核心目录 66.73 / 66.42 / 54.21 / 64.57）。
  - 本轮提升主要来自新落的纯逻辑 + 单测：离线待同步队列（`lib/statusQueue.ts`、`store/statusQueueStore.ts`，各 98%+）、
    行程聚焦（`lib/tripFocus.ts`）、规划器草稿（`pages/Planner/planDraft.ts`）。**这是 ratchet 上调，不是下调**。
- **只允许上调**（ratchet）：新增/修改代码必须带测试；下调阈值必须在 PR 说明里给出具体文件与原因，并同步本表。
- 不追求全仓统一高数字：大量页面组件由 Playwright e2e 覆盖（`e2e/smoke.spec.ts`），单测占比天然偏低，全局阈值防的是「存量单测悄悄退步」；`src/store`、`src/api`、`src/lib` 的业务状态与请求封装单独用 `node scripts/check-core-coverage.mjs` 卡更高阈值。

| 范围 | 指标 | 基线（2026-11-20，447 tests） | 阈值 | 校验处 |
| --- | --- | --- | --- | --- |
| 全局 src/** | lines | 57.63% | ≥ 55% | `vitest.config.ts` thresholds |
| 全局 src/** | branches | 55.58% | ≥ 53% | 同上 |
| 全局 src/** | functions | 51.60% | ≥ 49% | 同上 |
| 全局 src/** | statements | 56.86% | ≥ 54% | 同上 |
| store/api/lib | lines | 79.21% | ≥ 76% | `scripts/check-core-coverage.mjs` |
| store/api/lib | branches | 76.72% | ≥ 74% | 同上 |
| store/api/lib | functions | 68.97% | ≥ 66% | 同上 |
| store/api/lib | statements | 78.18% | ≥ 75% | 同上 |

> `vitest.config.ts` 与 `check-core-coverage.mjs` 是唯一权威；表格随 ratchet 同步更新。
> 全局一行的数字取自 `coverage-summary.json` 的 `total.*.pct` 字段。reporter 输出到 stdout 的表和
> `covered/total` 直接算会差 0.01 个点（例如 lines：`pct` 39.55 而 `1267/3203 = 39.56`）；门槛按更低的
> `pct` 定，两者都远高于阈值，不影响判定。

## 3. ESLint 分工与豁免

- 配置：flat config（`eslint.config.js`），`@eslint/js` recommended + `typescript-eslint` recommended + `react-hooks` flat recommended + `react-refresh`；忽略 `dist`、`coverage`、`node_modules`、`src/api/generated`。
- `eslint.config.js` 只保留「能挡真实缺陷」的规则：`react-hooks`（依赖数组/顺序）、`react-refresh`（仅组件文件导出组件）、`no-undef`/`no-unused-vars`（tsc 之外的兜底）。
- 格式类规则（引号/缩进/分号）全部交给 Prettier，ESLint 不重复管。
- 关闭的规则与原因写在 `eslint.config.js` 注释里（`no-explicit-any`、`no-non-null-assertion` 等历史噪声）。**当前门禁是 0 error / 48 warning**（2026-11-20 复核实测）：
  - `react-hooks/set-state-in-effect` 21 处：页面「挂载即拉数据」的既有统一写法，改成 React Query / `useSyncExternalStore` 属跨页面重构；
  - `react-hooks/exhaustive-deps` 12 处、`react-hooks/refs` 8 处、`react-hooks/immutability` 2 处、`react-refresh/only-export-components` 4 处、未使用 disable 3 处。
  - 这些是**存量**噪声（均来自既有页面/组件的历史写法）；本轮新增文件 `lib/statusQueue.ts`、`lib/tripFocus.ts`、`store/statusQueueStore.ts`、
    `pages/Planner/planDraft.ts`、`components/UndoBar` 单独 eslint 为 **0 warning**，新代码仍应保持无告警。
  - 这些都是 **warning 不阻断**，新代码仍应写成无告警的形式。

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
| `npm run lint` 报 react-hooks 依赖缺失 | 修依赖数组或用 `useCallback`/`useRef` 收敛；不要 disable |
| `npm run lint` 有 error | 必须修；warning 允许存在（见第 3 节清单），但不得新增未说明的 disable |
| coverage 低于阈值 | 补测试；确认不是删除了测试导致的退步 |
| `check-core-coverage.mjs` 报「找不到 coverage-summary.json」 | 先跑 `npm run test:coverage`（顺序不能反） |
| format:check 失败 | `npm run format:write` 后重跑 typecheck + test；格式化应只有空白/换行 diff |
| tsc 与 eslint 对同一行都报错 | 先修类型，再看 lint 是否还有意义 |
| `npm run build:check` 以 `Segmentation fault`(139) 结束但已打印 `✓ built` + `PWA files generated` | Windows 下 npm 外壳偶发崩溃（构建本身成功）；直接补跑 `node scripts/check-bundle-budget.mjs` 确认预算项 |
