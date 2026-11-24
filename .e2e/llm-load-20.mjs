// LLM 20 连规划压测驱动（P0-B 完成标准：真实模型重复跑 ≥20 次规划，每次都有明确终态）
//
// 用法：
//   node .e2e/llm-load-20.mjs                              # 默认 http://localhost:8081 / 20 次
//   E2E_API_TARGET=http://127.0.0.1:18081 node .e2e/llm-load-20.mjs
//   LOAD_N=1 LOAD_OFFSET=17 node .e2e/llm-load-20.mjs      # 只复跑第 18 条（「从南京出发去扬州镇江」）
//   LOAD_OUT=.evidence/llm-load-20-rerun node ...          # 换证据目录，避免覆盖上一轮
//   node .e2e/llm-load-20.mjs --classify                   # 不联网，只重分类已有 evidence
//
// 前置：目标后端已配置真实 LLM_API_KEY；本脚本不接触任何密钥。
// 证据：$LOAD_OUT（默认 .evidence/llm-load-20，gitignore 内）——每轮原始响应 + summary.json。
//
// 2026-11-24 修正（run-18 假绿）：
//   旧版只按 status 是否属于 {COMPLETED, NEED_CONFIRMATION, FAILED, CANCELLED} 判「有终态」，
//   于是「抱歉，暂时没能为『州镇江的』生成行程」也因 status=COMPLETED 被算成成功（红线=0）。
//   现在把结果分成 planned / asked_back / failed / unknown 四类：
//     - planned    ：真的产出待确认方案（confirmation 非空）或已产生行程（tripId 非空）
//     - asked_back ：槽位不足，明确反问（missingSlots 非空）——合法终态，但不是规划成功
//     - failed     ：失败文案 / FAILED / 非 200 / 空计划（有 planId 但无 confirmation）
//     - unknown    ：其余无法归类的响应
//   红线改为 failed=0 且 unknown=0；asked_back 单独计数，不再混进成功。

import { mkdirSync, readFileSync, readdirSync, writeFileSync } from 'node:fs';

const BASE = process.env.E2E_API_TARGET || 'http://localhost:8081';
const N = Number(process.env.LOAD_N || 20);
const OFFSET = Number(process.env.LOAD_OFFSET || 0);
const USER = process.env.LOAD_USER || 'loadtest20';
const PASS = process.env.LOAD_PASS || 'Loadtest20!';
const OUT_DIR = process.env.LOAD_OUT || '.evidence/llm-load-20';
const CLASSIFY_ONLY = process.argv.includes('--classify');

// 20 条互不相同的真实规划消息（覆盖不同城市对/天数/预算/人群），全部应为 PLAN_TRIP 意图
const MESSAGES = [
  '帮我规划上海出发的杭州周末两日游，两人，预算3000以内',
  '规划一个北京出发去西安的十一五日游，一家三口，预算12000',
  '我在广州，想去成都吃四天，两个人预算6000，帮我做行程',
  '深圳出发去厦门三日游，预算4000，两个人，海边多一点',
  '规划武汉到长沙的两天一夜，学生党，预算1500',
  '帮我安排一个南京出发的苏州无锡两日游，预算2500',
  '从成都出发去重庆的三日游，两个人，想吃火锅和看夜景，预算3500',
  '规划杭州出发去黄山的四日游，两个人预算8000，想爬山',
  '我在天津，想去青岛玩三天，预算3000，两个人',
  '帮我规划西安出发去洛阳开封的两日游，预算2000',
  '上海出发去苏州一日游，预算800，一个人，想看园林',
  '规划北京出发去承德避暑山庄的两日游，一家四口，预算4000',
  '从长沙出发去张家界的五日游，两个人预算9000',
  '我在昆明，想去大理丽江玩五天，预算7000，两个人',
  '规划哈尔滨出发去雪乡的三日游，两个人，预算5000，冬天出行',
  '厦门出发去泉州的两日游，预算1800，两个人，想看人文古迹',
  '帮我规划郑州出发去开封的周末两日游，带小孩，预算2500',
  '从南京出发去扬州镇江的两日游，预算2200，两个人，想吃淮扬菜',
  '规划大连出发去营口鲅鱼圈的两天海边游，预算2000',
  '我在宁波，想去舟山群岛玩三天，预算3500，两个人，海鲜多吃点',
];

// 明显不像城市名的字符：句尾助词 / 动词 / 连词 / 出发动词残留
const SUSPECT_CITY_CHARS = /[的吃玩逛看住待了在和与发去来到想帮请规划安排的着过]/;

const STAMP = new Date().toISOString().replace(/[-:TZ.]/g, '').slice(0, 14);

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

function pad(n) {
  return String(n).padStart(2, '0');
}

async function jsonOrText(res) {
  const text = await res.text();
  try { return JSON.parse(text); } catch { return { _raw: text.slice(0, 500) }; }
}

/** 从回复正文里取计划城市（「已为你规划「成都吃4日游」」→ 成都吃），只用于标脏，不参与判定 */
function planCity(message) {
  const m = String(message || '').match(/「([^」]*?)(?:[0-9一二两三四五六七八九十]+日游|周末游|亲子游)」/);
  return m ? m[1] : null;
}

/**
 * 单轮结果分类。
 * @returns {{outcome:'planned'|'asked_back'|'failed'|'unknown', city:string|null, citySuspect:boolean}}
 */
function classify(entry) {
  const body = entry.raw || {};
  const data = body.data || {};
  const message = String(data.message ?? '');
  const status = String(entry.status ?? data.status ?? 'NULL');
  const missing = Array.isArray(data.missingSlots) ? data.missingSlots : [];
  const httpOk = entry.httpStatus === 200 && (entry.code === 0 || entry.code == null);
  const failureText = /抱歉|没能|暂时无法|生成失败|服务不可用|不支持该目的地/.test(message);
  const city = planCity(message);
  const citySuspect = !!city && SUSPECT_CITY_CHARS.test(city);

  let outcome;
  if (!httpOk || status === 'FAILED' || status === 'CANCELLED' || failureText) {
    outcome = 'failed';
  } else if (data.confirmation != null || data.tripId != null) {
    outcome = 'planned';
  } else if (missing.length > 0) {
    outcome = 'asked_back';
  } else if (data.planId != null) {
    // 有 planId 却没有确认对象/行程：典型「空计划」，旧版正是在这里记成 COMPLETED 成功
    outcome = 'failed';
  } else {
    outcome = 'unknown';
  }
  return { outcome, city, citySuspect };
}

function applyClassification(entry) {
  const verdict = classify(entry);
  entry.outcome = verdict.outcome;
  entry.planCity = verdict.city;
  entry.citySuspect = verdict.citySuspect;
  entry.terminal = ['COMPLETED', 'NEED_CONFIRMATION', 'FAILED', 'CANCELLED'].includes(String(entry.status));
  return entry;
}

function summarize(results, meta = {}) {
  const byStatus = {};
  const byOutcome = {};
  const noTerminal = [];
  const failed = [];
  const unknown = [];
  const askedBack = [];
  const planned = [];
  const citySuspect = [];
  for (const r of results) {
    byStatus[String(r.status ?? 'NULL')] = (byStatus[String(r.status ?? 'NULL')] || 0) + 1;
    byOutcome[String(r.outcome ?? 'unknown')] = (byOutcome[String(r.outcome ?? 'unknown')] || 0) + 1;
    if (!r.terminal) noTerminal.push(r.run);
    if (r.outcome === 'failed') failed.push(r.run);
    else if (r.outcome === 'unknown') unknown.push(r.run);
    else if (r.outcome === 'asked_back') askedBack.push(r.run);
    else if (r.outcome === 'planned') planned.push(r.run);
    if (r.citySuspect) citySuspect.push({ run: r.run, city: r.planCity });
  }
  const times = results.map((r) => r.elapsedMs).sort((a, b) => a - b);
  return {
    ...meta,
    runs: results.length,
    statusDistribution: byStatus,
    outcomeDistribution: byOutcome,
    plannedRuns: planned,
    askedBackRuns: askedBack,
    failedRuns: failed,
    unknownRuns: unknown,
    noTerminalRuns: noTerminal,
    citySuspectRuns: citySuspect,
    elapsedMs: {
      min: times[0],
      median: times[Math.floor(times.length / 2)],
      max: times[times.length - 1],
    },
    rateLimitedRuns: results.filter((r) => r.rateLimited).map((r) => r.run),
    // 红线：既不能有失败，也不能有无法归类的响应
    redLinePassed: failed.length === 0 && unknown.length === 0,
  };
}

// —— 只重分类已有证据：不启动后端、不调用模型 ——
if (CLASSIFY_ONLY) {
  const files = readdirSync(OUT_DIR)
    .filter((f) => /^run-\d+\.json$/.test(f))
    .sort();
  if (files.length === 0) {
    console.error(`没有找到证据文件：${OUT_DIR}/run-*.json`);
    process.exit(1);
  }
  const results = files.map((f) => applyClassification(JSON.parse(readFileSync(`${OUT_DIR}/${f}`, 'utf8'))));
  const summary = summarize(results, { base: 'reclassified', startedAt: 'reclassified', runs: results.length });
  writeFileSync(`${OUT_DIR}/summary-reclassified.json`, JSON.stringify(summary, null, 2));
  console.log('run | http | status | outcome | city | elapsed');
  for (const r of results) {
    console.log(
      `${pad(r.run)} | ${r.httpStatus} | ${String(r.status).padEnd(16)} | ${String(r.outcome).padEnd(10)} | ` +
      `${r.planCity ?? '-'}${r.citySuspect ? ' (脏)' : ''} | ${r.elapsedMs}ms`,
    );
  }
  console.log('\n===== 重分类汇总 =====');
  console.log(`终态分布: ${JSON.stringify(summary.statusDistribution)}`);
  console.log(`结果分布: ${JSON.stringify(summary.outcomeDistribution)}`);
  console.log(`失败轮次: ${summary.failedRuns.length ? summary.failedRuns.join(',') : '无'}`);
  console.log(`无法归类: ${summary.unknownRuns.length ? summary.unknownRuns.join(',') : '无'}`);
  console.log(`反问轮次（合法终态，非成功）: ${summary.askedBackRuns.length ? summary.askedBackRuns.join(',') : '无'}`);
  console.log(`脏城市名轮次: ${summary.citySuspectRuns.length ? summary.citySuspectRuns.map((x) => `#${x.run}=${x.city}`).join(', ') : '无'}`);
  console.log(`红线（failed=0 且 unknown=0）: ${summary.redLinePassed ? 'PASS' : 'FAIL'}`);
  console.log(`重分类证据: ${OUT_DIR}/summary-reclassified.json`);
  process.exit(summary.redLinePassed ? 0 : 1);
}

mkdirSync(OUT_DIR, { recursive: true });

// —— 登录：注册（已存在则忽略）→ 拿 Cookie ——
let cookie = '';
{
  const reg = await fetch(`${BASE}/api/auth/register`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ username: USER, password: PASS, nickname: '负载验收' }),
  });
  const regBody = await jsonOrText(reg);
  const regOk = reg.ok || /exist|已|duplicate/i.test(JSON.stringify(regBody));
  if (!regOk) {
    console.error(`注册失败 http=${reg.status}:`, JSON.stringify(regBody).slice(0, 200));
    process.exit(1);
  }
  const login = await fetch(`${BASE}/api/auth/login`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ username: USER, password: PASS }),
  });
  const setCookie = login.headers.getSetCookie?.() ?? [login.headers.get('set-cookie')].filter(Boolean);
  cookie = setCookie.map((c) => c.split(';')[0]).join('; ');
  if (!login.ok || !cookie) {
    console.error(`登录失败 http=${login.status}（cookie=${cookie ? '有' : '无'}）`);
    process.exit(1);
  }
  console.log(`登录成功：${USER} @ ${BASE}`);
}

// —— 20 连规划：串行执行（避免叠加高德 QPS 压力；限流桶 agent=30/300s 也按此设计）——
const results = [];
for (let i = 0; i < N; i++) {
  const runNo = OFFSET + i + 1;
  const message = MESSAGES[(runNo - 1) % MESSAGES.length];
  const conversationId = `load20-${STAMP}-${pad(runNo)}`;
  const t0 = Date.now();
  let entry = { run: runNo, conversationId, message };

  let attempt = 0, res, body;
  while (true) {
    attempt++;
    res = await fetch(`${BASE}/api/agent/chat`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', Cookie: cookie },
      body: JSON.stringify({ message, conversationId, requestId: `${conversationId}-a${attempt}` }),
    });
    body = await jsonOrText(res);
    if (res.status === 429 && attempt <= 3) {
      // 命中限流桶：等窗口再试，并如实记录
      entry.rateLimited = true;
      await sleep(65_000);
      continue;
    }
    break;
  }

  entry.httpStatus = res.status;
  entry.attempts = attempt;
  entry.elapsedMs = Date.now() - t0;
  entry.code = body?.code;
  entry.intent = body?.intent ?? body?.data?.intent ?? null;
  entry.status = body?.status ?? body?.data?.status ?? null;
  entry.planId = body?.planId ?? body?.data?.planId ?? null;
  entry.error = body?.error ?? (res.ok ? null : `HTTP ${res.status}`);
  entry.messageText = body?.data?.message ?? null;
  entry.raw = body;
  applyClassification(entry);
  results.push(entry);
  writeFileSync(`${OUT_DIR}/run-${pad(runNo)}.json`, JSON.stringify(entry, null, 2));

  const flag = entry.outcome === 'planned' ? 'OK ' : entry.outcome === 'asked_back' ? '?? ' : '!! ';
  console.log(
    `${flag}#${pad(runNo)} ${String(entry.elapsedMs).padStart(6)}ms ` +
    `http=${entry.httpStatus} status=${entry.status} outcome=${entry.outcome}` +
    (entry.planCity ? ` city=${entry.planCity}${entry.citySuspect ? '(脏)' : ''}` : '') +
    (entry.rateLimited ? ' (429重试)' : '') +
    (entry.messageText ? ` ::${String(entry.messageText).replace(/\n/g, ' ').slice(0, 60)}` : ''),
  );
  await sleep(2000); // 轻微间隔，稳定压在限流桶内
}

// —— 汇总 ——
const summary = summarize(results, { base: BASE, startedAt: STAMP });
writeFileSync(`${OUT_DIR}/summary.json`, JSON.stringify(summary, null, 2));

console.log('\n===== 汇总 =====');
console.log(`终态分布: ${JSON.stringify(summary.statusDistribution)}`);
console.log(`结果分布: ${JSON.stringify(summary.outcomeDistribution)}`);
console.log(`失败轮次: ${summary.failedRuns.length ? summary.failedRuns.join(',') : '无'}`);
console.log(`无法归类: ${summary.unknownRuns.length ? summary.unknownRuns.join(',') : '无'}`);
console.log(`反问轮次（合法终态，非成功）: ${summary.askedBackRuns.length ? summary.askedBackRuns.join(',') : '无'}`);
console.log(`无明确终态的轮次: ${summary.noTerminalRuns.length ? summary.noTerminalRuns.join(',') : '无'}  ← P0-B 红线：必须为 0`);
console.log(`脏城市名轮次: ${summary.citySuspectRuns.length ? summary.citySuspectRuns.map((x) => `#${x.run}=${x.city}`).join(', ') : '无'}`);
console.log(`耗时 ms: min=${summary.elapsedMs.min} median=${summary.elapsedMs.median} max=${summary.elapsedMs.max}`);
console.log(`红线（failed=0 且 unknown=0）: ${summary.redLinePassed ? 'PASS' : 'FAIL'}`);
console.log(`证据目录: ${OUT_DIR}/`);
process.exit(summary.redLinePassed ? 0 : 1);