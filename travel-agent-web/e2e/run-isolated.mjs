#!/usr/bin/env node
/**
 * 隔离 e2e 启动器：让 Playwright 自己拉起「无真实外部依赖」的后端，再跑浏览器用例。
 *
 * 解决的问题：历史 `npm run e2e` 默认连接 http://localhost:8080，如果开发者用
 * `travel-agent-server/.env.local` 启动过后端，e2e 实际打到真实 LLM/高德：
 * 慢、烧配额、结果不可重复，而且“通过”不能证明隔离环境可用。
 *
 * 做法：
 * 1) 设置 E2E_START_BACKEND=1，playwright.config.ts 据此把 scripts/start-e2e-backend.mjs
 *    作为第一个 webServer（独立 H2 内存库、LLM/MCP/AMAP 全关）；
 * 2) E2E_API_TARGET 指向该实例，`vite preview` 的 /api 反代随之走隔离后端；
 * 3) E2E_ASSERT_ISOLATION=1，e2e/isolation.spec.ts 会断言 /api/system/status 里所有
 *    外部 provider 确实是关闭的——配置漂移会直接让门禁变红，而不是静默通过。
 *
 * 用法：
 *   npm run build            # preview 需要 dist；脚本检测到缺失会自动构建一次
 *   npm run e2e:isolated     # 默认套件（Chromium 桌面）
 *   npm run e2e:device:isolated  # 移动端 WebKit + 弱网 + 多端并发
 *   node e2e/run-isolated.mjs --suite mobile -- --grep 地图
 */
import { spawn } from 'node:child_process';
import fs from 'node:fs';
import path from 'node:path';
import { createRequire } from 'node:module';
import { fileURLToPath } from 'node:url';

const E2E_DIR = path.dirname(fileURLToPath(import.meta.url));
const WEB_DIR = path.resolve(E2E_DIR, '..');
const require = createRequire(import.meta.url);

const argv = process.argv.slice(2);
const suiteIndex = argv.indexOf('--suite');
const suite = suiteIndex >= 0 ? (argv[suiteIndex + 1] ?? '').toLowerCase() : 'default';
const passthrough = argv.filter((_, index) => index !== suiteIndex && index !== suiteIndex + 1);
if (passthrough[0] === '--') passthrough.shift();

const VALID_SUITES = new Set(['default', 'mobile', 'network', 'multi', 'device']);
if (!VALID_SUITES.has(suite)) {
  console.error(`[e2e] 未知套件：${suite}（可选：${[...VALID_SUITES].join(' | ')}）`);
  process.exit(2);
}

const port = Number(process.env.E2E_API_PORT ?? 18081);
if (!Number.isInteger(port) || port <= 0 || port > 65535) {
  console.error(`[e2e] E2E_API_PORT 不合法：${process.env.E2E_API_PORT}`);
  process.exit(2);
}

const baseUrl = process.env.E2E_BASE_URL ?? 'http://localhost:4173';
const npm = process.platform === 'win32' ? 'npm.cmd' : 'npm';

/** preview 依赖 dist；缺了就构建一次，避免报 “dist/index.html not found” */
function ensureDist() {
  const index = path.join(WEB_DIR, 'dist', 'index.html');
  if (fs.existsSync(index)) {
    startPlaywright();
    return;
  }
  console.log('[e2e] dist/index.html 不存在，先执行 npm run build …');
  const build = spawn(npm, ['run', 'build'], {
    cwd: WEB_DIR,
    stdio: 'inherit',
    shell: process.platform === 'win32',
  });
  build.on('exit', (code) => {
    if (code !== 0) process.exit(code ?? 1);
    startPlaywright();
  });
  build.on('error', (error) => {
    console.error(`[e2e] 构建前端失败：${error.message}`);
    process.exit(1);
  });
}

function startPlaywright() {
  const pkgPath = require.resolve('@playwright/test/package.json');
  const cli = path.join(path.dirname(pkgPath), 'cli.js');
  const env = {
    ...process.env,
    E2E_START_BACKEND: '1',
    E2E_ASSERT_ISOLATION: '1',
    E2E_API_TARGET: `http://127.0.0.1:${port}`,
    E2E_API_PORT: String(port),
    E2E_BASE_URL: baseUrl,
    E2E_SUITE: suite,
  };
  console.log(
    `[e2e] 隔离模式：suite=${suite} backend=http://127.0.0.1:${port} preview=${baseUrl}\n` +
      '[e2e] 后端：H2 内存库 · LLM/Embedding/MCP/AMAP 关闭 · 限流关闭',
  );
  const child = spawn(process.execPath, [cli, 'test', ...passthrough], {
    cwd: WEB_DIR,
    stdio: 'inherit',
    env,
  });
  child.on('exit', (code, signal) => {
    if (signal) {
      console.error(`[e2e] Playwright 被信号 ${signal} 中断`);
      process.exit(1);
    }
    process.exit(code ?? 1);
  });
  child.on('error', (error) => {
    console.error(`[e2e] 启动 Playwright 失败：${error.message}`);
    process.exit(1);
  });
}

ensureDist();