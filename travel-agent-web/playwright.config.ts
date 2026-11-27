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

/**
 * 隔离后端（发布门禁口径）：`npm run e2e:isolated` / `e2e:device:isolated` 会设置
 * E2E_START_BACKEND=1，Playwright 先拉起 scripts/start-e2e-backend.mjs：
 * 独立 H2 内存库 + LLM/Embedding/MCP/AMAP 全关 + 限流关闭，绝不读开发者 .env.local。
 *
 * 默认的 `npx playwright test` 保持旧行为（连 8080），方便本地快速迭代；
 * 但它不能作为发布证据，isolation.spec.ts 只在 E2E_ASSERT_ISOLATION=1 时运行。
 */
const ISOLATED_BACKEND = process.env.E2E_START_BACKEND === '1';
const API_PORT = Number(process.env.E2E_API_PORT ?? 18081);
if (ISOLATED_BACKEND) {
  // 隔离模式下必须让 vite preview 的 /api 反代指向隔离实例：
  // 显式设置一次，避免调用方漏传 E2E_API_TARGET 时悄悄打回 8080 的开发者后端。
  process.env.E2E_API_TARGET = `http://127.0.0.1:${API_PORT}`;
} else {
  console.warn(
    '[e2e] 未启用隔离后端：本次结果不是发布门禁口径（可能连到带真实 LLM/MCP Key 的 8080 实例）。' +
      '请用 `npm run e2e:isolated` 重新执行。',
  );
}
const BACKEND_SCRIPT = path.resolve(configDir, '../scripts/start-e2e-backend.mjs');

/**
 * 按 `E2E_SUITE` 选择要跑哪一套（默认 = 默认套件，行为与历史版本完全一致）。
 *
 * - `default`：桌面 Chromium 冒烟/规划/无障碍，跑在默认 `npm run e2e` 上（CI 门禁）；
 * - `network`：Chromium + CDP 弱网/离线；
 * - `multi`  ：Chromium，两个独立 context 同账号；
 * - `device` ：桌面 + 弱网 + 多端并发一次跑完（本地全量核对用）。
 *
 * 关键：默认跑法**不会**把 CDP 与多端用例拉进来 —— 每个项目用 testMatch 正向认领自己的文件，
 * 互不可见，于是 CI 的 `npx playwright test` 既不会变慢，也不会被专项用例的抖动拖红。
 */
const suite = (process.env.E2E_SUITE ?? 'default').trim().toLowerCase();

const NETWORK_SUITE = suite === 'network' || suite === 'device';
const MULTI_DEVICE_SUITE = suite === 'multi' || suite === 'device';
/**
 * 是否注册默认的桌面 Chromium 项目（冒烟/规划/无障碍那一批）。
 * 只有 default / device 需要它：network / multi 是专项套件，
 * 再挂一个 chromium 会把整套桌面用例一起跑一遍（实测：network 套件因此跑了 14 条无关用例，
 * 其中 a11y 的首页对比度用例还让专项套件无谓变红）。
 */
const DEFAULT_SUITE = suite === 'default' || suite === 'device';

/** 视觉回归（e2e/visual）走独立配置与桩数据，任何套件都不跑 */
const ALWAYS_IGNORED = ['**/visual/**'];

/**
 * 默认套件的文件白名单：**显式列出**桌面套件的文件，而不是「排除掉专项文件」。
 *
 * 为什么不能用排除法：默认项目（chromium）走 `testIgnore`，专项项目走 `testMatch`，
 * 两者在 `device` 汇总套件里会同时生效 —— 专项文件会被默认 chromium 项目也跑一遍，
 * 两边的断言口径不同，必然互相污染。改成正向白名单后，每个文件只属于一个项目，互不交叉。
 *
 * 新增桌面 e2e 文件时把它加到这里；新增专项文件则加进下面各自的 *_SPEC。
 */
const DEFAULT_SPECS = [
  'a11y.spec.ts',
  'isolation.spec.ts',
  'maplibre-vendor.spec.ts',
  'planner-form.spec.ts',
  'smoke.spec.ts',
];

/** 弱网项目：Chromium + CDP，独立文件与独立 npm script */
const NETWORK_SPEC = /[\\/]network-conditions\.spec\.ts$/;
/** 多端并发：Chromium，两个独立 context 同账号 */
const MULTI_DEVICE_SPEC = /[\\/]multi-device\.spec\.ts$/;

export default defineConfig({
  testDir: './e2e',
  // 视觉回归（e2e/visual）走独立配置与桩数据，不参与需要后端登录的 e2e 套件。
  // 专项文件由各项目的 testMatch 正向认领，这里不再用排除法（见 DEFAULT_SPECS 的说明）
  testIgnore: ALWAYS_IGNORED,
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
  /**
   * 项目清单按套件收窄：默认套件只有 chromium，与历史配置一致。
   * network / multi 各自只有在对应 E2E_SUITE 下才注册，
   * 这样 `npx playwright test` 不会把专项用例一起拉进来。
   */
  projects: [
    ... (DEFAULT_SUITE
      ? [{ name: 'chromium', testMatch: DEFAULT_SPECS, use: { ...devices['Desktop Chrome'] } }]
      : []),
    /** 弱网/离线：CDP 只有 Chromium 有，因此固定在 Desktop Chrome 上跑 */
    ...(NETWORK_SUITE
      ? [
          {
            name: 'network-chromium',
            testMatch: NETWORK_SPEC,
            timeout: 60_000,
            use: { ...devices['Desktop Chrome'] },
          },
        ]
      : []),
    /** 多端并发：两个独立 context 同账号，用桌面 Chromium 即可 */
    ...(MULTI_DEVICE_SUITE
      ? [
          {
            name: 'multi-device',
            testMatch: MULTI_DEVICE_SPEC,
            timeout: 60_000,
            use: { ...devices['Desktop Chrome'] },
          },
        ]
      : []),
  ],
  webServer: [
    // 隔离后端先起：timeout 覆盖 mvn package（首次）+ Spring 启动 + Flyway。
    // reuseExistingServer=false：宁可报“端口被占”，也不复用可能是开发者实例的后端。
    ...(ISOLATED_BACKEND
      ? [
          {
            command: `node "${BACKEND_SCRIPT}" --port ${API_PORT}`,
            url: `http://127.0.0.1:${API_PORT}/actuator/health`,
            timeout: 300_000,
            reuseExistingServer: false,
          },
        ]
      : []),
    {
      command: `npm run preview -- --port ${previewPort} --strictPort`,
      url: baseURL,
      reuseExistingServer: !process.env.CI,
      timeout: 60_000,
    },
  ],
});