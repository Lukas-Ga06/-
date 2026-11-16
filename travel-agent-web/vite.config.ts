import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';
import { VitePWA } from 'vite-plugin-pwa';

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

export default defineConfig({
  /**
   * maplibre-gl 自带 worker（maplibre-gl-worker.mjs）。Vite 预打包依赖时不会把这个 worker
   * 一起产出到 node_modules/.vite/deps，dev 下地图会报
   * 「Worker failed to load. Check that the worker URL is correct.」并退化成空白底图。
   * 把 maplibre-gl 排除出预打包，让浏览器直接按 ESM 加载它自己的 worker 路径即可；
   * 生产构建（vite build）本来就走 Rollup，没有这个问题。
   */
  optimizeDeps: {
    exclude: ['maplibre-gl'],
  },
  plugins: [
    react(),
    // PWA：只预缓存 app shell（构建产物 + SPA 回退）。API 一律不缓存——
    // 数据全部带登录态且实时性强（SSE/记账/行程），离线只保证“页面打得开不白屏”
    VitePWA({
      registerType: 'autoUpdate',
      includeAssets: ['favicon.svg'],
      manifest: {
        name: 'AI 个人助理',
        short_name: '个人助理',
        description: '旅行规划 · 记账 · 备忘 · 办公 · 学习 · 健康',
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
  ],
  build: {
    // 现代浏览器目标：跳过 esbuild 的降级转译（在部分 Windows 环境里该步骤清理临时目录会被系统拒绝）
    target: 'esnext',
    // 压缩改用 Terser：esbuild 在该环境清理临时目录时会被系统拒绝（Access is denied）
    minify: 'terser',
    // 把体积大且更新频率低的依赖拆成独立 chunk：
    // 首屏只加载 react 相关与当前路由页面，图表/地图按需加载并长期缓存。
    chunkSizeWarningLimit: 800,
    rollupOptions: {
      output: {
        manualChunks(id) {
          if (!id.includes('node_modules')) {
            return undefined;
          }
          if (id.includes('echarts') || id.includes('zrender')) {
            return 'echarts';
          }
          if (id.includes('maplibre-gl')) {
            return 'maps';
          }
          if (id.includes('antd') || id.includes('@ant-design') || id.includes('rc-')) {
            return 'antd';
          }
          // 其余依赖交给 Rollup 自动分块：
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
});
