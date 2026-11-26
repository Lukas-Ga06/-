#!/usr/bin/env node
/**
 * alerts-rules-extract.mjs — 从 docs/operations/alerts.yml（Prometheus Operator 的 PrometheusRule CRD）
 * 抽出 `spec.groups`，产出裸 Prometheus 能直接放进 rule_files 的规则文件（零依赖，Node 18+/20+）。
 *
 * 为什么需要它：
 *   alerts.yml 的顶层是 apiVersion/kind/metadata/spec（kubectl apply 用），而裸 Prometheus 的
 *   rule_files 只认顶层 `groups:`。仓库注释里写的是「把 spec.groups 的内容放进 rule_files 指向的文件」，
 *   但这件事一直靠手工复制 —— 手抄一次的后果是「规则文件里少一条，告警静默消失」，且 CI 无从校验。
 *   本脚本把这一步变成一条命令，演练脚本也用它验证 22 条规则能被当前 Prometheus 解析。
 *
 * 用法：
 *   node scripts/alerts-rules-extract.mjs -Out .evidence/alerts-rules.yml
 *   node scripts/alerts-rules-extract.mjs -Out out.yml --summary
 *
 * 退出码：0 成功；2 参数/输入问题；3 结构不符合预期（找不到 spec.groups，或抽出后为空）。
 *
 * 边界：只做「缩进剥离」，不做 YAML 解析/重排 —— 保持与源文件逐字一致，便于 diff 与审计。
 */

import { readFileSync, writeFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const DEFAULT_IN = resolve(ROOT, 'docs', 'operations', 'alerts.yml');

function parseArgs(argv) {
  const opts = { in: DEFAULT_IN, out: '', summary: false };
  for (let i = 0; i < argv.length; i += 1) {
    const arg = argv[i];
    if (arg === '-Out' || arg === '--out') opts.out = argv[++i];
    else if (arg === '-In' || arg === '--in') opts.in = argv[++i];
    else if (arg === '--summary') opts.summary = true;
    else throw new Error(`未知参数：${arg}`);
  }
  if (!opts.out) throw new Error('-Out 必填');
  return opts;
}

function main() {
  const opts = parseArgs(process.argv.slice(2));
  const text = readFileSync(opts.in, 'utf8');
  const lines = text.split(/\r?\n/);

  const specIndex = lines.findIndex((l) => /^spec:\s*$/.test(l));
  if (specIndex < 0) {
    console.error(`[FAIL] ${opts.in} 里找不到顶层 spec:（不是 PrometheusRule 结构？）`);
    return 3;
  }
  const groupsIndex = lines.findIndex((l, i) => i > specIndex && /^  groups:\s*$/.test(l));
  if (groupsIndex < 0) {
    console.error(`[FAIL] ${opts.in} 的 spec: 下找不到两空格缩进的 groups:（结构变了？）`);
    return 3;
  }

  const body = [];
  for (let i = groupsIndex; i < lines.length; i += 1) {
    const line = lines[i];
    if (line.trim() === '') {
      body.push('');
      continue;
    }
    if (!line.startsWith('  ')) {
      // spec.groups 之后又出现顶层键（理论上不会有）：停下，避免把别的资源拼进来
      console.error(`[WARN] 第 ${i + 1} 行回到 0 缩进，停止抽取：${line.slice(0, 40)}`);
      break;
    }
    body.push(line.slice(2));
  }

  const outText = `${body.join('\n').replace(/\n+$/, '')}\n`;
  if (!/^groups:\s*$/m.test(outText)) {
    console.error('[FAIL] 抽出内容不以 groups: 开头，拒绝写出');
    return 3;
  }

  const groupCount = (outText.match(/^ {2}- name:/gm) || []).length;
  const ruleCount = (outText.match(/^ {6}- alert:/gm) || []).length;
  if (groupCount === 0 || ruleCount === 0) {
    console.error(`[FAIL] 抽出结果为空（groups=${groupCount}, rules=${ruleCount}）`);
    return 3;
  }

  const outPath = resolve(process.cwd(), opts.out);
  writeFileSync(outPath, outText, 'utf8');
  console.log(`[ OK ] ${outPath}（${groupCount} 组 / ${ruleCount} 条规则，${Buffer.byteLength(outText, 'utf8')} 字节）`);
  if (opts.summary) {
    const names = [...outText.matchAll(/^ {6}- alert: (\S+)/gm)].map((m) => m[1]);
    console.log(`[INFO] 规则清单：${names.join(', ')}`);
  }
  return 0;
}

try {
  process.exitCode = main();
} catch (err) {
  console.error(`[FAIL] ${err.message}`);
  process.exitCode = 2;
}