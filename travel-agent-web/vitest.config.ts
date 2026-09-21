import { defineConfig } from 'vitest/config';
import react from '@vitejs/plugin-react';

export default defineConfig({
  plugins: [react()],
  test: {
    // 默认使用 node 环境：当前仓库的测试覆盖 store / API 客户端等纯逻辑。
    // 需要渲染组件的测试可在文件顶部加 `// @vitest-environment jsdom`
    // （注意：jsdom 依赖 require(ESM)，需要 Node >= 20.19）。
    environment: 'node',
    include: ['src/**/*.test.{ts,tsx}'],
    setupFiles: ['./src/test/setup.ts'],
    restoreMocks: true,
  },
});
