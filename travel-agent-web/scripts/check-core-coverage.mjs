#!/usr/bin/env node
/**
 * 核心目录覆盖率门槛（全局阈值之外的第二道 ratchet）。
 *
 * 背景：全局覆盖率里大量页面组件由 Playwright e2e 覆盖，单测占比天然偏低；
 * 但 `src/store`、`src/api`、`src/lib` 是业务状态与请求封装，必须由单测守住。
 * 本脚本读 `coverage/coverage-summary.json`（`npm run test:coverage` 产物），
 * 对三个目录聚合校验独立阈值，失败 exit 1。
 *
 * 用法：npm run test:coverage && node scripts/check-core-coverage.mjs
 *
 * 阈值 = 2026-11-19 基线向下取整 2-3 个点，只允许上调（ratchet），
 * 下调必须在 PR 说明具体文件与原因，并同步 docs/guide/frontend-quality.md。
 */
import { existsSync, readFileSync } from 'node:fs';
import { resolve } from 'node:path';

const summaryPath = resolve(process.cwd(), 'coverage', 'coverage-summary.json');

if (!existsSync(summaryPath)) {
  console.error(
    '[core-coverage] 找不到 coverage/coverage-summary.json，请先运行 npm run test:coverage',
  );
  process.exit(1);
}

const summary = JSON.parse(readFileSync(summaryPath, 'utf8'));

/**
 * 阈值 = 基线向下取整 2-3 个点（ratchet，只上调）。
 * 2026-11-20 复核基线：lines 79.21 / branches 76.72 / functions 68.97 / statements 78.18
 *（较 2026-11-19 的 66.73 / 66.42 / 54.21 / 64.57 大幅提升：离线待同步队列与行程聚焦逻辑
 *  都以纯逻辑 + 单测的形式落在 src/lib、src/store 里）。
 */
const THRESHOLDS = {
  lines: 76,
  branches: 74,
  functions: 66,
  statements: 75,
};

const totals = {
  lines: [0, 0],
  branches: [0, 0],
  functions: [0, 0],
  statements: [0, 0],
};

for (const [path, data] of Object.entries(summary)) {
  if (path === 'total') continue;
  const normalized = path.replace(/\\/g, '/');
  if (!/(\/src\/store\/|\/src\/api\/|\/src\/lib\/)/.test(normalized)) continue;
  for (const metric of Object.keys(totals)) {
    totals[metric][0] += data[metric].covered;
    totals[metric][1] += data[metric].total;
  }
}

const failures = [];
console.log('\n核心目录覆盖率（src/store | src/api | src/lib）');
console.log('─'.repeat(60));
for (const [metric, [covered, total]] of Object.entries(totals)) {
  if (total === 0) {
    failures.push(`${metric}: 无数据（include/exclude 是否误排了核心目录？）`);
    console.log(`  ✗ ${metric.padEnd(12)} 无数据`);
    continue;
  }
  const pct = (covered / total) * 100;
  const min = THRESHOLDS[metric];
  const ok = pct >= min;
  if (!ok) failures.push(`${metric}: ${pct.toFixed(2)}% < ${min}%`);
  console.log(
    `  ${ok ? '✓' : '✗'} ${metric.padEnd(12)} ${pct.toFixed(2).padStart(7)}%  (阈值 ≥ ${min}%, ${covered}/${total})`,
  );
}

if (failures.length > 0) {
  console.error(`\n[core-coverage] ✗ 低于阈值：\n  - ${failures.join('\n  - ')}`);
  console.error(
    '  只允许上调阈值（ratchet）；下调必须在 PR 中说明并同步 docs/guide/frontend-quality.md',
  );
  process.exit(1);
}
console.log('\n[core-coverage] ✓ 核心目录覆盖率全部达标');
