import { copyFileSync, mkdirSync, readFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { defineConfig, type Plugin } from 'vite';
import react from '@vitejs/plugin-react';
import { VitePWA } from 'vite-plugin-pwa';
import { visualizer } from 'rollup-plugin-visualizer';

const ROOT = dirname(fileURLToPath(import.meta.url));

/**
 * 自托管的地图引擎文件。原样从 node_modules/maplibre-gl/dist 取出，不参与 Rollup 分块：
 * - maplibre-gl.mjs        主模块（浏览器在真正渲染地图时才 import）
 * - maplibre-gl-worker.mjs worker（maplibre 用 import.meta.url 推导同目录相对路径，必须同名同目录）
 * - maplibre-gl.css        样式（业务侧仍静态 import，只进懒加载页面的 CSS，不拉 JS）
 */
const MAPLIBRE_FILES = ['maplibre-gl.mjs', 'maplibre-gl-worker.mjs', 'maplibre-gl.css'] as const;

/**
 * 读取 node_modules 里的真实版本，作为 vendor 目录名。
 * 目录带版本号：升级依赖后 URL 变化，浏览器/CDN 缓存自然失效，
 * 不会出现「旧 HTML 配新 worker」的版本错配。
 */
function readMaplibreVersion(): string {
  const pkgPath = resolve(ROOT, 'node_modules/maplibre-gl/package.json');
  return (JSON.parse(readFileSync(pkgPath, 'utf8')) as { version: string }).version;
}

/**
 * 把 MapLibre 从 Rollup 的 import 图里摘出去：
 * - dev：`/vendor/maplibre/<version>/*` 由中间件直接从 node_modules 读，天然与依赖同步；
 * - build：`closeBundle` 把三个文件原样拷到 `dist/vendor/maplibre/<version>/`（幂等）。
 *
 * 这样页面里只剩 `import type` 与运行时 `import()`（URL 带 @vite-ignore），
 * 构建产物不会再有 maps chunk，PWA 也通过 globIgnores 排除 vendor。
 */
function maplibreVendor(): Plugin {
  const version = readMaplibreVersion();
  const distDir = resolve(ROOT, 'node_modules/maplibre-gl/dist');
  const urlPrefix = `/vendor/maplibre/${version}/`;
  const allowed = new Set<string>(MAPLIBRE_FILES);

  return {
    name: 'travel-agent:maplibre-vendor',
    configureServer(server) {
      server.middlewares.use((req, res, next) => {
        // worker 请求会带 ?v=<version> 的缓存参数，必须按 pathname 匹配
        const pathname = (req.url ?? '').split('?')[0];
        if (!pathname.startsWith(urlPrefix)) {
          next();
          return;
        }
        const file = pathname.slice(urlPrefix.length);
        // 该前缀下只允许白名单文件：不能交给 SPA fallback，否则请求错文件会拿到 200 的 index.html
        if (!allowed.has(file)) {
          res.statusCode = 404;
          res.end(`maplibre vendor asset not found: ${file}`);
          return;
        }
        try {
          const body = readFileSync(resolve(distDir, file));
          res.setHeader(
            'Content-Type',
            file.endsWith('.css') ? 'text/css; charset=utf-8' : 'text/javascript; charset=utf-8',
          );
          res.setHeader('Cache-Control', 'no-cache');
          res.end(body);
        } catch {
          res.statusCode = 404;
          res.end(`maplibre vendor asset not found: ${file}`);
        }
      });
    },
    closeBundle() {
      const outDir = resolve(ROOT, 'dist/vendor/maplibre', version);
      mkdirSync(outDir, { recursive: true });
      for (const file of MAPLIBRE_FILES) {
        copyFileSync(resolve(distDir, file), resolve(outDir, file));
      }
      console.log(`\n[vendor] maplibre ${version} → dist/vendor/maplibre/${version}/`);
    },
  };
}

/**
 * /api 反代目标：默认本地 8080 后端；e2e 需要独立后端时用 E2E_API_TARGET 指向它
 * （例如本地 8080 被占用时后端起在 18081，不影响 dev/preview 的默认行为）。
 */
const apiTarget = process.env.E2E_API_TARGET ?? 'http://localhost:8080';

/**
 * PWA 离线查看行程：只缓存三个**只读 GET**。
 * 命中范围（整串匹配 URL，含 query）：
 *   GET /api/trips                       —— 行程列表
 *   GET /api/trips/<id>/itinerary        —— 某趟行程的安排
 *   GET /api/trips/<id>/budget           —— 某趟行程的预算执行
 * 故意不匹配 /api/trips/<id>（行程详情）：契约里没有要求，少缓存一类就少一类脏数据。
 */
const ITINERARY_API_PATTERN =
  /^https?:\/\/[^/]+\/api\/trips(?:\/\d+\/(?:itinerary|budget))?(?:\?[^#]*)?$/;

/**
 * 缓存版本号：改缓存策略/数据结构时 +1。旧容器由 workbox 的 cleanupOutdatedCaches 清掉，
 * 用户不需要手动清站点数据。
 */
const ITINERARY_CACHE_NAME = 'itinerary-v1';

export default defineConfig(({ mode }) => ({
  plugins: [
    react(),
    maplibreVendor(),
    // PWA：只预缓存 app shell（构建产物 + SPA 回退）。API 一律不缓存——
    // 数据全部带登录态且实时性强（SSE/记账/行程），离线只保证“页面打得开不白屏”
    VitePWA({
      registerType: 'autoUpdate',
      includeAssets: ['favicon.svg'],
      manifest: {
        name: 'AI 智能旅行助手',
        short_name: '旅行助手',
        description: '行程规划 · 旅行知识库 · 足迹与照片 · 记账备忘 · 日程提醒',
        lang: 'zh-CN',
        theme_color: '#1677ff',
        background_color: '#ffffff',
        display: 'standalone',
        icons: [
          { src: 'favicon.svg', sizes: '192x192', type: 'image/svg+xml', purpose: 'any' },
          { src: 'favicon.svg', sizes: '512x512', type: 'image/svg+xml', purpose: 'any' },
          { src: 'favicon.svg', sizes: '512x512', type: 'image/svg+xml', purpose: 'maskable' },
        ],
      },
      workbox: {
        navigateFallback: '/index.html',
        // SSE（/api/agent）绝不能进任何缓存；保守起见整个 /api 前缀都不做导航回退
        navigateFallbackDenylist: [/^\/api\//],
        // 版本升级后清掉上一版 precache / runtime 缓存容器（只清 workbox 自己建的）
        cleanupOutdatedCaches: true,
        // 地图引擎不进 precache：它是按需加载的大文件，预缓存会让所有用户白下载 1.6 MB；
        // 地图页真正打开时走浏览器 HTTP 缓存（vendor URL 带版本号，可安全长缓存）。
        // bundle-stats.html 只在 analyze 模式产生，也不该被当成 app shell 预缓存。
        globIgnores: ['**/vendor/maplibre/**', '**/bundle-stats.html'],
        // 不动 precache：仍只预缓存 app shell（构建产物 + SPA 回退），不扩大体积。
        // runtimeCaching 只管下面这一条：离线时能继续看已打开过的行程。
        runtimeCaching: [
          {
            urlPattern: ITINERARY_API_PATTERN,
            // method: 'GET' 必须显式写死：不写的话 workbox 会为非 GET 也注册路由，
            // 一旦 POST/PUT/DELETE 被缓存，离线时会拿旧响应冒充"写入成功"。
            method: 'GET',
            handler: 'NetworkFirst',
            options: {
              cacheName: ITINERARY_CACHE_NAME,
              // 网络 3 秒拿不到就走缓存（弱网下不让用户干等 30s）
              networkTimeoutSeconds: 3,
              // 5 分钟过期、最多 50 条：行程数据实时性要求高，宁可短暂离线可用
              expiration: { maxEntries: 50, maxAgeSeconds: 300 },
              // 只缓存 200：401/5xx 不进缓存，避免把"登录失效"页面缓存下来反复出现
              cacheableResponse: { statuses: [200] },
            },
          },
        ],
      },
    }),
    // 只有 npm run analyze（vite build --mode analyze）才产出交互式包体分析页
    ...(mode === 'analyze'
      ? [visualizer({ filename: 'dist/bundle-stats.html', gzipSize: true, open: false })]
      : []),
  ],
  build: {
    // 现代浏览器目标：跳过 esbuild 的降级转译（在部分 Windows 环境里该步骤清理临时目录会被系统拒绝）
    target: 'esnext',
    // 压缩改用 Terser：esbuild 在该环境清理临时目录时会被系统拒绝（Access is denied）
    minify: 'terser',
    // 800 kB 是单文件告警线：地图引擎已移出 Rollup 图（vendor 单独预算），
    // 任何 JS chunk 再越过这条线都说明有新的重依赖被静态引入。
    chunkSizeWarningLimit: 800,
    rollupOptions: {
      output: {
        manualChunks(id) {
          if (!id.includes('node_modules')) {
            return undefined;
          }
          // echarts / zrender 较大且被多个懒加载页面共享，单独成块便于长期缓存。
          // 地图引擎不再进入这里：它由 maplibreLoader 运行时按需加载，不参与分块。
          if (id.includes('echarts') || id.includes('zrender')) {
            return 'echarts';
          }
          // 其余依赖（含 antd / rc-* / react）交给 Rollup 自动分块：
          // 之前把剩余依赖统一塞进 vendor，会与 react 互相引用形成 vendor -> react -> vendor 循环。
          return undefined;
        },
      },
    },
  },
  server: {
    port: 5173,
    proxy: {
      '/api': {
        target: apiTarget,
        changeOrigin: true,
      },
    },
  },
  // preview（vite preview / e2e）与 dev 同源转发 /api，e2e 无需单独 mock 后端
  preview: {
    port: 4173,
    proxy: {
      '/api': {
        target: apiTarget,
        changeOrigin: true,
      },
    },
  },
}));