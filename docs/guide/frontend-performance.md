# 前端性能：地图包体与运行时按需加载

> 状态：当前事实 | 适用版本：0.1.0（Spring Boot 3.5.7 / Node 20） | 最后验证：2026-11-19 | 事实来源：travel-agent-web/vite.config.ts、travel-agent-web/src/components/FootprintMap/、travel-agent-web/scripts/check-bundle-budget.mjs、本次 `npm run build` / `npm run build:check` / `npm test` 实测产物

> 面向：要改前端、改构建或排查「地图/图表拖慢首屏」的同学。
> 结论先行：**地图引擎不再进入 Rollup 的 import 图**，改为页面真正渲染地图时从带版本号的自托管目录按需 `import()`；
> 构建产物不再有 `maps` chunk，PWA 不再预缓存地图引擎，包体预算由脚本强制守住。

---

## 1. 结论与数字

改造前后都来自本仓真实 `npm run build`（maplibre-gl **6.19.0**，不允许降级）：

| 指标 | 改造前 | 改造后 |
| --- | --- | --- |
| Rollup 地图 chunk | `maps-*.js` **1,068.35 kB**（gzip 292.76 kB），触发 Vite >800 kB 告警 | **不存在**；地图引擎移出 import 图 |
| 地图引擎传输 | 随 `maps` chunk 进入首屏/预缓存 | 运行时按需：主模块 **1,106,041 B / gzip9 299,604 B**；worker **550,754 B / gzip9 158,020 B** |
| PWA precache | 66 entries，**3,062.74 KiB（≈2.99 MiB）**，含整个 maps chunk | 65 entries，**2,070,880 B（≈1.97 MiB）**，不含 `vendor/maplibre/**` |
| 首屏 JS（index.html 直接加载） | 入口 `index-*.js` 563.95 kB（gzip 183.87 kB） | 入口 563,919 B + `registerSW.js` 134 B = **564,053 B**（gzip9 183,505 B） |
| ECharts chunk | `echarts-*.js` 550.48 kB | 550,477 B，仍独立分块、只被图表页懒加载 |
| 地图样式 | `maps-*.css` 71,505 B 随 maps chunk | 懒加载 CSS chunk 71,505 B，不进 `index.html` |
| 构建告警 | `Some chunks are larger than 800 kB` | 无 |

补充：`dist/index.html` 只有 1 个 module script 与 1 个 SW 注册脚本，没有 `modulepreload`，也没有任何 `vendor/maplibre` 引用；
入口 JS 的静态 import 闭包只有它自己（563,919 B），ECharts 与地图 chunk 都不在其中。

---

## 2. 方案原理

### 2.1 为什么不降级 maplibre

4.7.1 能做出 798.14 kB 的 chunk，但它落在 GHSA-jrc7-96c5-q579（CRITICAL XSS，影响 `<=6.4.0`）范围内。
**安全修复优先于几十 KB 的体积**，因此保持 6.19.0，改用加载方式解决体积问题。

### 2.2 为什么运行时加载，而不是继续 manualChunks

6.19 的 `dist/maplibre-gl.mjs` 是单文件 ESM bundle（1.1 MB），Rollup 无法在包内部再拆分。
只要它出现在 import 图里：

- 必然产出一个接近/超过 1 MB 的 chunk（Vite 800 kB 告警）；
- PWA 默认 glob 会把这个 chunk 收进 precache，**所有页面**（包括不看地图的登录页）首次访问都要为它下载；
- 首页 `FootprintMap` 虽然是 `lazy()`，但只要地图组件渲染，就在首屏链路里下载 1 MB 地图引擎。

改成运行时 `import()` 后，地图引擎是浏览器直接请求的静态文件，不经过 Rollup：
首屏 HTML 不会引用它，SW 也不预缓存它；只有 `FootprintMap` 真正挂载时才发起请求。

### 2.3 自托管 + 版本目录

- URL：`${import.meta.env.BASE_URL}vendor/maplibre/<version>/maplibre-gl.mjs`，支持子路径部署（`BASE_URL` 带 `/`）。
- **文件名必须保持官方名字**：maplibre 内部用 `import.meta.url` 推导同目录的 `maplibre-gl-worker.mjs`，
  主模块与 worker 必须原样同目录，worker 的相对 URL 才能解析。
- 目录名带版本号：升级 maplibre 后 URL 变化，浏览器/CDN 缓存自然失效，不会出现「旧 HTML 配新 worker」。
- dev / build 两种提供方式：
  - `vite dev`：`configureServer` 中间件把 `/vendor/maplibre/<ver>/*` 映射到 `node_modules/maplibre-gl/dist/*`，免复制、天然同步；
  - `vite build`：`closeBundle` 把 `maplibre-gl.mjs`、`maplibre-gl-worker.mjs`、`maplibre-gl.css` 原样拷到 `dist/vendor/maplibre/<ver>/`（幂等）。
  - 因此**不需要**往 `public/` 写生成物，也没有要额外 ignore 的源树文件。

### 2.4 加载器与降级

`src/components/FootprintMap/maplibreLoader.ts`：

- 单例 Promise：并发调用共享同一次加载；成功后缓存模块。
- 校验导出 `Map / Marker / Popup / LngLatBounds / AttributionControl`，缺一个都视为失败。
- 失败时清空缓存的 Promise（**允许重试**），抛出带完整 URL 的错误：`地图引擎加载失败（<url>）：<原因>`。
- `FootprintMap` 在 effect 中 `await loadMaplibre()` 后再创建 Map：
  - 组件卸载 / 依赖变化重建时，用 `cancelled` 标记兜住，**加载未完成不会 setState**；
  - 失败时渲染可见的降级块（「地图引擎加载失败」+ 错误原因 + 重试按钮），不是白屏，也把动作按钮隐藏掉；
  - 重试通过递增 `loadAttempt` 重新执行 effect。
- `basemap.ts`、组件内的 `Map/Marker/Popup/GeoJSONSource` 全部 `import type`；业务代码里没有任何 maplibre 值导入。
- CSS 仍静态 `import 'maplibre-gl/dist/maplibre-gl.css'`：只把样式打进懒加载页面的 CSS chunk，不会拉 JS。

### 2.5 版本常量为什么硬编码

`MAPLIBRE_VERSION = '6.19.0'` 写在 loader 里，运行时不去读 `package.json`（避免为版本号引入运行时依赖/额外请求）。
防漂移靠两处断言：

- `src/components/FootprintMap/maplibreLoader.test.ts` 之外的构建门禁：`scripts/check-bundle-budget.mjs` 同时校验
  `maplibreLoader.ts` 的常量、`node_modules/maplibre-gl/package.json`、`dist/vendor/maplibre/<version>/` 三方一致；
- vendor 目录本身由 `vite.config.ts` 按 `node_modules` 真实版本生成，目录名与常量不一致时预算脚本会失败。

### 2.6 PWA 与 ECharts

- `workbox.globIgnores: ['**/vendor/maplibre/**', '**/bundle-stats.html']`：地图引擎不预缓存；版本目录可安全长缓存，地图页首次打开时走 HTTP 缓存。
  `bundle-stats.html` 只在 `npm run analyze` 时产生，也排除出 precache，避免分析构建把 1.7 MB 报告塞进 SW。
  预算脚本会从 `dist/sw.js` 解析 precache 清单，断言其中不含 `vendor/maplibre`。
- ECharts 仍由 `manualChunks` 独立成块（`echarts`/`zrender`），`src/lib/echarts.ts` 按需 `use()` 注册；
  `Home` 没有静态引用 `BudgetChart`/`ExpensePanel`/`lib/echarts`，`Trip`/`Expenses` 是懒加载路由，
  所以非图表页面不会下载 `echarts-*.js`（入口静态闭包证据见第 1 节）。

---

## 3. 包体预算（`scripts/check-bundle-budget.mjs`）

`npm run build:check` = `npm run build && node scripts/check-bundle-budget.mjs`；CI 可直接用这条命令。

| 预算项 | 阈值 | 当前实测 | 说明 |
| --- | --- | --- | --- |
| `assets/*.js` 单文件 | ≤ 800,000 B | 最大 563,919 B | 与 Vite `chunkSizeWarningLimit: 800` 对齐；vendor 不算在内 |
| index.html 首屏 JS | ≤ 600,000 B | 564,053 B | module script + modulepreload，去重后求和 |
| ECharts chunk | ≤ 600,000 B | 550,477 B | 图表必须保持独立分块，否则无法单独设预算 |
| 地图主模块 gzip | ≤ 300,000 B | 299,604 B | gzip **level 9** 口径 |
| 地图 worker gzip | ≤ 160,000 B | 158,020 B | 同上 |
| PWA precache 总量 | ≤ 2.3 MiB | 1.97 MiB（65 项） | 从 `dist/sw.js` 的 precache 清单求和 |
| `index.html` 引用地图 vendor | 必须为 0 | 0 | 出现即说明地图又回到首屏 |

gzip 口径说明：脚本用 `zlib.gzipSync(..., { level: 9 })`，与 `rollup-plugin-visualizer` 的 `gzipSize` 一致，
表示「静态预压缩/最高压缩」下的传输体积；nginx 默认压缩级别通常略大几百字节到几 KB。阈值本身不追求字节级精确，
而是防止「又多了一个大依赖」这类结构性回退。

### 调整规则

1. **只能按证据上调**：先定位新增体积来自哪里（`npm run analyze`、`git diff`、包体报告），在文档里记录版本与理由，再改阈值。
2. **优先拆，不要抬**：新增的第三方大依赖默认应 `lazy()` / 运行时加载；`assets/*.js` 的 800 kB 是硬告警线，不建议上调。
3. **地图引擎预算单独管理**：主模块/worker/gzip 是三条独立预算；不要为了过门槛降级 maplibre（安全漏洞除外禁止）。
4. **升级 maplibre 时**：同步 `MAPLIBRE_VERSION`、重跑 `npm run build:check`（脚本会绑三方版本），并复核 gzip 是否越过 300 kB。
5. 预算脚本失败时先看输出里的「超限项 + 建议」，不要直接改阈值绕过。

---

## 4. 分析地图与验证命令

### 4.1 交互式包体分析

```bash
cd travel-agent-web
npm run analyze        # vite build --mode analyze
# 产物：dist/bundle-stats.html（dist/ 已被 gitignore，不需要提交）
```

`rollup-plugin-visualizer` 只在 `mode === 'analyze'` 时启用（`gzipSize: true`），
正常 `npm run build` 不会生成统计文件、也不影响产物体积。

### 4.2 本地验证「非地图页不下载地图引擎」

```bash
cd travel-agent-web
npm run build
npm run preview        # http://localhost:4173
```

1. 打开 DevTools → Network，过滤 `vendor/maplibre`。
2. 访问 `/login`、`/expenses` 等非地图页面：应为 **0 个** `vendor/maplibre` 请求，也不应出现 `index-DzpEEPd2.js` 这类 FootprintMap chunk。
3. 访问 `/`（首页）：只有账号**存在已打卡足迹**时 `FootprintMap` 才会挂载，此时才出现 `vendor/maplibre` 请求；
   没有足迹时首页只显示空态，同样不下载地图引擎（Home 的条件渲染见 `src/pages/Home/index.tsx`）。
4. 访问 `/footprint`：有可定位足迹时才加载地图引擎；否则只显示空态。

Playwright 快速探针（在 preview 已启动时）：

```js
await page.goto('http://localhost:4173/');
const result = await page.evaluate(async () => {
  const mod = await import('/vendor/maplibre/6.19.0/maplibre-gl.mjs');
  const worker = new Worker('/vendor/maplibre/6.19.0/maplibre-gl-worker.mjs', { type: 'module' });
  worker.terminate();
  return { hasMap: typeof mod.Map, version: mod.getVersion() };
});
// 本次实测：{ hasMap: 'function', version: '6.19.0' }
```

上面这套手动验证已经固化成 e2e 用例，CI 每次都会跑（`travel-agent-web/e2e/maplibre-vendor.spec.ts`）：

| 用例 | 守住什么 |
| --- | --- |
| vendor 文件可发布且按 JavaScript 返回 | 构建产物存在、nginx/静态服务器 MIME 正确（`.mjs` 被发成 octet-stream 时浏览器拒绝执行模块） |
| 浏览器可 import 并拿到核心导出 | `Map/Marker/Popup/LngLatBounds/AttributionControl` 齐全、`getVersion()` 与 vendor 目录版本一致 |
| worker 与样式同目录可解析 | maplibre 用 `import.meta.url` 推导 worker 相对路径，改名/挪目录会让瓦片解析静默失效 |

版本号从 `dist/vendor/maplibre/<ver>` 自动发现，升级 maplibre 不需要改用例：

```bash
cd travel-agent-web
npm run build && npx playwright test maplibre-vendor
```

### 4.3 dev 中间件

`npm run dev` 下同样直接访问 `/vendor/maplibre/6.19.0/maplibre-gl.mjs`，
应返回 `Content-Type: text/javascript`；worker 请求带 `?v=` 查询参数也能正确命中；
非白名单文件返回 404（不会退化成 SPA 的 200 index.html）。

---

## 5. 部署注意（nginx 必配）

`dist/vendor/maplibre/<ver>/*.mjs` 是浏览器按 **JavaScript module** 加载的，服务器必须返回 JS MIME。
nginx 1.27 自带的 `mime.types` 只有 `application/javascript js;`，**没有 `mjs`**，
默认会把 `.mjs` 当 `application/octet-stream`，浏览器会拒绝执行（地图显示降级提示而不是白屏）。

生产 nginx 需要为版本目录补 MIME 与缓存头（示例，按现有 `travel-agent-web/nginx.conf` 的 server 块追加）：

```nginx
# MapLibre 运行时按需加载：.mjs 必须是 JS MIME；版本目录可长缓存
location ^~ /vendor/maplibre/ {
    types { text/javascript mjs; }
    default_type text/javascript;
    expires 30d;
    add_header Cache-Control "public, immutable";
    try_files $uri =404;
}
```

说明：

- `types` 写在该 location 内只影响这个目录，不会覆盖全局的 `.js/.css` 映射；
- `try_files $uri =404` 避免文件缺失时回退到 `index.html`（否则会拿到 200 HTML，报错更难懂）；
- 目录名含版本号，`immutable` 长缓存是安全的。

`vite preview` / `vite dev` 会自动返回正确的 `text/javascript`（已实测），只有自建静态服务器需要显式配置。

---

## 6. 本次改动文件

| 文件 | 改动 |
| --- | --- |
| `travel-agent-web/vite.config.ts` | 新增 `maplibreVendor()` 插件（dev 中间件 + build 拷贝）；删除 maps manualChunk；PWA `globIgnores`；`mode=analyze` 启用 visualizer |
| `travel-agent-web/src/components/FootprintMap/maplibreLoader.ts` | 新建：单例加载、导出校验、失败可重试、版本常量 |
| `travel-agent-web/src/components/FootprintMap/maplibreLoader.test.ts` | 新建：URL 拼接 / 导出校验 / 并发单例 / 失败重试（8 个用例） |
| `travel-agent-web/src/components/FootprintMap/index.tsx` | 去掉 maplibre 值导入，effect 中异步加载；卸载安全；加载失败可见降级 + 重试 |
| `travel-agent-web/scripts/check-bundle-budget.mjs` | 新建：包体预算门禁（含 vendor 版本三方一致、precache 解析） |
| `travel-agent-web/nginx.conf`（待部署侧确认） | 需要按第 5 节补 `/vendor/maplibre/` 的 `mjs` MIME；本任务不改部署文件，作为上线前置项跟踪 |

`src/components/FootprintMap/basemap.ts`、`src/pages/Footprint/index.tsx` 无需改动：前者本来就是 `import type`；
后者是懒加载路由，且只在有可定位足迹时才渲染 `FootprintMap`。

---

## 7. 遗留风险

1. **nginx MIME（高）**：不按第 5 节配置时，Docker/生产环境的 `.mjs` 会被拒绝执行，地图显示降级提示（可点重试，但不会成功）。
   本地 `vite preview`、e2e 的 `vite preview` 不受影响；需要在部署配置里落实。
2. **地图引擎体积贴线**：主模块 gzip9 299,604 B，距 300 kB 预算仅 ~400 B。升级 maplibre 或新增插件/样式时要先跑 `npm run build:check`，
   按规则评估新阈值，不要降级到有漏洞的版本。
3. **worker 与主文件的相对路径**：主模块、worker 必须同目录且保持官方文件名；以后若想改成 CDN 子路径，必须整目录一起搬，
   否则 maplibre 的 `import.meta.url` 推导会 404。
4. **运行时加载不可被 Rollup 分析**：vendor 文件不参与 tree-shaking/完整性校验；版本目录 + 预算脚本 + loader 导出校验是当前的护栏。
5. **precache 仍包含地图 CSS（约 70 KB）**：CSS 是懒加载 chunk 且体积小，按当前预算策略保留；
   若以后要极致压缩 precache，可再把地图 CSS 也改为运行时加载。