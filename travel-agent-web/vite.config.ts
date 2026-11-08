import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';
import { VitePWA } from 'vite-plugin-pwa';

export default defineConfig({
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
        // SSE（/api/agent）绝不能进任何缓存；保守起见整个 /api 前缀都不做 runtime 缓存
        navigateFallbackDenylist: [/^\/api\//],
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
          if (id.includes('maplibre-gl') || id.includes('leaflet')) {
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
        target: 'http://localhost:8080',
        changeOrigin: true,
      },
    },
  },
  // preview（vite preview / e2e）与 dev 同源转发 /api，e2e 无需单独 mock 后端
  preview: {
    port: 4173,
    proxy: {
      '/api': {
        target: 'http://localhost:8080',
        changeOrigin: true,
      },
    },
  },
});
