import { defineConfig, devices } from '@playwright/test';

/**
 * 视觉回归（截图基线）专用配置 —— 与 `playwright.config.ts` 完全解耦：
 *
 * - **不需要后端 / MySQL / 真实模型**：`e2e/visual/fixtures.ts` 接管全部 /api/**；
 * - 不需要登录：`/auth/me` 由桩返回固定用户，绕过登录页之外的路由守卫；
 * - 时间冻结在 2026-11-21 10:00（`page.clock.setFixedTime`），
 *   因此「还有 21 天出发」「今天」这类文案不会在基线里漂移；
 * - 外网请求全部 abort（地图瓦片、字体 CDN 等），地图按「加载失败」降级，
 *   基线只覆盖应用自身布局，不依赖第三方服务可用性。
 *
 * 用法：
 *   npm run test:visual           # 与基线比较，不一致即失败（并输出 diff）
 *   npm run test:visual:update    # 有意改动视觉时重新生成基线（需人工 review 图片 diff）
 *
 * 注意：基线文件名带 `-win32` 平台后缀，Windows 上生成的基线不能在 Linux CI
 * 直接复用（字体渲染不同），因此本套件默认不进 CI 门禁，见 docs/guide/frontend-quality.md。
 */
const baseURL = process.env.E2E_BASE_URL ?? 'http://localhost:4173';
const previewPort = Number(new URL(baseURL).port || 4173);

export default defineConfig({
  testDir: './e2e/visual',
  timeout: 60_000,
  retries: 0,
  fullyParallel: false,
  // 预览服务只有一个，跑一份就够；worker 多了反而互相抢资源
  workers: 1,
  reporter: 'list',
  expect: {
    toHaveScreenshot: {
      // 逐像素比较：任何布局变化都应被看见；只有「抗锯齿级」噪声才落在 ratio 里
      maxDiffPixelRatio: 0,
      animations: 'disabled',
      caret: 'hide',
    },
  },
  use: {
    baseURL,
    locale: 'zh-CN',
    timezoneId: 'Asia/Shanghai',
    colorScheme: 'light',
    serviceWorkers: 'block',
    trace: 'retain-on-failure',
  },
  projects: [
    {
      name: 'chromium',
      use: {
        ...devices['Desktop Chrome'],
        viewport: { width: 1440, height: 900 },
        deviceScaleFactor: 1,
      },
    },
  ],
  webServer: {
    command: `npm run preview -- --port ${previewPort} --strictPort`,
    url: baseURL,
    reuseExistingServer: !process.env.CI,
    timeout: 60_000,
  },
});
