#!/usr/bin/env node
/**
 * 专项 e2e 套件启动器：把套件名转成 `E2E_SUITE`，再原样透传命令行参数给 Playwright。
 *
 * 为什么不直接在 package.json 里写 `E2E_SUITE=network playwright test`：
 * Windows 的 npm script 走 cmd.exe，不支持 `VAR=value cmd` 前缀；装 cross-env 又会多一个依赖。
 * 用 Node 设环境变量再 spawn，Windows / Linux / macOS 行为一致，且不额外安装任何包。
 *
 * 用法：
 *   node e2e/run-suite.mjs network [playwright 参数...]
 * 等价于：
 *   E2E_SUITE=network npx playwright test [playwright 参数...]
 */
import { spawn } from 'node:child_process';
import { createRequire } from 'node:module';
import path from 'node:path';

const SUITES = new Set(['default', 'network', 'multi', 'device']);
const [, , suite, ...rest] = process.argv;

if (!suite || !SUITES.has(suite.toLowerCase())) {
  console.error(`[run-suite] 需要套件名：${[...SUITES].join(' | ')}`);
  console.error('  例：node e2e/run-suite.mjs network');
  process.exit(2);
}

// 定位 Playwright CLI：跟着本项目的依赖走，不依赖全局 PATH
const require = createRequire(import.meta.url);
const pkgPath = require.resolve('@playwright/test/package.json');
const cli = path.join(path.dirname(pkgPath), 'cli.js');

const child = spawn(process.execPath, [cli, 'test', ...rest], {
  stdio: 'inherit',
  env: { ...process.env, E2E_SUITE: suite.toLowerCase() },
});

child.on('exit', (code, signal) => {
  if (signal) {
    console.error(`[run-suite] Playwright 被信号 ${signal} 中断`);
    process.exit(1);
  }
  process.exit(code ?? 1);
});
child.on('error', (error) => {
  console.error(`[run-suite] 启动 Playwright 失败：${error.message}`);
  process.exit(1);
});