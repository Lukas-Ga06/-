# 真机、弱网与多端并发验收：能做的做了，做不到的写清为什么（2026-11-21）

> 快照日期：2026-11-21 ｜ 验证范围：Playwright WebKit（iPhone 13 预设）移动端、Chromium CDP 弱网/离线、同账号多端并发；顺带修掉一处无障碍门禁失效 ｜ 环境：Windows / Node 20.17.0 / Playwright 1.64.1（webkit 27.2、chromium）/ 后端 H2（`RATE_LIMIT_ENABLED=false`）/ `vite preview` 4173 ｜ 结论：**WebKit 与弱网两套真跑通（8 条新增用例全过）；iOS 真机不可验（本机无 Safari、无真机通道）；a11y 门禁此前是「靠运气全绿」，已修成确定性门禁并修掉真实对比度缺陷**

## 1. 为什么先划边界

「真机验收」这句话里塞了四件难度完全不同的事，混在一起谈就会把「模拟器通过」写成「真机通过」。先把可验证性摊开：

| 项 | 判定 | 依据 |
| --- | --- | --- |
| Playwright WebKit + iPhone 13 预设 | **可做（已做）** | `webkit-2370` 与 `webkit-2388` 两套都已安装；实测 `browser.version = 27.2`，UA 为 `...iPhone OS 15_0...Version/27.2 Mobile/15E148 Safari/604.1`，视口 390×664、DPR 3 |
| iOS 真机 Safari | **不可做** | Windows 上 Safari 已停止发布（`C:\Program Files\Safari` 不存在）；`adb devices` 无设备；无 `xcrun`/`idevice_id`。缺的不是配置，是一台 Mac + 真 iPhone |
| Chromium CDP 弱网 / 离线 | **可做（已做）** | `Network.emulateNetworkConditions` 生效：Slow-3G（400ms 延迟）下首屏 8.3s；`offline: true` 时 `navigator.onLine === false` 且 `OfflineBanner` 出现 |
| WebKit 上的弱网 | **不可做** | `newCDPSession` 在 WebKit 报 `CDP session is only available in Chromium`。替代方案只有 `context.route()` 做「首字节延迟/挂起」，做不出带宽形态 |
| 真机并发（两台物理设备） | **不可做** | 同上，无设备通道 |
| 等价并发（同账号两个独立 browser context） | **可做（已做）** | 两个 `browserContext` 各自独立 Cookie 罐，验证「互不阻塞、记忆不串号、无 5xx」 |

**结论口径**：下面凡写「移动端通过」，指的都是 **WebKit 引擎 + iPhone 视口/UA 预设**，不是 iOS 真机。

## 2. 新增用例与实跑结果

新增 5 个文件（`travel-agent-web/e2e/`）：`mobile-safari.spec.ts`、`network-conditions.spec.ts`、
`multi-device.spec.ts`、`support-device.ts`（共享工具）、`run-suite.mjs`（跨平台套件启动器，
因为 Windows 的 `cmd` 不支持 `VAR=x cmd` 前缀，而项目不想为此引入 cross-env）。
`playwright.config.ts` 增加 `E2E_SUITE` 分套件与 3 个 project；`package.json` 增加 4 条 script。

| 套件 | 用例 | 结果 |
| --- | --- | --- |
| `test:e2e:mobile` | ① 首页渲染且**无横向溢出**（`scrollWidth ≤ innerWidth`，含逐元素右边界扫描）② 底栏 4 个入口可见可点、`aria-current` 正确、触控区 ≥44px、切换行程/助手 ③ 行程详情时间轴可用 + 分段视图切 AI + 最近会话抽屉夹在 390px 内 | **3 passed (8.7s)** |
| `test:e2e:network` | ① Slow-3G 首屏 <20s（实测 **8.3s**，连跑 4 次一致）② 硬离线时 `OfflineBanner` 出现、不白屏、站内可用、恢复后消失 ③ 已知边界：屏蔽 SW 时离线跳懒加载路由会命中 ErrorBoundary | **3 passed (13.9s)** |
| `test:e2e:multi` | ① 同账号两 context 并发对话：互不阻塞、记忆不串号、无 5xx ② 两端各自渲染首页且未掉登录 | **2 passed (6.0s)** |
| 汇总 `test:e2e:device` | 13 条既有 + 8 条新增 | **21 passed (1.1m)** |

稳定性：每个新套件用独立 `--output` 目录连跑 3 轮全部通过。
主 e2e 仍只认领 13 条 Chromium 桌面用例（`npx playwright test` → 13 passed），**没有变慢、没有变红**。

CI 落地：新增 `.github/workflows/ci.yml` 的 `e2e-device` job（`continue-on-error: true`，观察期），
显式安装 `chromium webkit`，跑 `npm run test:e2e:device`。转正条件写在 [docs/guide/ci.md](../guide/ci.md)。

## 3. 顺带发现并修掉的问题

### 3.1 a11y 门禁此前是「靠运气全绿」

`a11y.spec.ts` 的首页扫描一开始 FAIL，报：

```
[serious] color-contrast: Elements must meet minimum color contrast ratio thresholds → .block-link
#1f6feb on #f0f0f0 = 4.06:1（要求 4.5:1），font-size 12.5px
```

先做了归因实验，避免把既有缺陷算到本轮改动头上：

| 实验 | 结果 |
| --- | --- |
| 当前工作区（含 CSS 拆分 + scope 改动） | 同一处 FAIL |
| **独立的 pristine HEAD worktree**（`git worktree add /tmp/headcheck HEAD`，独立 `vite build`） | **同一处 FAIL** |

所以这不是回归。那为什么阶段 E 第一刀当时报「axe 5 个页面 0 违规」？因为这个按钮
**只有 demo 账号存在未完成备忘时才渲染**（`Home/index.tsx` 的 `openMemos.length > 0`）。
全新库（CI）里它根本不在 DOM 里 → 扫不到 → 绿；本地跑久了库里攒了 20 条备忘 → 它出现 → 红。
**门禁的覆盖率取决于库里有没有残留数据，这比红更糟：它会让一条真实缺陷长期隐形。**

两处修复：

1. **产品缺陷**：新增 `--accent-text` 令牌（亮色 `#1a5fd0`、深色 `#7cb0ff`），`.dc-link` 改用它。
   实测对比度：亮色下白色底 5.85:1、`#f0f0f0` 底 5.13:1、`--surface-3` 底 5.26:1；
   深色下最差背景 6.77:1。填充/描边继续用 `--accent`（那是 3:1 口径的非文本对比度）。
2. **门禁确定性**：`a11y.spec.ts` 的首页用例先用 API 造一条未完成备忘、扫完删掉
   （`ensureHomeWidgets`），保证被扫的 DOM 不随库里的残留数据漂移。

修复后：`a11y.spec.ts` **5 passed**；`npm run test:visual` 仍 **11/11 PASS，0 像素差**
（视觉基线里没有该按钮，所以不需要更新基线）。

### 3.2 离线与「已缓存数据」的承诺不成立（未修，记录）

- 屏蔽 Service Worker 时，离线打开未加载过的懒加载路由 → `页面出错了 / Unable to preload CSS`。
- **开启** SW 时前端不报错，但 `/travel` 显示「行程列表加载失败：Network Error」——
  PWA 文档里「已打开过的行程离线还能看」在本次实测中**没有兑现**。
- 用例已把「非白屏」锁成底线断言，但这个产品承诺需要单独立项（缓存策略 vs 数据新鲜度的取舍）。

## 4. 多端并发的真实结论

- 前端 `conversationId` 存在 `sessionStorage`（`agentStore.ts` 的 `persist`），**按标签页隔离**：
  两个标签页各自生成各自的 UUID，表现为「各聊各的」，不会写同一段会话。所以日常双端并发是安全的。
- 但**同一段会话被显式续聊到两个端**时，服务端 `ConversationService.append` 只有裸 `save()`：
  `conversation_message` 上没有唯一约束、没有 `@Version`、没有 `SELECT ... FOR UPDATE`。
  消息会按到达顺序交错追加，`recent()` 取最新 N 条时会混入两端内容。
- 幂等台账（`agent_command_dedupe`，V20 的唯一键）只作用于 `RECORD_EXPENSE / ADD_MEMO / ADD_SCHEDULE`
  这三类写意图，**不覆盖会话消息写入**。
- 这是真实缺口，不是本轮引入的；本轮的 `test:e2e:multi` 只证明「不同会话并发安全」，
  **没有**证明「同一会话并发安全」。

## 5. 边界与仍待办

- **iOS 真机**：需要 Mac + 真 iPhone（或云真机）。Playwright WebKit 证明不了
  `env(safe-area-inset-*)` 真实取值（Playwright 下恒为 0，而底栏正是靠它让出 home 指示条）、
  真机滚动手势与惯性、软键盘弹出后的视口变化、老 iOS 的媒体查询分支、真机 WebKit 版本差异。
- **弱网只在 Chromium**：WebKit/Firefox 无 CDP 网络模拟。
- **同一会话并发写**：需要给会话写入加乐观锁或唯一去重键（例如按 `(user_id, conversation_id, request_id)`），
  属于契约变更，未做。
- **离线缓存承诺**：见 §3.2，需要单独设计。