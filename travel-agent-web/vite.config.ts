import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

export default defineConfig({
  plugins: [react()],
  build: {
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
          if (id.includes('react-dom') || id.includes('react-router') || id.includes('scheduler') || id.includes('/react/')) {
            return 'react';
          }
          return 'vendor';
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
