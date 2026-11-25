#!/usr/bin/env node
/**
 * render-alertmanager-config.mjs — 把 docs/operations/alertmanager.yml 的 ${ALERT_WEBHOOK_*}
 * 占位符替换成真实 webhook，产出可交给 Alertmanager 启动的渲染文件（零依赖，Node 18+/20+）。
 *
 * 为什么需要它：Alertmanager 不展开环境变量；而把真实 webhook 提交进仓库会被 gitleaks 拦下。
 * 所以仓库只存带占位的模板，部署时渲染一次，渲染产物不进版本库（见 .gitignore）。
 *
 * 用法：
 *   # 从环境变量读三个 webhook
 *   $env:ALERT_WEBHOOK_CRITICAL='https://...'; $env:ALERT_WEBHOOK_WARNING='https://...'; $env:ALERT_WEBHOOK_INFO='https://...'
 *   node scripts/render-alertmanager-config.mjs -Out alertmanager.rendered.yml
 *
 *   # 只渲染一部分（例如还没定 info 通道），其余沿用同名占位 → 脚本会拒绝写出，避免"半个配置"
 *   node scripts/render-alertmanager-config.mjs -Out out.yml --allow-missing   # 显式允许，产物标 UNRENDERED
 *
 *   # 渲染后直接交给 amtool 校验（工具在仓库外，找不到就跳过并提示）
 *   node scripts/render-alertmanager-config.mjs -Out out.yml --check
 *
 * 退出码：0 渲染成功；2 缺少环境变量/参数错误；3 渲染后仍残留占位符或 URL 非法。
 *
 * 安全边界：
 *   - 只读三个 ALERT_WEBHOOK_* 环境变量，不读 .env、不打印 webhook 明文（日志里只给 scheme+host 与长度）。
 *   - 产物权限：POSIX 下 0600；Windows 无等价语义，靠目录权限与 .gitignore。
 */

import { readFileSync, writeFileSync, chmodSync } from 'node:fs';
import { spawnSync } from 'node:child_process';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const TEMPLATE = resolve(ROOT, 'docs', 'operations', 'alertmanager.yml');

/** 模板里允许出现的占位符 → 对应环境变量。新增通道时必须同时改这里与模板。 */
const PLACEHOLDERS = {
  '${ALERT_WEBHOOK_CRITICAL}': 'ALERT_WEBHOOK_CRITICAL',
  '${ALERT_WEBHOOK_WARNING}': 'ALERT_WEBHOOK_WARNING',
  '${ALERT_WEBHOOK_INFO}': 'ALERT_WEBHOOK_INFO',
};

function parseArgs(argv) {
  const opts = { out: 'alertmanager.rendered.yml', template: TEMPLATE, allowMissing: false, check: false };
  for (let i = 0; i < argv.length; i += 1) {
    const arg = argv[i];
    if (arg === '-Out' || arg === '--out') opts.out = argv[++i];
    else if (arg === '--template') opts.template = argv[++i];
    else if (arg === '--allow-missing') opts.allowMissing = true;
    else if (arg === '--check') opts.check = true;
    else if (arg === '--help' || arg === '-h') opts.help = true;
    else throw new Error(`未知参数：${arg}`);
  }
  return opts;
}

/** 只保留 scheme + host + 前 6 位 path，用于日志；绝不打印完整 webhook（里面常带 token）。 */
function redact(url) {
  try {
    const u = new URL(url);
    const path = u.pathname.length > 6 ? `${u.pathname.slice(0, 6)}…` : u.pathname;
    return `${u.protocol}//${u.host}${path}（len=${url.length}）`;
  } catch {
    return `（非法 URL，len=${url.length}）`;
  }
}

function isValidWebhook(url) {
  try {
    const u = new URL(url);
    return u.protocol === 'https:' || u.protocol === 'http:';
  } catch {
    return false;
  }
}

function main() {
  const opts = parseArgs(process.argv.slice(2));
  if (opts.help) {
    console.log(readFileSync(TEMPLATE, 'utf8').split('\n').slice(0, 12).join('\n'));
    console.log('\n用法：node scripts/render-alertmanager-config.mjs -Out <file> [--allow-missing] [--check]');
    return 0;
  }

  let text = readFileSync(opts.template, 'utf8');
  const missing = [];
  let rendered = 0;

  for (const [token, envName] of Object.entries(PLACEHOLDERS)) {
    const value = (process.env[envName] || '').trim();
    if (!value) {
      missing.push(envName);
      continue;
    }
    if (!isValidWebhook(value)) {
      console.error(`[FAIL] ${envName} 不是 http(s) URL：${redact(value)}`);
      return 3;
    }
    const count = text.split(token).length - 1;
    if (count === 0) {
      console.error(`[WARN] 模板里找不到占位符 ${token}（模板与脚本不同步？）`);
      continue;
    }
    text = text.split(token).join(value);
    rendered += count;
    console.log(`[ OK ] ${envName} → ${redact(value)}`);
  }

  const leftovers = [...text.matchAll(/\$\{[A-Z0-9_]+\}/g)].map((m) => m[0]);
  if (leftovers.length > 0) {
    const uniq = [...new Set(leftovers)];
    if (!opts.allowMissing) {
      console.error(
        `[FAIL] 仍有 ${leftovers.length} 处占位符未渲染：${uniq.join(', ')}\n` +
          `       缺环境变量：${missing.join(', ') || '(无)'}\n` +
          '       全部给齐后再跑；确实要半成品请显式加 --allow-missing（产物标 UNRENDERED，Alertmanager 会启动失败）。',
      );
      return 3;
    }
    console.warn(`[WARN] 保留 ${leftovers.length} 处占位符（--allow-missing）：${uniq.join(', ')}`);
    text = `# UNRENDERED — 仍有未渲染占位符，Alertmanager 无法用此文件启动\n${text}`;
  }

  const outPath = resolve(process.cwd(), opts.out);
  writeFileSync(outPath, text, 'utf8');
  try {
    chmodSync(outPath, 0o600);
  } catch {
    /* Windows 无 POSIX 权限位，忽略 */
  }
  console.log(`[ OK ] 写出 ${outPath}（${rendered} 处替换，${Buffer.byteLength(text, 'utf8')} 字节）`);

  if (opts.check) {
    const amtool = process.env.AMTOOL_BIN || resolve(ROOT, '.tools/alertmanager/alertmanager-0.28.1.windows-amd64/amtool.exe');
    const probe = spawnSync(amtool, ['check-config', outPath], { encoding: 'utf8' });
    if (probe.error) {
      console.warn(`[WARN] 找不到 amtool（${amtool}），跳过 check-config；渲染结果未做语法校验。`);
      return 0;
    }
    if (probe.status !== 0) {
      console.error(`[FAIL] amtool check-config 失败：\n${probe.stdout}${probe.stderr}`);
      return 3;
    }
    console.log('[ OK ] amtool check-config 通过');
  }

  return 0;
}

process.exitCode = main();