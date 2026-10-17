import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

export default defineConfig({
  plugins: [react()],
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
});
