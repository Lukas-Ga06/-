import { defineConfig, devices } from '@playwright/test';

/**
 * e2e 冒烟：跑在 `vite preview`（4173，/api 代理到 8080）之上。
 * 需要先启动后端（本地：mvn spring-boot:run；CI 见 ci.yml e2e job）。
 * 前置：npm run build && npm run e2e（webServer 会自动起 preview）。
 */
export default defineConfig({
  testDir: './e2e',
  timeout: 30_000,
  retries: process.env.CI ? 1 : 0,
  use: {
    baseURL: 'http://localhost:4173',
    trace: 'retain-on-failure',
    locale: 'zh-CN',
  },
  projects: [{ name: 'chromium', use: { ...devices['Desktop Chrome'] } }],
  webServer: {
    command: 'npm run preview',
    url: 'http://localhost:4173',
    reuseExistingServer: !process.env.CI,
    timeout: 60_000,
  },
});
