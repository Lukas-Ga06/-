#!/usr/bin/env node
/**
 * 测试 3：第三方服务失败——LLM 402/500/连接拒绝 与 高德 Key 无效。
 *
 * 分部分运行，便于控制依赖状态：
 *   node run-third-party.mjs --part=llm402-real   # a) 真实第三方：:18091（DeepSeek 余额 0 → 402）
 *   node run-third-party.mjs --part=stub-errors   # a) 受控：替身返回 402 / 500（:18100）
 *   node run-third-party.mjs --part=refused       # b) 连接拒绝（替身已停 → 未监听端口）
 *   node run-third-party.mjs --part=amap          # c) 高德：route-legs / 系统状态自报
 * 每次运行都会把结果写进 .evidence/reports/resilience.json 的 thirdParty 段。
 */
import { existsSync, readFileSync } from 'node:fs';
import { join } from 'node:path';
import { ROOT, PORTS, base, httpJson, login, registerTemp, writeJson, stats, countBy, sleep, ACCOUNT, metricValue as mval, logLineCursor, logSince } from './lib.mjs';

const MAIN = PORTS.main;
const LLM402 = PORTS.llm402;
const OWN = PORTS.own;
const STUB = PORTS.stub;
const REPORT = join(ROOT, '.evidence', 'reports', 'resilience.json');
const INSTANCE_LOG = join(ROOT, '.evidence', 'resilience-run', 'instance.log');
const PLAN_MSG = '杭州两日游，两个人，预算2000元';

/* ---------------- 报告读写 ---------------- */
function loadReport() {
  if (!existsSync(REPORT)) return {};
  try {
    return JSON.parse(readFileSync(REPORT, 'utf8'));
  } catch {
    return {};
  }
}
function saveThirdParty(patch) {
  const report = loadReport();
  report.thirdParty = { ...(report.thirdParty || {}), ...patch, updatedAt: new Date().toISOString() };
  report.meta = { ...(report.meta || {}), generatedAt: new Date().toISOString(), node: process.version, host: '127.0.0.1', percentile: 'nearest-rank' };
  writeJson(REPORT, report);
}

/* ---------------- 日志证据（GBK 自动解码 + 行游标） ---------------- */
const logCursor = () => logLineCursor(INSTANCE_LOG);
const warnLinesSince = (cursor) => logSince(INSTANCE_LOG, cursor, /LLM 调用失败|Connection refused|降级为规则模板/);

/* ---------------- actuator 指标 ---------------- */
async function metric(port, token, name, tag) {
  const q = tag ? `?tag=${encodeURIComponent(tag)}` : '';
  const r = await httpJson(`${base(port)}/actuator/metrics/${name}${q}`, { token });
  return r.ok ? r.json : { missing: true, status: r.status };
}

/* ---------------- 替身控制 ---------------- */
async function stubMode(mode, delayMs = 35_000) {
  const r = await httpJson(`${base(STUB)}/__mode`, { method: 'POST', body: { mode, delayMs } });
  if (!r.ok) throw new Error(`stub __mode failed: ${r.status} ${r.text}`);
  return r.json;
}
const stubReset = () => httpJson(`${base(STUB)}/__reset`, { method: 'POST', body: {} });
async function stubStats() {
  const r = await httpJson(`${base(STUB)}/__stats`);
  if (!r.ok) throw new Error(`stub __stats failed: ${r.status}`);
  return r.json;
}

async function chat(port, token, message, timeoutMs = 8 * 60_000) {
  const r = await httpJson(`${base(port)}/api/agent/chat`, { method: 'POST', token, body: { message }, timeoutMs });
  return {
    status: r.status,
    ok: r.ok,
    code: r.json?.code,
    ms: r.ms,
    intent: r.json?.data?.intent,
    resultStatus: r.json?.data?.status,
    planId: r.json?.data?.planId ?? null,
    tripId: r.json?.data?.tripId ?? null,
    hasPlan: Boolean(r.json?.data?.diff || r.json?.data?.planId),
    messageHead: (r.json?.data?.message || '').slice(0, 220),
    error: r.error,
  };
}

/** 规划 → 确认 → 取 tripId（用于 route-legs 校验） */
async function planAndConfirm(port, token) {
  const plan = await chat(port, token, PLAN_MSG, 180_000);
  if (!plan.planId || !plan.ok) return { plan, confirm: null, tripId: null };
  const preview = await httpJson(`${base(port)}/api/agent/plans/${plan.planId}`, { token, timeoutMs: 30_000 });
  const confirmation = preview.json?.data?.confirmation || {};
  const body = { planId: plan.planId, contentHash: confirmation.contentHash, acknowledgeIssues: true };
  let c = await httpJson(`${base(port)}/api/agent/confirm`, { method: 'POST', token, body, timeoutMs: 60_000 });
  if (!c.ok && c.json?.code !== 0) {
    // 不带 contentHash 再试一次（只读接口不可用时也能走通），失败则原样记录
    c = await httpJson(`${base(port)}/api/agent/confirm`, { method: 'POST', token, body: { planId: plan.planId, acknowledgeIssues: true }, timeoutMs: 60_000 });
  }
  return {
    plan,
    contentHash: confirmation.contentHash ?? null,
    confirm: { status: c.status, ok: c.ok, code: c.json?.code, ms: c.ms, message: (c.json?.data?.message || c.json?.message || '').slice(0, 200), tripId: c.json?.data?.tripId ?? null, execution: c.json?.data?.execution ?? null },
    tripId: c.json?.data?.tripId ?? null,
  };
}

/* ---------------- a) 真实 402：:18091 ---------------- */
async function partLlm402Real() {
  const token = await login(LLM402, ACCOUNT.username, ACCOUNT.password);
  const sys = await httpJson(`${base(LLM402)}/api/system/status`, { token });
  const failedBefore = mval(await metric(LLM402, token, 'llm.calls', 'result:failed'));
  const okBefore = mval(await metric(LLM402, token, 'llm.calls', 'result:ok'));
  const lightResp = await chat(LLM402, token, '谢谢', 120_000);
  const planResp = await chat(LLM402, token, PLAN_MSG, 240_000);
  const failedAfter = mval(await metric(LLM402, token, 'llm.calls', 'result:failed'));
  const okAfter = mval(await metric(LLM402, token, 'llm.calls', 'result:ok'));
  return {
    target: `http://127.0.0.1:${LLM402}（真实 DeepSeek Key，余额 0）`,
    expectedThirdPartyError: 'HTTP 402 Insufficient Balance',
    systemStatus: sys.json?.data ?? null,
    llmCallsMetric: {
      failedBefore, failedAfter, failedDelta: failedBefore == null || failedAfter == null ? null : failedAfter - failedBefore,
      okBefore, okAfter, okDelta: okBefore == null || okAfter == null ? null : okAfter - okBefore,
      note: 'llm.calls{result=failed} 由 OpenAiCompatibleLlmClient 在异常时自增，是「真实收到 402」的服务端证据',
    },
    lightChat: lightResp,
    planChat: planResp,
    degradedButFunctional: planResp.ok === true && planResp.intent === 'PLAN_TRIP' && Boolean(planResp.planId),
    verdict: planResp.ok === true && planResp.intent === 'PLAN_TRIP' && Boolean(planResp.planId) && (failedAfter == null || failedBefore == null || failedAfter > failedBefore) ? 'PASS' : 'REVIEW',
  };
}

/* ---------------- a) 受控 402/500：:18100 + 替身 ---------------- */
async function partStubErrors() {
  const user = await registerTemp(OWN, 'tp');
  const token = user.token;
  const cursor0 = logCursor();
  const out = { target: `http://127.0.0.1:${OWN} → stub http://127.0.0.1:${STUB}/v1`, cases: [] };

  for (const [mode, expectErr] of [['http402', '402'], ['http500', '500']]) {
    const failedBefore = mval(await metric(OWN, token, 'llm.calls', 'result:failed'));
    await stubMode(mode);
    await stubReset();
    const cursor = logCursor();
    const resp = await chat(OWN, token, PLAN_MSG, 8 * 60_000);
    const stub = await stubStats();
    const failedAfter = mval(await metric(OWN, token, 'llm.calls', 'result:failed'));
    const warnLines = warnLinesSince(cursor);
    out.cases.push({
      mode,
      expectedThirdPartyError: `HTTP ${expectErr}`,
      response: resp,
      stubHits: stub.total,
      stubAnsweredStatuses: [...new Set(stub.requests.map((r) => r.answeredStatus))],
      llmCallsFailedBefore: failedBefore,
      llmCallsFailedAfter: failedAfter,
      llmCallsFailedDelta: failedBefore == null || failedAfter == null ? null : failedAfter - failedBefore,
      degradedWarnCount: warnLines.length,
      degradedWarnSample: warnLines.slice(0, 4),
      containsExpectedHttpCode: warnLines.some((l) => l.includes(expectErr)),
      degradedButFunctional: resp.ok === true && resp.intent === 'PLAN_TRIP' && Boolean(resp.planId),
      verdict: resp.ok === true && resp.intent === 'PLAN_TRIP' && warnLines.length > 0 ? 'PASS' : 'REVIEW',
    });
    await sleep(500);
  }
  out.allWarnLines = warnLinesSince(cursor0).slice(0, 12);
  out.verdict = out.cases.every((c) => c.verdict === 'PASS') ? 'PASS' : 'REVIEW';
  return out;
}

/* ---------------- b) 连接拒绝：替身已停 ---------------- */
async function partRefused() {
  const probe = await httpJson(`${base(STUB)}/__stats`, { timeoutMs: 3000 });
  const user = await registerTemp(OWN, 'tp');
  const token = user.token;
  const failedBefore = mval(await metric(OWN, token, 'llm.calls', 'result:failed'));
  const cursor = logCursor();
  const resp = await chat(OWN, token, PLAN_MSG, 3 * 60_000);
  const failedAfter = mval(await metric(OWN, token, 'llm.calls', 'result:failed'));
  const warnLines = warnLinesSince(cursor);
  return {
    target: `http://127.0.0.1:${OWN} → LLM_BASE_URL=http://127.0.0.1:${STUB}/v1（替身已停止，端口未监听）`,
    stubReachable: probe.ok,
    stubProbeError: probe.error,
    response: resp,
    llmCallsFailedBefore: failedBefore,
    llmCallsFailedAfter: failedAfter,
    llmCallsFailedDelta: failedBefore == null || failedAfter == null ? null : failedAfter - failedBefore,
    degradedWarnCount: warnLines.length,
    degradedWarnSample: warnLines.slice(0, 6),
    containsConnectionRefused: warnLines.some((l) => /Connection refused/i.test(l)),
    degradedButFunctional: resp.ok === true && resp.intent === 'PLAN_TRIP' && Boolean(resp.planId),
    /** 连接拒绝是「快速失败」，不应像读超时那样拖 30s */
    fastFailMs: resp.ms,
    verdict: resp.ok === true && resp.intent === 'PLAN_TRIP' && warnLines.length > 0 && resp.ms < 60_000 ? 'PASS' : 'REVIEW',
  };
}

/* ---------------- c) 高德 Key ---------------- */
async function partAmap() {
  const port = Number(process.env.AMAP_PORT || MAIN);
  const label = process.env.AMAP_LABEL || '无 Key（未配置 AMAP_KEY）';
  const token = process.env.AMAP_TOKEN || (await registerTemp(port, 'amap')).token;
  const sys = await httpJson(`${base(port)}/api/system/status`, { token, timeoutMs: 30_000 });
  const flow = await planAndConfirm(port, token);
  const legs = flow.tripId
    ? await httpJson(`${base(port)}/api/trips/${flow.tripId}/route-legs`, { token, timeoutMs: 60_000 })
    : { ok: false, status: 0, json: null, text: 'no tripId' };
  const legList = Array.isArray(legs.json?.data) ? legs.json.data : [];
  const sources = countBy(legList, (l) => l.source);
  return {
    target: `http://127.0.0.1:${port}`,
    amapKeyState: label,
    systemStatus: {
      amapConfigured: sys.json?.data?.amapConfigured ?? null,
      geoProvider: sys.json?.data?.geoProvider ?? null,
      geoHint: sys.json?.data?.geoHint ?? null,
      routeProvider: sys.json?.data?.routeProvider ?? null,
      // P2：配置态 / 最近一次真实调用是否成功 / 失败原因（含 infocode），三者必须与 route-legs 口径一致
      routeConfigured: sys.json?.data?.routeConfigured ?? null,
      amapVerified: sys.json?.data?.amapVerified ?? null,
      routeLastError: sys.json?.data?.routeLastError ?? null,
      routeHint: sys.json?.data?.routeHint ?? null,
    },
    planChat: flow.plan,
    confirm: flow.confirm,
    tripId: flow.tripId,
    routeLegs: {
      status: legs.status,
      ok: legs.ok,
      count: legList.length,
      sourceDistribution: sources,
      allEstimated: legList.length > 0 && legList.every((l) => l.source === 'estimated'),
      sample: legList.slice(0, 2),
    },
    /** 「失败自报家门」：状态页是否如实说明路线是估算而非真实 */
    selfReportConsistent: (sys.json?.data?.routeProvider === 'estimated') === (legList.length === 0 || legList.every((l) => l.source === 'estimated')),
    verdict: legList.length > 0 && legList.every((l) => l.source === 'estimated') && sys.json?.data?.routeProvider === 'estimated' ? 'PASS' : 'REVIEW',
  };
}

const part = (process.argv.find((a) => a.startsWith('--part=')) || '--part=stub-errors').slice(7);

const runners = {
  'llm402-real': async () => saveThirdParty({ real402: await partLlm402Real() }),
  'stub-errors': async () => saveThirdParty({ stubErrors: await partStubErrors() }),
  refused: async () => saveThirdParty({ connectionRefused: await partRefused() }),
  amap: async () => {
    // 两种高德状态分开存：无 Key（18090）与「配了无效 Key」（18100）
    const variant = (process.env.AMAP_VARIANT || '').trim();
    const key = variant ? `amap_${variant}` : 'amap';
    saveThirdParty({ [key]: await partAmap() });
  },
};

if (!runners[part]) {
  console.error(`unknown --part=${part}（可选：llm402-real / stub-errors / refused / amap）`);
  process.exit(1);
}
runners[part]()
  .then(() => {
    const tp = loadReport().thirdParty || {};
    const key =
      part === 'amap'
        ? (process.env.AMAP_VARIANT || '').trim()
          ? `amap_${process.env.AMAP_VARIANT.trim()}`
          : 'amap'
        : { 'llm402-real': 'real402', 'stub-errors': 'stubErrors', refused: 'connectionRefused' }[part];
    console.log(JSON.stringify({ part, result: tp[key] }, null, 2).slice(0, 4000));
  })
  .catch((e) => {
    console.error('FAILED', e);
    process.exit(1);
  });