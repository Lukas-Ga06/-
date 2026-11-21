# 阶段 E 第一刀验收：共享空状态 + 自动化无障碍（2026-11-21）

> 快照日期：2026-11-21 ｜ 验证范围：五处空状态统一、axe 无障碍扫描（5 页面）、对比度修正 ｜ 环境：Windows / Node 20.17.0 / Playwright 1.64.1 / @axe-core/playwright 4.13 ｜ 结论：**空状态收敛完成、axe critical/serious = 0、e2e 13/13**；CSS 拆分与视觉回归基线未做

## 1. 交付内容

| 能力 | 实现 | 证据 |
| --- | --- | --- |
| 共享空状态 | `components/EmptyState`：标题 + 引导 + 唯一主操作（`role="status"`，图标装饰化）；替换 Reviews / Travel / Schedule / Footprint / Knowledge 五处 | 组件测试 3 条；页面测试保持通过 |
| 自动化无障碍 | `e2e/a11y.spec.ts`：登录 / 首页 / 助手 / 旅行 / 回顾，WCAG 2.0/2.1 A+AA，critical/serious 必须为 0 | e2e 5 条全绿 |
| 对比度修正 | antd 色板令牌三处（primary / success / textTertiary）+ `global.css` 末尾集中修正块（侧栏选中、Tab、筛选胶囊、进行中徽标、绿色 Tag、登录提示） | axe 首轮 5 页全红 → 修完 5/5 绿 |

## 2. 扫描发现（修复前）

| 节点 | 对比度 | 处理 |
| --- | --- | --- |
| `.login-tip` | 4.17（#1f6feb on #ebf4ff） | 文字改 `--accent-hover`（5.27） |
| antd 次要文字（登录条款） | 3.09（#8b93a1） | 令牌 `colorTextTertiary → #6b7280`（4.83） |
| 侧栏选中项 / 筛选胶囊 | 4.08 | 文字改 `--accent-hover`（5.15） |
| 选中 Tab | 4.32 | `colorPrimary → #1a5fd0`（5.45；白字 4.63→5.85） |
| 绿色 Tag（「第 1 天」） | 3.37 | `colorSuccess → #15803d`；`.ant-tag.ant-tag-green` 提高特异性（5.44） |
| 「进行中」徽标 | 3.89 | 文字 `#0f6b2f`（5.83） |
| 回顾列表待出发行 | 2.85 | 去掉整行 `opacity:.72`，改浅底 |
| 「Enter 发送」 | 3.26 | 改 `var(--text-3)` |

## 3. 误报与口径

首轮扫描 5 个页面全红，但其中大量节点在样式落定后并不违规：**axe 在 fade-up 过渡中途计算了中间色**。
spec 现在统一在 `networkidle` + 1s 之后再扫；这条经验写进注释，避免后人把误报当真实缺陷去改颜色。

## 4. 门禁实测

- `npm test`：**46 文件 / 468 用例全过**；覆盖率全局 57.52/56.88/52.37/58.29，核心目录 79.67/77.04/69.32/78.66。
- Playwright：**13/13 PASS**（8 条功能 + 5 条无障碍；39.2s）。
- `typecheck` / `lint` / `format` / `build:check` / `api:check` 全绿。

## 5. 仍待办

- 阶段 E 剩余：`global.css`（约 6.7k 行）页面级规则拆分、关键页面截图基线（视觉回归）。
- §8.6 最近会话列表（缺后端接口）、阶段 D 冲突提示。
- 真实环境验收（MySQL 实机、远端 CI、真实模型、iOS/Safari、弱网、多端并发）。