# 首屏 JS 预算摸底与依赖归因（2026-11-23）

> 快照日期：2026-11-23 ｜ 验证范围：生产构建首屏单 chunk 的依赖归因、可分割性实验 ｜ 环境：Windows 11 / Node 20.17 / Vite 6.4.5 / `travel-agent-web` ｜ 结论：首屏 **597,968 / 600,000 B（余 2,032 B）**；大头是 antd、axios、react-dom、react-router；「表单栈单独成块」实验让首屏涨到 684 KB，已回退；**v1 前不得新增首屏依赖**。

## 1. 口径

- 门禁：`npm run build:check` → `scripts/check-bundle-budget.mjs`，统计 `index.html` 的 `<script src>` **与** `<link rel="modulepreload">`。
- 归因：临时在 `vite.config.ts` 的 visualizer 条件追加 `|| process.env.ANALYZE === '1'`，用 `ANALYZE=1 npm run build` 走**生产模式**构建（`--mode analyze` 会带开发版 React，不能用于预算判断）；构建后配置已还原，不提交。
- `renderedLength` 是压缩前的模块源码量。整包 1,185,201 raw → 597,834 B 成品，比例 **0.504**；下表「估 min」= raw × 0.504，只用于排序与量级，不是精确字节。
- 首屏实际加载：`index-Dpww5kD6.js` 597,834 B + `registerSW.js` 134 B = **597,968 B**；余量 **2,032 B**。

## 2. 包级归因（入口 chunk：1,185,201 raw / 504 个模块）

| 包 | raw B | raw% | 估 min B | 模块数 |
| --- | ---: | ---: | ---: | ---: |
| antd | 304,351 | 25.7% | 153,519 | 141 |
| axios | 161,614 | 13.6% | 81,520 | 55 |
| react-dom | 134,902 | 11.4% | 68,046 | 3 |
| react-router | 88,179 | 7.4% | 44,478 | 1 |
| 自有源码 `src/` | 99,447 | 8.4% | 50,162 | 26 |
| rc-field-form | 81,285 | 6.9% | 41,001 | 15 |
| @ant-design/cssinjs | 39,088 | 3.3% | 19,716 | 17 |
| @rc-component/async-validator | 36,071 | 3.0% | 18,194 | 27 |
| rc-util | 33,783 | 2.9% | 17,040 | 27 |
| rc-motion | 29,193 | 2.5% | 14,725 | 12 |
| rc-notification | 22,047 | 1.9% | 11,120 | 6 |
| @babel/runtime | 21,444 | 1.8% | 10,816 | 47 |
| @ant-design/cssinjs-utils | 20,883 | 1.8% | 10,533 | 12 |
| rc-dialog | 16,793 | 1.4% | 8,470 | 8 |
| @ant-design/fast-color | 13,729 | 1.2% | 6,925 | 1 |

## 3. 重点组件（估 min）

| 依赖 | raw B | 估 min B | 备注 |
| --- | ---: | ---: | --- |
| `antd/es/result`（403/404/500 三态） | 42,988 | 21,683 | App 的加载/错误兜底 + ErrorBoundary，首屏常驻 |
| `antd/es/modal` | 40,686 | 20,522 | 注销确认、计划预览等；入口里只有 style + Modal 壳 |
| `antd/es/button` | 38,145 | 19,240 | 导航、错误页、主操作 |
| `antd/es/notification` | 22,263 | 11,229 | 全局通知，被 Toast 封装 |
| `antd/es/skeleton` | 20,517 | 10,349 | 页面加载骨架 |
| `antd/es/spin` | 16,944 | 8,546 | 路由 Suspense 兜底 |
| `antd/es/theme` + `config-provider` | 46,927 | 23,670 | 主题令牌与 ConfigProvider |
| `rc-field-form` + `async-validator` | 117,356 | 59,195 | 表单栈（Planner / Profile / 登录等共享） |
| `axios` 全家桶 | 161,614 | 81,520 | `src/api/client.ts` 及所有 api 模块 |
| `react-dom` | 134,902 | 68,046 | 不可减 |
| `react-router`（含 dom 包共享部分） | 88,179 | 44,478 | 不可减 |

## 4. 为什么「共享依赖」会进入口

所有页面（首页、规划器、助手、行程……）都是 `React.lazy()` 动态导入。被 **≥2 个懒路由共享**的模块，Rollup 只能放到唯一静态 chunk（入口），于是 antd 控件、表单栈、axios 等"并非首屏马上用到"的代码都进了入口。

这解释了矛盾：入口 597 KB，但首屏真正渲染的只有登录页/外壳。预算按入口计算，所以**余量是结构性的**，不是某个大组件突然变胖。

## 5. 可分割性实验：表单栈单独成块（已回退）

- 假设：把 `rc-field-form`、`@rc-component/async-validator`、`antd/es/form` 用 `manualChunks` 拆成 `form-vendor`。
- 实测：入口降到 **289,232 B**，但 `index.html` 出现 `modulepreload form-vendor-E8MqOR5x.js`（394,991 B），首屏合计 **684,223 B**，超预算 84 KB。
- 原因：antd Form 依赖 config-provider/theme/button 等，拆出去会把公共控件一起拖走；入口仍静态引用 `form-vendor`，`modulepreload` 又被预算脚本计入，拆分没有把代码移出首屏。
- 结论：**不能靠 manualChunks 降低首屏**。配置已回退，dist 重建回 597,968 B。`modulepreload` 计入预算是正确的，它堵住了"拆文件绕过"的口子。

## 6. 可减项排序（v1 不做，记录给 v1.1）

| 优先 | 动作 | 预计节省（估 min） | 风险 / 前提 |
| --- | --- | ---: | --- |
| 1 | `axios` → 轻量 fetch 封装 | ~81.5 KB | 影响全部 api 与错误处理；工作量大，单列专项 |
| 2 | antd `Result` 兜底页换成轻量组件 | ~21.7 KB | App 加载/错误 + ErrorBoundary；需更新视觉基线 |
| 3 | `Modal` 拆到真正按需的页面 | ~20.5 KB | 注销确认等交互依赖，需要先拆页面结构 |
| 4 | `Skeleton` / `notification` / `message` 轻量化 | ~10.3 / 11.2 / 6.0 KB | 收益小，优先级低 |
| 5 | 表单栈（rc-field-form 59.2 KB） | ~59.2 KB | 需要表单页成为真正独立的异步依赖；当前 Rollup 共享策略下做不到 |
| — | react-dom / react-router | 不可减 | 框架基线 |

## 7. 约束与门禁（写给后续 PR）

- **不调阈值**：`entryJs=600_000` 是约束，不是可调参数。
- 任何新增首屏 import 前先跑 `npm run build:check`，PR 里贴「首屏前后差值」。
- 首屏相关改动必须同时跑 `npm run test:visual`（11 张）与 e2e；不能把 `modulepreload` 当作"不计入首屏"。
- 若要动 `manualChunks`，验收标准是 `index.html` 的 `script + modulepreload` 总量下降，而不是入口文件名变小。

## 8. 复现

```bash
cd travel-agent-web
# 1) 临时在 vite.config.ts 的 visualizer 条件追加： || process.env.ANALYZE === '1'
ANALYZE=1 npm run build          # 生产模式 + dist/bundle-stats.html
# 2) 还原 vite.config.ts 后：
npm run build:check              # 597,968 / 600,000 B，2,032 B 余量
# 3) 归因脚本按 nodeMetas[*].moduleParts 聚合入口 chunk（见本快照 §2 口径）
```