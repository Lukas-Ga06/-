import { defineConfig } from 'vitest/config';
import react from '@vitejs/plugin-react';

export default defineConfig({
  plugins: [react()],
  test: {
    // 默认使用 node 环境：当前仓库的测试覆盖 store / API 客户端等纯逻辑。
    // 需要渲染组件的测试可在文件顶部加 `// @vitest-environment happy-dom`
    // （注意：jsdom 依赖 require(ESM)，需要 Node >= 20.19）。
    environment: 'node',
    include: ['src/**/*.test.{ts,tsx}'],
    setupFiles: ['./src/test/setup.ts'],
    restoreMocks: true,
    coverage: {
      provider: 'v8',
      reporter: ['text', 'json-summary', 'html'],
      reportsDirectory: './coverage',
      // 只统计运行期源码：测试、测试工具、入口、OpenAPI 生成类型与纯类型目录不参与。
      include: ['src/**/*.{ts,tsx}'],
      exclude: [
        'src/**/*.test.{ts,tsx}',
        'src/test/**',
        'src/main.tsx',
        'src/api/generated/**',
        'src/**/*.d.ts',
        'src/types/**',
      ],
      // 基线 + ratchet，只能上调：
      // 全局基线（2026-11-20 复核，445 个测试）：lines 57.63 / branches 55.58 / functions 51.60 / statements 56.86
      //（2026-11-19 首版基线为 224 个测试下的 39.55 / 39.81 / 34.11 / 38.31，本轮按 ratchet 上调）
      // —— 大量页面组件由 Playwright e2e 覆盖（playwright.config.ts + e2e/smoke.spec.ts），
      //    不强求单测；本阈值防的是「存量单测悄悄退步」。
      // 核心目录（src/store|api|lib）基线：lines 66.73 / branches 66.42 / functions 54.21 / statements 64.57
      // —— 业务状态与请求封装必须由单测守住，阈值单独更高；
      //    由 node scripts/check-core-coverage.mjs 读 coverage-summary.json 校验。
      // 阈值 = 基线向下取整 2-3 个点；下调必须在 PR 说明具体文件与原因，并同步 docs/guide/frontend-quality.md。
      thresholds: {
        lines: 55,
        branches: 53,
        functions: 49,
        statements: 54,
      },
    },
  },
});
