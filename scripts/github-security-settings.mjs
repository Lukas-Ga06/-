#!/usr/bin/env node
/**
 * github-security-settings.mjs — 用 GitHub REST API 核对/开启仓库安全设置（零依赖，Node 20+）
 *
 * 为什么存在这份脚本：docs/operations/github-security-settings.md 里的人工清单，
 * 每一项都能用 API 读写；把它固化成脚本后，"到底开了没有"不必再靠登录网页目测，
 * 订阅/可见性不满足时也能拿到平台的原始拒绝理由（而不是"我点过但没生效"）。
 *
 * 用法：
 *   node scripts/github-security-settings.mjs            # 只核对（默认；不写任何设置）
 *   node scripts/github-security-settings.mjs --apply     # 真正写入（需要仓库 Admin 权限）
 *   node scripts/github-security-settings.mjs --apply --enable-default-codeql
 *                                                        # 额外尝试 CodeQL Default setup
 *   node scripts/github-security-settings.mjs --repo owner/name
 *
 * Token 来源：环境变量 GH_TOKEN / GITHUB_TOKEN，否则读本机 `git credential`（github.com）。
 * Token 绝不会被打印；脚本也不会写任何文件。
 *
 * 退出码：0 = 四项设置全部达成（或本来就是期望状态）
 *         2 = 有项目被「仓库可见性 / 订阅」挡住（平台拒绝，不是脚本错误）
 *         1 = 其他失败（网络、权限不足、参数错误）
 *
 * 注意：Default setup 与高级（advanced）workflow 互斥——仓库里存在 CodeQL workflow 时，
 * GitHub 会拒绝启用 Default setup。本仓库的 .github/workflows/codeql.yml 是高级方案，
 * 因此默认不尝试；要改用 Default setup 必须先删掉该 workflow（见 docs/operations/github-security-settings.md §2.3）。
 */

import { spawnSync } from 'node:child_process';
import { existsSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const APPLY = process.argv.includes('--apply');
const ENABLE_DEFAULT_CODEQL = process.argv.includes('--enable-default-codeql');
const API = 'https://api.github.com';

/**
 * 分支保护里要设为「必需」的检查名。
 * 必须与 Actions 页面显示的 check run 名称**逐字相同**（含中文全角括号），
 * 来自 ci.yml / codeql.yml 的 job `name:`，并与 docs/operations/github-security-settings.md §3 表格一致。
 * 观察期 job（e2e-device、visual）刻意不在列表内。
 */
const REQUIRED_CHECKS = [
  'Backend / mvn verify（测试 + 覆盖率 + Checkstyle + SpotBugs）',
  'Backend / 真实 MySQL Flyway 迁移 + Hibernate validate',
  'Frontend / typecheck + lint + format + coverage + build budget + 契约类型',
  'Contract / OpenAPI 快照一致 + 破坏性变更',
  'Docs / 链接与命令引用校验',
  'Security / 依赖漏洞',
  'Security / gitleaks 密钥扫描',
  'E2E / Playwright smoke（后端 H2 + 前端 preview）',
  'Analyze (java-kotlin)',
  'Analyze (javascript-typescript)',
];

const BRANCH = 'main';
const ADVANCED_CODEQL_WORKFLOW = resolve(ROOT, '.github/workflows/codeql.yml');

const results = [];

function sleep(ms) {
  return new Promise((r) => setTimeout(r, ms));
}

function readToken() {
  for (const name of ['GH_TOKEN', 'GITHUB_TOKEN']) {
    const v = process.env[name];
    if (v && v.trim()) return { token: v.trim(), source: `环境变量 ${name}` };
  }
  const r = spawnSync('git', ['credential', 'fill'], {
    cwd: ROOT,
    input: 'protocol=https\nhost=github.com\n\n',
    encoding: 'utf8',
  });
  for (const line of (r.stdout || '').split(/\r?\n/)) {
    if (line.startsWith('password=')) {
      return { token: line.slice('password='.length).trim(), source: 'git credential（github.com）' };
    }
  }
  throw new Error('找不到 GitHub token：请设置 GH_TOKEN，或先让 git 记住 github.com 的凭据');
}

function parseRepoArgs() {
  const i = process.argv.indexOf('--repo');
  if (i !== -1 && process.argv[i + 1]) {
    const [owner, repo] = process.argv[i + 1].split('/');
    if (!owner || !repo) throw new Error(`--repo 需要 owner/name 形式，收到：${process.argv[i + 1]}`);
    return { owner, repo };
  }
  const r = spawnSync('git', ['remote', 'get-url', 'origin'], { cwd: ROOT, encoding: 'utf8' });
  const url = (r.stdout || '').trim();
  const m = url.match(/github\.com[:/]([^/]+)\/(.+?)(?:\.git)?$/);
  if (!m) throw new Error(`无法从 origin 推导 owner/repo（origin=${url || '空'}），请用 --repo owner/name`);
  return { owner: m[1], repo: m[2] };
}

const { token, source: tokenSource } = readToken();
const { owner, repo } = parseRepoArgs();
const SLUG = `/repos/${owner}/${repo}`;

async function api(method, path, body) {
  const headers = {
    Authorization: `bearer ${token}`,
    Accept: 'application/vnd.github+json',
    'X-GitHub-Api-Version': '2022-11-28',
    'User-Agent': 'travel-agent-github-security-settings',
  };
  if (body !== undefined) headers['Content-Type'] = 'application/json';

  let lastErr;
  for (let attempt = 0; attempt < 4; attempt += 1) {
    try {
      const res = await fetch(API + path, {
        method,
        headers,
        body: body === undefined ? undefined : JSON.stringify(body),
      });
      const text = await res.text();
      let json = null;
      try {
        json = text ? JSON.parse(text) : null;
      } catch {
        json = null;
      }
      return { status: res.status, json };
    } catch (err) {
      lastErr = err;
      await sleep(1200 * (attempt + 1)); // 链路偶发 EOF，退避重试
    }
  }
  throw new Error(`${method} ${path} 网络失败：${lastErr?.message}`);
}

function record(item, state, detail) {
  results.push({ item, state, detail });
  const icon = { ok: '✓', blocked: '⛔', missing: '·', error: '✗' }[state] || '?';
  console.log(`${icon} ${item}${detail ? ` — ${detail}` : ''}`);
}

function reasonOf(res) {
  return res.json?.message || `HTTP ${res.status}`;
}

/** 1. Dependency graph */
async function checkDependencyGraph() {
  const res = await api('GET', `${SLUG}/dependency-graph/sbom`);
  if (res.status === 200) return record('Dependency graph', 'ok', '已开启（SBOM 可读）');
  if (!APPLY) return record('Dependency graph', 'missing', reasonOf(res));

  const put = await api('PATCH', SLUG, { security_and_analysis: { dependency_graph: { status: 'enabled' } } });
  if (put.status === 200) return record('Dependency graph', 'ok', '已通过 PATCH 开启');
  return record('Dependency graph', 'error', reasonOf(put));
}

/** 2. Code scanning（advanced workflow 的 SARIF 是否真的落地，或 Default setup 是否已配置） */
async function checkCodeScanning() {
  const hasWorkflow = existsSync(ADVANCED_CODEQL_WORKFLOW);
  const analyses = await api('GET', `${SLUG}/code-scanning/analyses?per_page=5`);
  const setup = await api('GET', `${SLUG}/code-scanning/default-setup`);

  // 真正算"开着"的证据是 analyses 里有一条没有 error 的分析结果（SARIF 已入库），
  // 只看到 workflow 变绿不算：codeql.yml 在未开通时会把 upload 关掉，绿但不上传。
  if (analyses.status === 200 && Array.isArray(analyses.json)) {
    const usable = analyses.json.filter((a) => !a.error);
    if (usable.length > 0) {
      const latest = usable[0];
      return record(
        'Code scanning',
        'ok',
        `${hasWorkflow ? 'advanced workflow' : 'CodeQL'} 结果已入库（最新 ${(latest.created_at || '').slice(0, 10)}，${latest.category || latest.analysis_key}）`,
      );
    }
    if (analyses.json.length > 0) {
      return record('Code scanning', 'error', `有分析记录但都带 error：${(analyses.json[0].error || '').slice(0, 120)}`);
    }
  }
  if (setup.status === 200 && setup.json?.state === 'configured') {
    return record('Code scanning', 'ok', `Default setup 已配置（${(setup.json.languages || []).join(', ')}）`);
  }
  if (setup.status === 403 || setup.status === 404 || analyses.status === 403) {
    const detail = hasWorkflow
      ? `不可用（${reasonOf(setup.status >= 400 ? setup : analyses)}）；仓库已有高级方案 .github/workflows/codeql.yml`
      : `不可用（${reasonOf(setup.status >= 400 ? setup : analyses)}）`;
    if (!APPLY || !ENABLE_DEFAULT_CODEQL || hasWorkflow) return record('Code scanning', 'blocked', detail);
    const put = await api('PUT', `${SLUG}/code-scanning/default-setup`, {
      state: 'configured',
      query_suite: 'default',
      languages: ['java-kotlin', 'javascript-typescript'],
    });
    if (put.status === 200 || put.status === 202) {
      return record('Code scanning', 'ok', 'Default setup 已提交（Java + JavaScript）');
    }
    return record('Code scanning', 'blocked', reasonOf(put));
  }

  return record(
    'Code scanning',
    'missing',
    hasWorkflow
      ? '高级 workflow 存在，但还没有任何分析结果入库：确认 codeql.yml 的 upload 条件（仓库变量 CODEQL_UPLOAD）并等一次 main 运行'
      : '既没有 Default setup，也没有 CodeQL workflow',
  );
}

/** 3. Secret scanning + push protection */
async function checkSecretScanning() {
  const body = {
    security_and_analysis: {
      secret_scanning: { status: 'enabled' },
      secret_scanning_push_protection: { status: 'enabled' },
    },
  };

  if (!APPLY) {
    const repoRes = await api('GET', SLUG);
    const sa = repoRes.json?.security_and_analysis;
    if (sa) {
      const secret = sa.secret_scanning?.status || 'unknown';
      const push = sa.secret_scanning_push_protection?.status || 'unknown';
      const state = secret === 'enabled' && push === 'enabled' ? 'ok' : 'missing';
      return record('Secret scanning / Push protection', state, `secret_scanning=${secret}, push_protection=${push}`);
    }
    const probe = await api('GET', `${SLUG}/secret-scanning/alerts?per_page=1`);
    if (probe.status === 200) return record('Secret scanning / Push protection', 'ok', '已开启（告警接口可读）');
    return record('Secret scanning / Push protection', 'blocked', reasonOf(probe));
  }

  const put = await api('PATCH', SLUG, body);
  if (put.status === 200) {
    const sa = put.json?.security_and_analysis;
    const secret = sa?.secret_scanning?.status || 'unknown';
    const push = sa?.secret_scanning_push_protection?.status || 'unknown';
    if (secret === 'enabled' && push === 'enabled') {
      return record('Secret scanning / Push protection', 'ok', '均已开启');
    }
    return record('Secret scanning / Push protection', 'blocked', `secret_scanning=${secret}, push_protection=${push}`);
  }
  if (put.status === 422 || put.status === 403) {
    return record('Secret scanning / Push protection', 'blocked', reasonOf(put));
  }
  return record('Secret scanning / Push protection', 'error', reasonOf(put));
}

/** 4. main 分支保护（必须 PR + 必需检查 + 禁止 force push / 删除） */
async function checkBranchProtection() {
  const res = await api('GET', `${SLUG}/branches/${BRANCH}/protection`);

  const payload = {
    required_status_checks: { strict: true, contexts: REQUIRED_CHECKS },
    // 必须走 PR；approving review 数量刻意设为 0，个人仓库维持"可自助合并但必须过 PR"。
    // 团队需要审批时把它改成 1（那会同时要求 PR 作者之外的人来点 Approve）。
    required_pull_request_reviews: { required_approving_review_count: 0, dismiss_stale_reviews: true },
    enforce_admins: true,
    required_conversation_resolution: true,
    restrictions: null,
    allow_force_pushes: false,
    allow_deletions: false,
    lock_branch: false,
  };

  if (res.status === 200) {
    const contexts = res.json?.required_status_checks?.contexts || [];
    const missing = REQUIRED_CHECKS.filter((c) => !contexts.includes(c));
    const forcePush = res.json?.allow_force_pushes?.enabled;
    const deletions = res.json?.allow_deletions?.enabled;
    const problems = [];
    if (missing.length) problems.push(`缺少必需检查 ${missing.length} 个`);
    if (forcePush) problems.push('允许 force push');
    if (deletions) problems.push('允许删除分支');
    if (!problems.length) return record(`分支保护 ${BRANCH}`, 'ok', '必需检查齐备，禁止 force push 与删除');
    // 已有保护规则但内容不符（典型：REQUIRED_CHECKS 增项）：--apply 时也要写入。
    // 只报告缺失、不更新，会让清单与线上状态长期不一致（2026-11-25 实际踩到）。
    if (!APPLY) return record(`分支保护 ${BRANCH}`, 'missing', problems.join('；'));
    const put = await api('PUT', `${SLUG}/branches/${BRANCH}/protection`, payload);
    if (put.status === 200) {
      return record(`分支保护 ${BRANCH}`, 'ok', `已更新为 ${REQUIRED_CHECKS.length} 个必需检查`);
    }
    return record(`分支保护 ${BRANCH}`, 'error', reasonOf(put));
  }
  if (res.status !== 404 && res.status !== 403) return record(`分支保护 ${BRANCH}`, 'error', reasonOf(res));

  if (!APPLY) {
    return record(
      `分支保护 ${BRANCH}`,
      res.status === 403 ? 'blocked' : 'missing',
      `${reasonOf(res)}${res.status === 404 ? '（当前没有保护规则）' : ''}`,
    );
  }

  const put = await api('PUT', `${SLUG}/branches/${BRANCH}/protection`, payload);
  if (put.status === 200) return record(`分支保护 ${BRANCH}`, 'ok', `已写入 ${REQUIRED_CHECKS.length} 个必需检查`);
  if (put.status === 403) {
    return record(`分支保护 ${BRANCH}`, 'blocked', `${reasonOf(put)}（需要 GitHub Pro / Team，或把仓库改为 Public）`);
  }
  return record(`分支保护 ${BRANCH}`, 'error', `${reasonOf(put)}`);
}

async function main() {
  console.log(`仓库：${owner}/${repo}　模式：${APPLY ? '写入（--apply）' : '只核对'}　token：${tokenSource}`);
  console.log('');

  const repoRes = await api('GET', SLUG);
  if (repoRes.status !== 200) throw new Error(`读不到仓库 ${owner}/${repo}：${reasonOf(repoRes)}`);
  const admin = repoRes.json.permissions?.admin === true;
  console.log(
    `可见性：${repoRes.json.private ? 'Private' : 'Public'}　Admin 权限：${admin ? '有' : '没有'}　默认分支：${repoRes.json.default_branch}`,
  );
  console.log('');

  if (!admin) throw new Error('当前 token 不是仓库 Admin：这些开关需要 Admin 权限');

  await checkDependencyGraph();
  await checkCodeScanning();
  await checkSecretScanning();
  await checkBranchProtection();

  const ok = results.filter((r) => r.state === 'ok').length;
  const blocked = results.filter((r) => r.state === 'blocked' || r.state === 'missing').length;
  const errors = results.filter((r) => r.state === 'error').length;
  console.log('');
  console.log(`汇总：${ok}/${results.length} 项达成，${blocked} 项未达成，${errors} 项错误`);

  if (blocked > 0) {
    console.log('');
    console.log('被订阅或可见性挡住时可选的解锁路径（二选一，都需要仓库管理员决定）：');
    console.log('  A. 仓库改为 Public：分支保护、Code scanning、Secret scanning + Push protection 全部免费可用；');
    console.log('     但公开不可逆地暴露全部提交历史，改之前必须先把历史扫一遍（gitleaks）。');
    console.log('  B. 升级订阅：GitHub Pro 解锁私有仓库的分支保护；Code scanning / Secret scanning 在私有仓库');
    console.log('     还需要安全类付费 add-on（GitHub Code Security / Secret Protection）。');
  }

  process.exit(errors > 0 ? 1 : blocked > 0 ? 2 : 0);
}

main().catch((err) => {
  console.error(`✗ ${err.message}`);
  process.exit(1);
});