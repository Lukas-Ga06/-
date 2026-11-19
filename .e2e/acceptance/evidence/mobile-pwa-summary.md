# 移动端 PWA 验收摘要（`.evidence/reports/mobile-pwa.md`）

> 机器可读摘要 + 真机自测清单。数字以同目录 `mobile-pwa.json` 为准；
> `<!-- AUTO:... -->` 区块由 `.e2e/mobile/mobile-pwa-check.mjs` 重跑时自动覆盖。

<!-- AUTO:MOBILE-PWA:BEGIN -->
<!-- 本段由 .e2e/mobile/mobile-pwa-check.mjs 自动生成，重跑会覆盖同标记区间 -->
- 生成时间：2026-11-19T07:02:32.302Z
- 总体结论：**PASS**
- 静态站：`http://127.0.0.1:4173`；后端：`http://127.0.0.1:18090`
- 浏览器：Playwright 1.64.1 / Node v20.17.0

| 设备 | 引擎 | 检查项 | 结果 | 证据 |
| --- | --- | --- | --- | --- |
| iPhone 13 | webkit | a_login | PASS | loginUrl=http://127.0.0.1:4173/login postLoginUrl=http://127.0.0.1:4173/ innerWidth=390 overflowPx=0 |
| iPhone 13 | webkit | a_login_no_overflow | PASS | scrollWidth=390 innerWidth=390 overflowPx=0 |
| iPhone 13 | webkit | b_travel_list | PASS | tripRows=12 innerWidth=390 scrollWidth=390 overflowPx=0 mainVisible=true heading=🧭 旅行规划 |
| iPhone 13 | webkit | b_trip_detail | PASS | tripId=12 timelineRows=5 innerWidth=390 scrollWidth=390 overflowPx=0 budgetDrawerOpened=true |
| iPhone 13 | webkit | c_manifest | PASS | status=200 type=application/manifest+json; charset=utf-8 name=AI 个人助理 short_name=个人助理 start_url=/ display=standalone sizes=192x192/512x512/512x512 |
| iPhone 13 | webkit | c_manifest_link | PASS | href=/manifest.webmanifest |
| iPhone 13 | webkit | c_sw_active | PASS | activeState=activated scope=http://127.0.0.1:4173/ script=http://127.0.0.1:4173/sw.js |
| iPhone 13 | webkit | c_sw_controller | PASS | controller=http://127.0.0.1:4173/sw.js |
| iPhone 13 | webkit | d_runtime_cache_warm | PASS | caches=workbox-precache-v2-http://127.0.0.1:4173/,itinerary-v1 itineraryEntries=http://127.0.0.1:4173/api/trips/12/itinerary |
| iPhone 13 | webkit | d_runtime_cache_budget | PASS | http://127.0.0.1:4173/api/trips/12/budget |
| iPhone 13 | webkit | d_offline_shell | PASS | url=http://127.0.0.1:4173/trip/12 title=AI 个人助理 · 旅行规划 · 记账 · 备忘 rootChildren=1 textLength=649 |
| iPhone 13 | webkit | d_offline_banner | PASS | offline-banner 可见 |
| iPhone 13 | webkit | d_offline_cached_data | PASS | pathname=/trip/12 tripHeading=行程 timelineRows=5 sessionRecoveryFailed=false loginForm=false text=离线：显示缓存数据 ✕ 行程 加载中… 已花 ¥1566 / 预算 ¥- 撤销 路书 记账 预算分析 地图 Day 1 高德地图加载失败，请检查 VITE_AMAP_KEY、VITE_AMAP_SECURITY_CODE 和网络连接 🏔 3D 🗺 2D ▶ 沿路线巡航 ⬛ 俯视 AI 助手 随时调整行程 🤖 你好，我是 AI 旅行助手 ✈️ 可以说： · 帮我规划上海三日游，2人，预算3000，酒店近地铁 · 明天下雨，把户外景点换掉 · 点选时间轴安排后说「这个换成类似的」 · 上海有什么适合亲子游的地方？… |
| iPhone 13 | webkit | d_offline_auth_me | PASS | kickedToLogin=false pathname=/trip/12 sessionRecoveryFailed=false meRequests=[{"method":"GET","url":"http://127.0.0.1:4173/api/auth/me"}] meFailures=[{"url":"http://127.0.0.1:4173/api/auth/me","failure":"WebKit encountered an internal error"}] meResponses=[] |
| iPhone 13 | webkit | d_offline_note | INFO | url=http://127.0.0.1:4173/trip/12 title=AI 个人助理 · 旅行规划 · 记账 · 备忘 text=离线：显示缓存数据 ✕ 行程 加载中… 已花 ¥1566 / 预算 ¥- 撤销 路书 记账 预算分析 地图 Day 1 高德地图加载失败，请检查 VITE_AMAP_KEY、VITE_AMAP_SECURITY_CODE 和网络连接 🏔 3D 🗺 2D ▶ 沿路线巡航 ⬛ 俯视 AI 助手 随时调整行程 🤖 你好，我是 AI 旅行助手 ✈️ 可以说： · 帮我规划上海三日游，2人，预算3000，酒店近地铁 · 明天下雨，把户外景点换掉 · 点选时间轴安排后说「这个换成类似的」 · 上海有什么适合亲子游的地方？ Day 1 Day 2 09:00 09:30 酒店 入住 · 王府井亚朵酒店 ¥520 酒店 近地铁 王府井商圈，步行可达故宫东门，地铁口近在咫尺。 10:00 11:45 景点 故宫博物院 ¥120 历史 文化 地标 亲子 明清两代皇宫，世界文化遗产，周一闭馆，需提前7天预… |
| iPhone 13 | webkit | e_recovery | PASS | url=http://127.0.0.1:4173/trip/12 timelineRows=5 loginForm=false onLine=true overflowPx=0 |
| Pixel 7 | chromium | a_login | PASS | loginUrl=http://127.0.0.1:4173/login postLoginUrl=http://127.0.0.1:4173/ innerWidth=412 overflowPx=0 |
| Pixel 7 | chromium | a_login_no_overflow | PASS | scrollWidth=412 innerWidth=412 overflowPx=0 |
| Pixel 7 | chromium | b_travel_list | PASS | tripRows=12 innerWidth=412 scrollWidth=412 overflowPx=0 mainVisible=true heading=🧭 旅行规划 |
| Pixel 7 | chromium | b_trip_detail | PASS | tripId=12 timelineRows=5 innerWidth=412 scrollWidth=412 overflowPx=0 budgetDrawerOpened=true |
| Pixel 7 | chromium | c_manifest | PASS | status=200 type=application/manifest+json; charset=utf-8 name=AI 个人助理 short_name=个人助理 start_url=/ display=standalone sizes=192x192/512x512/512x512 |
| Pixel 7 | chromium | c_manifest_link | PASS | href=/manifest.webmanifest |
| Pixel 7 | chromium | c_sw_active | PASS | activeState=activated scope=http://127.0.0.1:4173/ script=http://127.0.0.1:4173/sw.js |
| Pixel 7 | chromium | c_sw_controller | PASS | controller=http://127.0.0.1:4173/sw.js |
| Pixel 7 | chromium | d_runtime_cache_warm | PASS | caches=workbox-precache-v2-http://127.0.0.1:4173/,itinerary-v1 itineraryEntries=http://127.0.0.1:4173/api/trips/12/itinerary |
| Pixel 7 | chromium | d_runtime_cache_budget | PASS | http://127.0.0.1:4173/api/trips/12/budget |
| Pixel 7 | chromium | d_offline_shell | PASS | url=http://127.0.0.1:4173/trip/12 title=AI 个人助理 · 旅行规划 · 记账 · 备忘 rootChildren=1 textLength=611 |
| Pixel 7 | chromium | d_offline_banner | PASS | offline-banner 可见 |
| Pixel 7 | chromium | d_offline_cached_data | PASS | pathname=/trip/12 tripHeading=行程 timelineRows=5 sessionRecoveryFailed=false loginForm=false text=离线：显示缓存数据 ✕ 行程 加载中… 已花 ¥1566 / 预算 ¥- 撤销 路书 记账 预算分析 地图 Day 1 高德地图加载失败，请检查 VITE_AMAP_KEY、VITE_AMAP_SECURITY_CODE 和网络连接 🏔 3D 🗺 2D ▶ 沿路线巡航 ⬛ 俯视 AI 助手 随时调整行程 🤖 你好，我是 AI 旅行助手 ✈️ 可以说： · 帮我规划上海三日游，2人，预算3000，酒店近地铁 · 明天下雨，把户外景点换掉 · 点选时间轴安排后说「这个换成类似的」 · 上海有什么适合亲子游的地方？… |
| Pixel 7 | chromium | d_offline_auth_me | PASS | kickedToLogin=false pathname=/trip/12 sessionRecoveryFailed=false meRequests=[{"method":"GET","url":"http://127.0.0.1:4173/api/auth/me"}] meFailures=[{"url":"http://127.0.0.1:4173/api/auth/me","failure":"net::ERR_INTERNET_DISCONNECTED"}] meResponses=[] |
| Pixel 7 | chromium | d_offline_note | INFO | url=http://127.0.0.1:4173/trip/12 title=AI 个人助理 · 旅行规划 · 记账 · 备忘 text=离线：显示缓存数据 ✕ 行程 加载中… 已花 ¥1566 / 预算 ¥- 撤销 路书 记账 预算分析 地图 Day 1 高德地图加载失败，请检查 VITE_AMAP_KEY、VITE_AMAP_SECURITY_CODE 和网络连接 🏔 3D 🗺 2D ▶ 沿路线巡航 ⬛ 俯视 AI 助手 随时调整行程 🤖 你好，我是 AI 旅行助手 ✈️ 可以说： · 帮我规划上海三日游，2人，预算3000，酒店近地铁 · 明天下雨，把户外景点换掉 · 点选时间轴安排后说「这个换成类似的」 · 上海有什么适合亲子游的地方？ Day 1 Day 2 09:00 09:30 酒店 入住 · 王府井亚朵酒店 ¥520 酒店 近地铁 王府井商圈，步行可达故宫东门，地铁口近在咫尺。 10:00 11:45 景点 故宫博物院 ¥120 历史 文化 地标 亲子 明清两代皇宫，世界文化遗产，周一闭馆，需提前7天预… |
| Pixel 7 | chromium | e_recovery | PASS | url=http://127.0.0.1:4173/trip/12 timelineRows=5 loginForm=false onLine=true overflowPx=0 |

<!-- AUTO:MOBILE-PWA:END -->

## 待有权限环境复跑（脚本已就绪）

```powershell
# 前置：后端 18090 已在跑；静态站（可在另一个会话起）
node .e2e/mobile/serve-dist.mjs --port 4173 --api http://127.0.0.1:18090

# 真设备仿真验收（需要 danger-full-access：Playwright 要 spawn 浏览器）
cd D:\travel-agent
node .e2e/mobile/mobile-pwa-check.mjs --base http://127.0.0.1:4173 --api http://127.0.0.1:18090
# 产出：.evidence/reports/mobile-pwa.json、.evidence/reports/mobile-pwa.md（自动区块）、
#       .evidence/screenshots/mobile-{iphone,pixel}-{login,trip,offline}.png

# 若要 iPhone 用真 WebKit 引擎（脚本会自动尝试；未装则退化 chromium + iPhone 参数并记录）
cd D:\travel-agent\travel-agent-web
npx playwright install webkit
```

参数说明（`mobile-pwa-check.mjs`）：`--base`/`--api`/`--out`/`--screenshots`/`--only iphone|pixel`/`--headed`/`--timeout`。

`serve-dist.mjs` 参数：`--port`（默认 4173）、`--api`（默认 `http://127.0.0.1:18090`）、`--dist`（默认 `travel-agent-web/dist`）、`--quiet`、`--harness-dir`（可选，提供 `/__e2e/*` 同源自测页与结果落盘，默认关闭）。

## 真机自测清单（人工复验，仿真 ≠ 真机）

| 平台 | 步骤 | 预期 |
| --- | --- | --- |
| iPhone Safari | 1. Safari 打开 `http://<host>:4173/`，登录 demo/demo123<br>2. 分享 → 添加到主屏幕<br>3. 从主屏图标启动 | 主屏出现「个人助理」图标；启动后无 Safari 地址栏（display=standalone）；状态栏颜色为主题色 #1677ff |
| iPhone Safari | 4. 旅行规划 → 打开一趟行程 → 打开「预算分析」<br>5. 打开飞行模式<br>6. 下拉刷新，或杀掉后从主屏图标冷启动 | 页面不白屏、不出现 Safari 错误页；顶部出现「离线：显示缓存数据」提示条；已看过的行程（时间轴/预算）仍可见；**不应被踢回登录页** |
| iPhone Safari | 7. 关闭飞行模式 | 提示条自动消失；数据恢复；无持续报错 |
| Android Chrome | 1. Chrome 打开 `http://<host>:4173/`，登录<br>2. 菜单 →「安装应用」/「添加到主屏幕」<br>3. 从桌面图标启动 | 出现安装入口（manifest + SW 满足可安装性）；standalone 启动；无地址栏 |
| Android Chrome | 4. 浏览行程与预算后开飞行模式<br>5. 从桌面图标冷启动 | app shell 由 SW 预缓存打开；离线提示条出现；已缓存行程可见；不被踢回登录页 |
| Android Chrome | 6. 关闭飞行模式并下拉刷新 | 恢复正常在线数据；提示条消失 |
| 桌面 Chrome | 1. 打开 `http://<host>:4173/`，登录<br>2. 地址栏右侧安装图标 / 菜单 → 安装 | 安装入口可用 |
| 桌面 Chrome | 3. DevTools → Application → Service Workers / Cache Storage | `sw.js` activated；Cache Storage 有预缓存与 `itinerary-v1`；打开过行程后出现 itinerary/budget 条目 |
| 桌面 Chrome | 4. DevTools → Network → Offline，然后刷新 | app shell 打开、离线提示条出现；已看过行程尽量渲染（当前实现受 `/auth/me` 离线失败影响，详见验收报告「发现的问题」） |