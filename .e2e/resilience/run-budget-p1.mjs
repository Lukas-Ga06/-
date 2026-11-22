#!/usr/bin/env node
/**
 * P1 端到端时间预算：实测耗时 + 降级可观测性。
 *
 * 旧基线（docs/review/2026-11-19-resilience.md §4）：慢 LLM（35s/次 > 30s 读超时）下
 * 一次规划串行 5 次 LLM 调用，端到端 **128.13s**，无总预算/熔断。
 * 本轮后端已合并 P1（travel.agent.budget.total-ms 默认 30000）：
 *
 * 用法（实例 18100 + 替身 18110 均已在后台作业中）：先切替身为慢模式，再跑本脚本。
 *   curl -XPOST 127.0.0.1:18110/__mode -d '{"mode":"slow","delayMs":35000}'
 *   node .e2e/resilience/run-budget-p1.mjs
 *
 * 断言：
 *   P1-1 慢规划端到端 ≤ 预算 30s + 5s 余量（收敛，而不是旧 128s）
 *   P1-2 相对旧基线 128.13s 至少省 60s
 *   P1-3 run 追踪里存在 taskType=DEGRADE 任务（含降级阶段与剩余预算）
 *   P1-4 SSE 收到 budget_degraded 事件
 *   P1-5 回复正文/warnings 如实说明跳过了哪些阶段 + 「未经完整校验」
 *   P1-6 降级后仍产出 planId（功能降级而非失败）
 *   P1-7 文案回显的预算值 = 配置的 30.0s
 *
 * 结果写入 .evidence/reports/resilience-postfix.json 的 p1Budget 段。
 */
import { existsSync, readFileSync } from 'node:fs';
import { join } from 'node:path';
import { ROOT, base, httpJson, registerTemp, writeJson, sleep } from './lib.mjs';

const POSTFIX = join(ROOT, '.evidence', 'reports', 'resilience-postfix.json');
const OWN = 18100;
const STUB = 18110;
const PLAN_MSG = '杭州两日游，两个人，预算2000元';
/** 旧基线硬数字：来自 .evidence/reports/resilience-prefix-baseline.json → slowLlm.slowPlan.response.ms */
const BASELINE = join(ROOT, '.evidence', 'reports', 'resilience-prefix-baseline.json');
const OLD_BASELINE_MS = (() => {
  try {
    return JSON.parse(readFileSync(BASELINE, 'utf8'))?.slowLlm?.slowPlan?.response?.ms ?? 128_125.2;
  } catch {
    return 128_125.2;
  }
})();

function loadJson(path) {
  if (!existsSync(path)) return null;
  try {
    return JSON.parse(readFileSync(path, 'utf8'));
  } catch {
    return null;
  }
}
function saveSection(section, data) {
  const report = loadJson(POSTFIX) || {};
  report[section] = data;
  report.meta = { ...(report.meta || {}), generatedAt: new Date().toISOString(), node: process.version };
  writeJson(POSTFIX, report);
}

async function stubMode(mode, delayMs) {
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

/** 降级阶段：从「做了降级：X、Y、Z。本次结果未经完整校验」里取，保留原文 */
function parseStages(text) {
  if (typeof text !== 'string') return [];
  const m = /做了降级：(.+?)。本次结果未经完整校验/.exec(text);
  return m ? m[1].split('、').map((s) => s.trim()).filter(Boolean) : [];
}

async function syncCase(token) {
  await stubMode('slow', 35_000);
  await stubReset();
  const startedAt = new Date().toISOString();
  const r = await httpJson(`${base(OWN)}/api/agent/chat`, {
    method: 'POST',
    token,
    body: { message: PLAN_MSG },
    timeoutMs: 180_000,
  });
  const message = r.json?.data?.message || '';
  const warnings = r.json?.data?.warnings || [];
  const planId = r.json?.data?.planId ?? null;

  // run 追踪：最新一条同消息的 run
  await sleep(400);
  const runsResp = await httpJson(`${base(OWN)}/api/agent/runs?limit=10`, { token });
  const runs = Array.isArray(runsResp.json?.data) ? runsResp.json.data : [];
  const run = runs.find((x) => x.message === PLAN_MSG) || runs[0] || null;
  const detail = run ? await httpJson(`${base(OWN)}/api/agent/runs/${run.runId}`, { token }) : null;
  const tasks = detail?.json?.data?.tasks || [];
  const degradeTasks = tasks.filter((t) => t.taskType === 'DEGRADE');

  const budgetEcho = /时间预算（([\d.]+)s）已用尽/.exec(message);
  const stub = await stubStats();

  return {
    startedAt,
    request: {
      status: r.status,
      ok: r.ok,
      code: r.json?.code,
      ms: r.ms,
      intent: r.json?.data?.intent,
      resultStatus: r.json?.data?.status,
      planId,
      error: r.error,
    },
    message,
    messageLen: message.length,
    warnings,
    budgetEchoSeconds: budgetEcho ? Number(budgetEcho[1]) : null,
    degradedStages: parseStages(message),
    run: run ? { runId: run.runId, status: run.status, durationMs: run.durationMs } : null,
    tasks: tasks.map((t) => ({ taskType: t.taskType, receiver: t.receiver, status: t.status, summary: t.summary })),
    degradeTasks: degradeTasks.map((t) => ({ taskType: t.taskType, receiver: t.receiver, status: t.status, summary: t.summary })),
    stub: { total: stub.total, aborted: stub.requests.filter((x) => x.clientAborted).length, requests: stub.requests },
  };
}

/** 读 SSE：收集事件名与 budget_degraded 的 payload */
async function sseCase(token) {
  await stubMode('slow', 35_000);
  await stubReset();
  const started = performance.now();
  const ctrl = new AbortController();
  const timer = setTimeout(() => ctrl.abort(new Error('sse-client-timeout-95s')), 95_000);
  const out = { status: 0, ok: false, ms: null, ttfbMs: null, events: [], budgetDegraded: [], error: null, closed: false };
  try {
    const res = await fetch(`${base(OWN)}/api/agent/stream?message=${encodeURIComponent(PLAN_MSG)}`, {
      headers: { Authorization: `Bearer ${token}`, Accept: 'text/event-stream' },
      signal: ctrl.signal,
    });
    out.status = res.status;
    out.ok = res.ok;
    const reader = res.body.getReader();
    const dec = new TextDecoder();
    let buf = '';
    for (;;) {
      const { done, value } = await reader.read();
      if (done) {
        out.closed = true;
        break;
      }
      if (out.ttfbMs == null) out.ttfbMs = Math.round((performance.now() - started) * 10) / 10;
      buf += dec.decode(value, { stream: true });
      let idx;
      while ((idx = buf.indexOf('\n\n')) >= 0) {
        const chunk = buf.slice(0, idx);
        buf = buf.slice(idx + 2);
        const name = /(^|\n)event:([^\n]*)/.exec(chunk)?.[2]?.trim() || 'message';
        const data = /(^|\n)data:([^\n]*)/.exec(chunk)?.[2]?.trim() || '';
        const ev = { name, data: data.slice(0, 600) };
        out.events.push(ev);
        if (name === 'budget_degraded') out.budgetDegraded.push({ data: ev.data });
      }
    }
    out.ms = Math.round((performance.now() - started) * 10) / 10;
  } catch (e) {
    out.error = `${e.name}: ${e.message}`;
    out.ms = Math.round((performance.now() - started) * 10) / 10;
  } finally {
    clearTimeout(timer);
  }
  out.eventNames = [...new Set(out.events.map((e) => e.name))];
  out.stub = await stubStats();
  return out;
}

async function main() {
  const user = await registerTemp(OWN, 'p1budget');
  const token = user.token;

  const sync = await syncCase(token);
  const sse = await sseCase(token);

  const savedMs = Math.round((OLD_BASELINE_MS - sync.request.ms) * 10) / 10;
  const savedPct = Math.round((savedMs / OLD_BASELINE_MS) * 1000) / 10;
  const budgetSeconds = sync.budgetEchoSeconds;
  const noticeInWarnings = sync.warnings.some((w) => typeof w === 'string' && w.includes('时间预算') && w.includes('未经完整校验'));

  const checks = [
    {
      id: 'P1-1 端到端收敛到预算量级',
      expected: '≤ 35000ms（预算 30s + 5s 余量）',
      actual: sync.request.ms,
      pass: sync.request.ms <= 35_000,
    },
    {
      id: 'P1-2 相对旧基线 128.13s 省 ≥60s',
      expected: `≥ 60000ms（旧 ${OLD_BASELINE_MS}ms）`,
      actual: { oldMs: OLD_BASELINE_MS, newMs: sync.request.ms, savedMs, savedPct },
      pass: savedMs >= 60_000,
    },
    {
      id: 'P1-3 run 追踪含 DEGRADE 任务',
      expected: 'taskType=DEGRADE 且 summary 写明阶段与剩余预算',
      actual: sync.degradeTasks,
      pass: sync.degradeTasks.length > 0 && sync.degradeTasks.every((t) => typeof t.summary === 'string'),
    },
    {
      id: 'P1-4 SSE 收到 budget_degraded 事件',
      expected: 'events 含 budget_degraded（含 stage / remainingMs）',
      actual: sse.budgetDegraded,
      pass: sse.budgetDegraded.length > 0 && sse.budgetDegraded.every((b) => b.data.includes('stage') && b.data.includes('remainingMs')),
    },
    {
      id: 'P1-5 文案如实说明降级且未经完整校验',
      expected: 'message 含「时间预算…已用尽」「做了降级」「未经完整校验」与具体阶段',
      actual: { stages: sync.degradedStages, noticeInWarnings },
      pass:
        sync.message.includes('时间预算') &&
        sync.message.includes('做了降级') &&
        sync.message.includes('未经完整校验') &&
        sync.degradedStages.length > 0 &&
        noticeInWarnings,
    },
    {
      id: 'P1-6 降级后仍产出方案（功能降级而非失败）',
      expected: 'planId 非空且 intent=PLAN_TRIP',
      actual: { planId: sync.request.planId, intent: sync.request.intent, messageLen: sync.messageLen },
      pass: Boolean(sync.request.planId) && sync.request.intent === 'PLAN_TRIP',
    },
    {
      id: 'P1-7 文案回显预算 = 配置的 30.0s',
      expected: 30,
      actual: budgetSeconds,
      pass: budgetSeconds === 30,
    },
  ];

  const result = {
    name: 'p1-budget',
    startedAt: new Date().toISOString(),
    finishedAt: new Date().toISOString(),
    topology: {
      instance: `http://127.0.0.1:${OWN}（LLM on，read-timeout=30000，budget.total-ms 默认 30000）`,
      llmStub: `http://127.0.0.1:${STUB}/v1（slow 35000ms > 读超时）`,
      message: PLAN_MSG,
    },
    oldBaseline: {
      source: '.evidence/reports/resilience-prefix-baseline.json → slowLlm.slowPlan.response.ms',
      ms: OLD_BASELINE_MS,
      llmCalls: 5,
      note: '旧口径：4×30s 读超时 + 1×8s 润色超时，无总预算/熔断（docs/review/2026-11-19-resilience.md §4）',
    },
    sync,
    sse,
    savings: {
      oldMs: OLD_BASELINE_MS,
      newMs: sync.request.ms,
      savedMs,
      savedPct,
      oldLlmCalls: 5,
      newLlmCalls: sync.stub.total,
    },
    functionalCost: {
      degradedStages: sync.degradedStages,
      elapsedMs: sync.request.ms,
      stillProducedPlan: Boolean(sync.request.planId),
      /** 降级换来的时间 ÷ 损失的阶段数，给出直观口径 */
      msSavedPerStage: sync.degradedStages.length ? Math.round(savedMs / sync.degradedStages.length) : null,
    },
    checks,
    verdict: checks.every((c) => c.pass) ? 'PASS' : 'FAIL',
  };
  saveSection('p1Budget', result);

  console.log(
    JSON.stringify(
      {
        verdict: result.verdict,
        syncMs: sync.request.ms,
        oldMs: OLD_BASELINE_MS,
        savedMs,
        savedPct,
        syncPlanId: sync.request.planId,
        budgetEchoSeconds: budgetSeconds,
        degradedStages: sync.degradedStages,
        messageLen: sync.messageLen,
        degradeTasks: sync.degradeTasks,
        sse: { status: sse.status, ms: sse.ms, closed: sse.closed, eventNames: sse.eventNames, budgetDegraded: sse.budgetDegraded },
        stubHits: { sync: sync.stub.total, syncAborted: sync.stub.aborted, sse: sse.stub.total },
        checks: checks.map((c) => ({ id: c.id, pass: c.pass, actual: c.actual })),
      },
      null,
      2,
    ),
  );
  if (result.verdict !== 'PASS') process.exitCode = 2;
}

main().catch((e) => {
  console.error('FAILED', e);
  process.exit(1);
});