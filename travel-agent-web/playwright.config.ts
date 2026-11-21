import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { defineConfig, devices } from '@playwright/test';

/**
 * e2e 冒烟：跑在 `vite preview`（默认 4173，/api 反代到后端）之上。
 * 需要先启动后端：本地见 .review/batch-c-web.md，CI 见 .github/workflows/ci.yml 的 e2e job。
 *
 * 可重复性要点（B7）：
 * - 只登录一次：globalSetup 登录 demo 写 e2e/.auth/demo.json，用例通过 storageState 复用；
 * - 串行执行：auth 接口按 IP 限流，并行/多 worker 会把请求叠加成 429；
 * - e2e 后端关闭限流（RATE_LIMIT_ENABLED=false）；
 * - 屏蔽 Service Worker：避免 PWA 预缓存的旧资源干扰用例。
 */
const baseURL = process.env.E2E_BASE_URL ?? 'http://localhost:4173';
const previewPort = Number(new URL(baseURL).port || 4173);
// 本项目 package.json 是 "type": "module"，配置走 ESM，没有 __dirname
const configDir = path.dirname(fileURLToPath(import.meta.url));

export default defineConfig({
  testDir: './e2e',
  // 视觉回归（e2e/visual）走独立配置与桩数据，不参与需要后端登录的 e2e 套件
  testIgnore: ['**/visual/**'],
  timeout: 30_000,
  // 本地 0 / CI 1：保留 trace retain-on-failure 的排障能力
  retries: process.env.CI ? 1 : 0,
  // 认证限流按 IP 计数，必须串行，避免同一轮内请求量叠加成 429
  fullyParallel: false,
  workers: 1,
  // 一次登录，全部用例复用（e2e/global-setup.ts 写 e2e/.auth/demo.json）
  globalSetup: './e2e/global-setup.ts',
  reporter: process.env.CI ? [['list'], ['html', { open: 'never' }]] : 'list',
  use: {
    baseURL,
    storageState: path.resolve(configDir, 'e2e/.auth/demo.json'),
    trace: 'retain-on-failure',
    locale: 'zh-CN',
    serviceWorkers: 'block',
  },
  projects: [{ name: 'chromium', use: { ...devices['Desktop Chrome'] } }],
  webServer: {
    command: `npm run preview -- --port ${previewPort} --strictPort`,
    url: baseURL,
    reuseExistingServer: !process.env.CI,
    timeout: 60_000,
  },
});