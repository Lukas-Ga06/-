# 移动端 PWA 验收报告：iPhone Safari / Android Chrome 仿真 + 安装性与离线恢复

> 快照日期：2026-11-19 ｜ 验证范围：iPhone 13 WebKit / Pixel 7 Chromium 仿真、PWA 安装硬指标、离线恢复 ｜ 结论：自动化 32 项 0 FAIL；真机系统级安装仍待人工（详见 §0）

> 验收日期：2026-11-19 ｜ 执行：`mobile-pwa` 子代理起草，root 用 WebKit/Chromium 真引擎复跑并回填数字
> 范围：只验收「移动端 Safari / Chrome 仿真 + PWA 安装硬指标 + 离线恢复」，不评价业务功能正确性。
> 证据：`.evidence/reports/mobile-pwa-playwright.json`、`.evidence/screenshots/mobile-iphone-*.png` / `mobile-pixel-*.png`

## 0. 结论先看（TL;DR）

| 验收项 | 结论 | 依据 |
|---|---|---|
| iPhone 13（**真 WebKit 引擎**，playwright webkit 2370 / WebKit 27.2） | ✅ **PASS** 16/16 | 登录、行程列表/详情、manifest、SW、离线、恢复全部通过 |
| Pixel 7（Chromium 156） | ✅ **PASS** 16/16 | 同上（Android Chrome 引擎） |
| PWA 可安装硬指标（manifest / 图标 / SW / controller） | ✅ **PASS** | manifest 200、standalone、192+512 图标、SW active、页面受控 |
| 离线冷启动（`context.setOffline(true)` 真断网） | ✅ **PASS** | app shell 打开、离线提示条出现、**已看过行程时间轴 5 行仍渲染**、不踢回登录页 |
| 恢复在线 | ✅ **PASS** | 行程重新拉取、无横向溢出、`onLine=true` |
| 真机人工确认（主屏安装 UI、iOS 安全区/状态栏、真飞行模式） | ⏳ **待人工** | 无法在 headless 自动化里点系统级安装弹窗，见 §5 清单 |

**本轮同时修复了一个真实缺陷**：离线冷启动会被 `/auth/me` 网络失败挡在「登录状态校验失败」兜底页，
预缓存的 app shell 与运行时缓存的行程数据永远显示不出来（旧实现只保证「不白屏、不踢人」）。
修复后离线可以真正看到缓存行程，回归用例 166 → **172 全绿**，详细见 §4。

## 1. 方法与口径

| 项 | 取值 |
|---|---|
| 浏览器 | Playwright 1.64.1；iPhone 13 → **webkit 2370（真实 WebKit 引擎）**；Pixel 7 → Chromium 156 |
| 设备参数 | iPhone 13：390×844、DPR 3、`isMobile`、`hasTouch`、iOS UA；Pixel 7：412×915、DPR 2.625、Android 14 UA |
| 站点 | `node .e2e/mobile/serve-dist.mjs --port 4173 --api http://127.0.0.1:18090`（生产构建 `dist` + `/api` 反代，SPA fallback） |
| 后端 | `http://127.0.0.1:18090`（H2 文件库，`RATE_LIMIT_ENABLED=false`，demo/demo123） |
| 断网 | `context.setOffline(true)`（CDP 网络模拟，`navigator.onLine` 会变 false，等价飞行模式） |
| 断言总数 | 2 设备 × 16 项 = 32 项；**30 PASS / 0 FAIL / 2 INFO** |
| 构建 | `npm run typecheck` ✅；`npm test` **172 passed**；`npm run build` ✅（precache 42 entries / 3604.68 KiB） |

脚本：`.e2e/mobile/mobile-pwa-check.mjs`（可参数化 `--base/--api/--out/--screenshots/--only/--headed`，
自己 launch 浏览器、不依赖调用方起站）。退出码 0=全 PASS、1=有 FAIL、2=环境不可用。

## 2. 逐项结果

| # | 检查 | iPhone 13 (WebKit) | Pixel 7 (Chromium) | 关键读数 |
|---|---|---|---|---|
| a | 登录页可用、能登录 demo/demo123 | PASS | PASS | 登录后 URL=`/`，密码框/按钮可见 |
| a | 登录页无横向溢出 | PASS | PASS | `scrollWidth==innerWidth`（390/412），overflowPx=0 |
| b | 行程列表无溢出、主内容可见 | PASS | PASS | 12 条行程，`overflowPx=0` |
| b | 行程详情无溢出、时间轴可见 | PASS | PASS | 时间轴 5 行，预算抽屉能打开 |
| c | manifest 200 + 字段齐全 | PASS | PASS | `name=AI 个人助理`、`short_name=个人助理`、`start_url=/`、`display=standalone`、图标 192/512 |
| c | `<link rel="manifest">` 存在 | PASS | PASS | `/manifest.webmanifest`，`application/manifest+json` |
| c | SW 已激活 | PASS | PASS | `state=activated`，`scope=/`，`script=/sw.js` |
| c | 页面被 SW 控制 | PASS | PASS | `controller=/sw.js` |
| d | 运行时缓存已预热（行程/预算） | PASS | PASS | `itinerary-v1` 命中 `/api/trips/12/itinerary`、`/api/trips/12/budget` |
| d | 离线 reload：app shell 能打开 | PASS | PASS | URL 仍 `/trip/12`，标题正常，非浏览器错误页 |
| d | 离线提示条出现 | PASS | PASS | `[data-testid=offline-banner]` 可见，文案「离线：显示缓存数据」 |
| d | **离线时已缓存行程仍渲染** | **PASS** | **PASS** | 时间轴 5 行；页面文本含「已花 ¥1566」等缓存数据 |
| d | 离线时不被踢回登录页 | PASS | PASS | `pathname=/trip/12`、无登录表单、`session-recovery-failed=false` |
| e | 恢复在线后数据重新渲染 | PASS | PASS | `onLine=true`、时间轴 5 行、无横向溢出 |

> INFO 两项：离线时页面实际 URL/标题/文本快照（如实记录，不作断言）。
> 离线快照里「高德地图加载失败」是预期现象（离线拿不到 JS API），不影响行程数据渲染。

## 3. PWA 安装性判据（为什么这些项就够）

Chrome/Safari 的「可安装」硬门槛是：**HTTPS（或 localhost）+ 有 manifest（name/short_name/start_url/display/icons 192+512）+
注册了带 fetch 处理的 Service Worker，且页面被它控制**。本报告逐项验证了后者三条；
`beforeinstallprompt` / Safari「添加到主屏幕」弹窗是系统 UI，headless 无法点击，留给 §5 真机清单。

## 4. 本轮发现并修复：离线冷启动被登录态校验挡住

### 4.1 现象（修复前）

- 链路：`App.tsx` 启动即 `GET /api/auth/me`；离线是**无状态码的网络错误** → `sessionError`；
  `Protected` 在 `!user && sessionError` 时渲染 `SessionRecoveryFailed`，**不渲染任何子路由**。
- 结果：SW 明明缓存了 app shell 与 `itinerary/budget`，页面却停在「登录状态校验失败…重新加载」，
  时间轴 0 行。旧实现只做到「不白屏、不踢回登录页」，**「离线还能看已打开过的行程」实际不成立**
  （与 `vite.config.ts` 里 runtimeCaching 的产品意图冲突）。
- 复现证据：修复前同脚本 iPhone/Pixel 各 1 项 FAIL（`d_offline_cached_data`），
  见 git 历史中的 `.evidence/reports/mobile-pwa-playwright.json` 上一版。

### 4.2 修复（最小改动，2 个文件 + 1 个测试）

| 文件 | 改动 |
|---|---|
| `travel-agent-web/src/store/authStore.ts` | 新增 `ta.lastUser` 缓存（**只存 id/username/nickname，不存 token**）；`setAuth/setUser` 写入、`logout` 清除；新增 `restoreCachedUser()` |
| `travel-agent-web/src/App.tsx` | 启动恢复遇到**纯网络错误**（`status===undefined`）且有缓存时，先用缓存身份渲染离线界面并提示；新增 `online` 监听：恢复网络后重新 `/auth/me`，**只有 401 才登出** |
| `travel-agent-web/src/store/authStore.test.ts` | 新增 6 个用例：写缓存、setAuth 落缓存、离线恢复、无缓存不伪造、损坏缓存、logout 清除 |

设计约束：缓存只是**展示身份**，不伪造 token、不放宽服务端鉴权；401 仍然清态跳登录；
429/5xx 且无缓存时保持原有「保留登录态 + 提示重试」的行为。

### 4.3 回归

- `npm test`：**172 passed（23 files）**，原 166 + 新增 6；
- `npm run typecheck`：0 error；
- `npm run build`：成功，precache 42 entries；
- 移动端脚本复跑：**30 PASS / 0 FAIL**（本文档数字即复跑后数据）；
- 桌面既有 e2e 未改配置（`serviceWorkers:'block'`），不受影响。

## 5. 真机自测清单（需要人工在设备上确认）

> 自动化只能证明「WebKit/Chromium 引擎下行为正确」；下面这些系统级交互必须真机点一遍。

### iPhone Safari

1. Safari 打开 `https://<域名>`，登录 demo/demo123；
2. 分享 → **添加到主屏幕**，确认图标/名称（个人助理）正确；
3. 从主屏图标冷启动：应全屏（无 Safari 地址栏）、状态栏与安全区不遮挡顶部/底部按钮；
4. 打开一趟行程 + 预算分析，返回列表；
5. 开**飞行模式**，从主屏图标冷启动：不白屏、出现「离线：显示缓存数据」、已看过的行程时间轴可见、**不被踢回登录页**；
6. 关闭飞行模式：页面自动恢复（或手动刷新）后数据为最新。

### Android Chrome

1. Chrome 打开同址，登录；
2. 菜单出现「安装应用 / 添加到主屏幕」，安装后从桌面图标启动（standalone）；
3. 打开行程 + 预算后开飞行模式，从桌面图标冷启动：app shell 打开、离线提示、缓存行程可见；
4. 恢复网络后下拉刷新，数据更新；检查返回键行为（不应直接退出应用）。

### 桌面 Chrome（回归）

1. DevTools → Application → Manifest：无 installability 报错；Service Workers：activated；
2. Network → Offline 后刷新：app shell 打开、提示条出现、已看过行程可读；
3. 恢复 Online 后刷新：数据最新、无重复请求异常。

记录模板：设备/系统版本、Safari/Chrome 版本、安装是否成功、离线是否可见、是否被踢登录、异常截图。

## 6. 局限（不要把仿真当已上真机）

1. **仿真 ≠ 真机**：WebKit 2370 是 Playwright 构建，不等于 iOS Safari 26/27 的全部行为（尤其 SW 生命周期、内存回收、安装弹窗）。
2. **安装 UI 无法自动化**：`beforeinstallprompt`、iOS 分享菜单、主屏图标渲染只能人工确认。
3. **PWA 需要安全上下文**：真机验证必须 HTTPS（或 localhost）；本次用 `http://127.0.0.1:4173`（localhost 属安全上下文）。
4. **离线只覆盖白名单 GET**：`/api/trips`、`/api/trips/<id>/itinerary`、`/api/trips/<id>/budget`；行程详情 `/api/trips/<id>` 按设计不缓存。
5. **缓存 5 分钟过期**：离线可看窗口受 `maxAgeSeconds:300` 限制，超时后只保证 shell 可打开。

## 7. 交付物

| 文件 | 说明 |
|---|---|
| `.e2e/mobile/mobile-pwa-check.mjs` | Playwright 验收脚本（iPhone 13 WebKit / Pixel 7 Chromium，在线+离线+恢复+截图+JSON） |
| `.e2e/mobile/serve-dist.mjs` | 生产 dist 静态站 + `/api` 反代（Node 原生，无依赖） |
| `.evidence/reports/mobile-pwa-playwright.json` | 本次运行原始结果（32 项 + 设备/UA/引擎/缓存读数） |
| `.evidence/reports/mobile-pwa.md` | 脚本自动生成的同内容摘要 |
| `.evidence/screenshots/mobile-iphone-*.png`、`mobile-pixel-*.png` | 登录/行程/离线三态截图（真 `page.screenshot()`） |
| `.e2e/mobile/harness/pwa-harness.html` | 早期受限环境下的同源自测页（保留作对照，不再是主证据） |