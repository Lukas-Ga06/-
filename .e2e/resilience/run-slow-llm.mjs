#!/usr/bin/env node
/**
 * 测试 2：慢接口（慢 LLM）——后端超时与降级。
 *
 * 拓扑：自建实例 :18100（LLM_ENABLED=true, LLM_READ_TIMEOUT_MS=30000）
 *       → LLM_BASE_URL=http://127.0.0.1:18110/v1（本测试的 OpenAI 兼容替身）
 * 替身按 mode 应答：ok（立即 200 + 内容 "{}"）/ slow（睡眠 N ms，N 可 > 读超时）。
 *
 * 判定点：
 *   1) 每个 LLM 调用被读超时截断在 30s，请求最终仍以 200 返回并降级为规则路径；
 *   2) 传输层证据：替身记录到「客户端先断开」（后端确实执行了读超时）；
 *   3) 降级告警落在后端日志；
 *   4) 不挂死线程：慢请求期间其它接口仍快速响应；依赖恢复后连发 5 个请求全部正常；
 *   5) 记录端到端耗时与串行 LLM 阶段数的关系（正常 vs 慢依赖对比）。
 *
 * 用法：node run-slow-llm.mjs
 */
import { existsSync, readFileSync } from 'node:fs';
import { join } from 'node:path';
import { ROOT, PORTS, base, httpJson, registerTemp, stats, sleep, writeJson, logLineCursor, logSince, metricValue as mval } from './lib.mjs';

const OWN = PORTS.own;
const STUB = PORTS.stub;
const STUB_DELAY_MS = 35_000;
const WITHIN_TIMEOUT_DELAY_MS = 20_000;
const READ_TIMEOUT_MS = 30_000;
const REPORT = join(ROOT, '.evidence', 'reports', 'resilience.json');
const INSTANCE_LOG = join(ROOT, '.evidence', 'resilience-run', 'instance.log');
const PLAN_MSG = '杭州两日游，两个人，预算2000元';
/** 规则路径落到 CHITCHAT：一次对话只产生 1 个 LLM 调用，是「单调用超时」的干净样本 */
const LIGHT_MSG = '谢谢';
/** 降级相关日志关键字（日志文件是 GBK，由 lib 自动解码） */
const LOG_PATTERN = /LLM 调用失败|SocketTimeout|Connection refused|降级为规则模板|降级估算/;

async function stubMode(mode, delayMs = STUB_DELAY_MS) {
  const r = await httpJson(`${base(STUB)}/__mode`, { method: 'POST', body: { mode, delayMs } });
  if (!r.ok) throw new Error(`stub __mode failed: ${r.status} ${r.text}`);
  return r.json;
}
async function stubReset() {
  await httpJson(`${base(STUB)}/__reset`, { method: 'POST', body: {} });
}
async function stubStats() {
  const r = await httpJson(`${base(STUB)}/__stats`);
  if (!r.ok) throw new Error(`stub __stats failed: ${r.status}`);
  return r.json;
}

const degradedLogLines = (cursor) => logSince(INSTANCE_LOG, cursor, LOG_PATTERN);

async function metric(token, name, tag) {
  const q = tag ? `?tag=${encodeURIComponent(tag)}` : '';
  const r = await httpJson(`${base(OWN)}/actuator/metrics/${name}${q}`, { token });
  return r.ok ? r.json : { missing: true, status: r.status };
}

async function snapshotRuntime(token) {
  const [live, peak, active, mem, pending, llmFailed, llmOk] = await Promise.all([
    metric(token, 'jvm.threads.live'),
    metric(token, 'jvm.threads.peak'),
    metric(token, 'http.server.requests.active'),
    metric(token, 'jvm.memory.used'),
    metric(token, 'hikaricp.connections.pending'),
    metric(token, 'llm.calls', 'result:failed'),
    metric(token, 'llm.calls', 'result:ok'),
  ]);
  return {
    jvmThreadsLive: mval(live),
    jvmThreadsPeak: mval(peak),
    httpActive: mval(active),
    jvmMemoryUsedBytes: mval(mem),
    hikariPending: mval(pending),
    llmCallsFailed: mval(llmFailed),
    llmCallsOk: mval(llmOk),
  };
}

async function chat(token, message, timeoutMs) {
  const r = await httpJson(`${base(OWN)}/api/agent/chat`, { method: 'POST', token, body: { message }, timeoutMs });
  return {
    status: r.status,
    ok: r.ok,
    code: r.json?.code,
    ms: r.ms,
    intent: r.json?.data?.intent,
    resultStatus: r.json?.data?.status,
    planId: r.json?.data?.planId ?? null,
    hasPlan: Boolean(r.json?.data?.diff || r.json?.data?.planId),
    messageLen: (r.json?.data?.message || '').length,
    messageHead: (r.json?.data?.message || '').slice(0, 180),
    /** P1：完整文案与 warnings 用于判断「预算降级是否如实告知」 */
    messageFull: r.json?.data?.message || '',
    warnings: r.json?.data?.warnings || [],
    error: r.error,
  };
}

async function saveSection(section, data) {
  let report = {};
  if (existsSync(REPORT)) {
    try {
      report = JSON.parse(readFileSync(REPORT, 'utf8'));
    } catch {
      report = {};
    }
  }
  report[section] = data;
  report.meta = {
    ...(report.meta || {}),
    generatedAt: new Date().toISOString(),
    node: process.version,
    host: '127.0.0.1',
    percentile: 'nearest-rank',
  };
  writeJson(REPORT, report);
}

async function main() {
  const startedAt = new Date().toISOString();
  const user = await registerTemp(OWN, 'slowllm');
  const token = user.token;
  const statusResp = await httpJson(`${base(OWN)}/api/system/status`, { token });
  const runtimeBefore = await snapshotRuntime(token);

  const result = {
    name: 'slow-llm',
    startedAt,
    topology: {
      instance: `http://127.0.0.1:${OWN}（独立后端实例 + 独立 H2 文件库）`,
      llmStub: `http://127.0.0.1:${STUB}/v1（OpenAI 兼容替身，只替换模型端 HTTP 应答）`,
      llmReadTimeoutMs: READ_TIMEOUT_MS,
      stubSlowDelayMs: STUB_DELAY_MS,
      stubWithinTimeoutDelayMs: WITHIN_TIMEOUT_DELAY_MS,
    },
    systemStatus: statusResp.json?.data ?? null,
    runtimeBefore,
    baselineLight: null,
    baselinePlan: null,
    slowWithinTimeout: null,
    slowLight: null,
    slowPlan: null,
    recovery: null,
    logEvidence: [],
    verdict: {},
  };

  // ---------- A. 正常依赖（对照） ----------
  await stubMode('ok');
  await stubReset();
  const baseLightSamples = [];
  for (let i = 0; i < 3; i++) {
    baseLightSamples.push(await chat(token, LIGHT_MSG, 60_000));
    await sleep(200);
  }
  result.baselineLight = {
    mode: 'ok（替身立即 200，内容 "{}"）',
    message: LIGHT_MSG,
    requests: baseLightSamples,
    latency: stats(baseLightSamples.map((s) => s.ms)),
    stubHits: (await stubStats()).total,
  };

  await stubReset();
  const basePlanSamples = [];
  for (let i = 0; i < 2; i++) {
    basePlanSamples.push(await chat(token, PLAN_MSG, 120_000));
    await sleep(200);
  }
  result.baselinePlan = {
    mode: 'ok',
    message: PLAN_MSG,
    requests: basePlanSamples,
    latency: stats(basePlanSamples.map((s) => s.ms)),
    stubHitsPerRequest: (await stubStats()).total / Math.max(1, basePlanSamples.length),
  };

  // ---------- B. 慢但未超时（20s < 30s 读超时）：应正常返回，不误判失败 ----------
  await stubMode('slow', WITHIN_TIMEOUT_DELAY_MS);
  await stubReset();
  const withinCursor = logLineCursor();
  const withinResp = await chat(token, LIGHT_MSG, 120_000);
  const withinStub = await stubStats();
  result.slowWithinTimeout = {
    mode: `slow(${WITHIN_TIMEOUT_DELAY_MS}ms < readTimeout ${READ_TIMEOUT_MS}ms)`,
    message: LIGHT_MSG,
    response: withinResp,
    stubHits: withinStub.total,
    stubAborted: withinStub.requests.filter((r) => r.clientAborted).length,
    latencyOverheadVsBaselineMs: withinResp.ms - (result.baselineLight.latency.p50 ?? 0),
    degradedLog: degradedLogLines(withinCursor),
  };

  // ---------- C. 慢依赖：单次 LLM 调用超过读超时（35s > 30s） ----------
  await stubMode('slow', STUB_DELAY_MS);
  await stubReset();
  const slowLightStartIso = new Date().toISOString();
  const slowLightCursor = logLineCursor();
  const probes = [];
  let probing = true;
  const probeTask = (async () => {
    while (probing) {
      await sleep(5000);
      const [health, runs, status] = await Promise.all([
        httpJson(`${base(OWN)}/actuator/health`, { timeoutMs: 10_000 }),
        httpJson(`${base(OWN)}/api/agent/runs?limit=5`, { token, timeoutMs: 10_000 }),
        httpJson(`${base(OWN)}/api/system/status`, { token, timeoutMs: 10_000 }),
      ]);
      probes.push({
        atMs: Date.now() - Date.parse(slowLightStartIso),
        healthMs: health.ms,
        health: health.json?.status ?? health.status,
        runsMs: runs.ms,
        runsStatus: runs.status,
        statusMs: status.ms,
        statusCode: status.status,
      });
    }
  })();
  const slowLightResp = await chat(token, LIGHT_MSG, 5 * 60_000);
  probing = false;
  await probeTask;
  const slowLightStub = await stubStats();
  const runtimeAfterLight = await snapshotRuntime(token);
  result.slowLight = {
    mode: `slow(${STUB_DELAY_MS}ms > readTimeout ${READ_TIMEOUT_MS}ms)`,
    message: LIGHT_MSG,
    response: slowLightResp,
    withinReadTimeoutPlusMargin: slowLightResp.ms <= READ_TIMEOUT_MS + 5000,
    overrunMs: Math.round(slowLightResp.ms - READ_TIMEOUT_MS),
    stub: {
      total: slowLightStub.total,
      aborted: slowLightStub.requests.filter((r) => r.clientAborted).length,
      detail: slowLightStub.requests,
    },
    probesWhileSlow: probes,
    otherEndpointsStayedFast:
      probes.length > 0 && probes.every((p) => p.healthMs < 2000 && p.runsMs < 2000 && p.statusMs < 2000),
    runtimeAfterSlow: runtimeAfterLight,
    degradedLog: degradedLogLines(slowLightCursor).slice(0, 8),
  };

  // ---------- D. 慢依赖：完整规划（多阶段串行 LLM） ----------
  await stubReset();
  const slowPlanCursor = logLineCursor();
  const slowPlanResp = await chat(token, PLAN_MSG, 8 * 60_000);
  const slowPlanStub = await stubStats();
  const runtimeAfterPlan = await snapshotRuntime(token);
  /** P1：预算生效时回复文案会回显「时间预算（30.0s）已用尽 … 未经完整校验」 */
  const budgetEcho = /时间预算（([\d.]+)s）已用尽/.exec(slowPlanResp.messageFull || '');
  const degradedStages = (/做了降级：(.+?)。本次结果未经完整校验/.exec(slowPlanResp.messageFull || '')?.[1] || '')
    .split('、')
    .map((x) => x.trim())
    .filter(Boolean);
  result.slowPlan = {
    mode: `slow(${STUB_DELAY_MS}ms)`,
    message: PLAN_MSG,
    response: slowPlanResp,
    llmCallsHit: slowPlanStub.total,
    stubAborted: slowPlanStub.requests.filter((r) => r.clientAborted).length,
    stubDetail: slowPlanStub.requests,
    perCallTimeoutMs: READ_TIMEOUT_MS,
    /** 旧口径对照：无总预算时理论耗时 = 读超时 × 调用数（旧基线 5 次 = 150s）。预算开启后仅作历史对照。 */
    theoreticalBudgetMsWithoutCap: READ_TIMEOUT_MS * slowPlanStub.total,
    /** P1 实测：预算是否把端到端收敛到 30s 量级、降级是否在文案里如实告知 */
    budget: {
      configuredMsHint: budgetEcho ? Number(budgetEcho[1]) * 1000 : null,
      degradedNoticePresent: Boolean(budgetEcho),
      degradedStages,
      llmCallsHit: slowPlanStub.total,
      oldBaselineMs: 128_125.2,
      convergedToBudget: slowPlanResp.ms <= 35_000,
      savedMs: Math.round((128_125.2 - slowPlanResp.ms) * 10) / 10,
    },
    runtimeAfterSlow: runtimeAfterPlan,
    degradedLog: degradedLogLines(slowPlanCursor).slice(0, 10),
    /**
     * 日志游标注意：Boot 的 file appender 有缓冲，上一次请求（402/500 用例）的 WARN 可能在本轮
     * 游标之后才落盘，因此 degradedLog 里可能混入旧行。超时/降级的主证据以 response、stub 传输记录
     * 与 run 追踪的 DEGRADE 任务为准（见 run-budget-p1.mjs）。
     */
    degradedLogNote: 'file appender 有缓冲，日志行序可能滞后于请求边界；本轮 P1 主证据为 p1Budget 段',
  };

  // ---------- E. 依赖恢复：验证没有挂死的线程/连接 ----------
  await stubMode('ok');
  await stubReset();
  const recoverySamples = [];
  for (let i = 0; i < 5; i++) {
    recoverySamples.push(await chat(token, LIGHT_MSG, 60_000));
    await sleep(200);
  }
  const recoveryStub = await stubStats();
  const runtimeAfterRecovery = await snapshotRuntime(token);
  result.recovery = {
    requests: recoverySamples,
    latency: stats(recoverySamples.map((s) => s.ms)),
    stubHits: recoveryStub.total,
    allOk: recoverySamples.every((s) => s.ok && s.code === 0),
    runtimeAfterRecovery,
    threadDeltaVsBefore: (runtimeAfterRecovery.jvmThreadsLive ?? 0) - (runtimeBefore.jvmThreadsLive ?? 0),
  };

  result.logEvidence = [...result.slowLight.degradedLog, ...result.slowPlan.degradedLog].slice(0, 12);
  result.finishedAt = new Date().toISOString();
  result.verdict = {
    perCallTimeoutEnforced: slowLightStub.requests.every((r) => r.clientAborted) && slowLightResp.ms <= READ_TIMEOUT_MS + 5000,
    transportTimeoutProven: slowLightStub.requests.some((r) => r.clientAborted === true),
    degradedToRulePath: slowPlanResp.ok === true && slowPlanResp.intent === 'PLAN_TRIP' && Boolean(slowPlanResp.planId),
    noHttpErrorOnSlowDependency: slowLightResp.status === 200 && slowPlanResp.status === 200,
    otherEndpointsStayedFast: result.slowLight.otherEndpointsStayedFast,
    noThreadHangAfterRecovery: result.recovery.allOk === true,
    degradationWarnedInLog: result.logEvidence.length > 0,
    slowWithinTimeoutNotMisreported: withinResp.ok === true,
    /** P1：端到端在 30s 预算 + 5s 余量内收敛；不收敛即为产品缺陷（如实 REVIEW） */
    endToEndWithinBudget: slowPlanResp.ms <= 35_000,
    /** P1：降级必须在用户可见文案里如实说明（跳过哪些阶段 + 未经完整校验） */
    budgetDegradeDisclosed: Boolean(budgetEcho) && degradedStages.length > 0,
  };
  result.conclusion = Object.values(result.verdict).every(Boolean) ? 'PASS' : 'REVIEW';
  result.endToEndBounded = slowPlanResp.ms <= 35_000;
  result.endToEndNotBounded = result.endToEndBounded
    ? null
    : `端到端 ${slowPlanResp.ms}ms 仍超出 30s 预算量级（本轮 ${slowPlanStub.total} 次 LLM 调用）：预算未生效，属产品缺陷`;

  await saveSection('slowLlm', result);
  console.log(
    JSON.stringify(
      {
        baselineLightP50: result.baselineLight.latency.p50,
        baselinePlanP50: result.baselinePlan.latency.p50,
        slowWithinTimeoutMs: withinResp.ms,
        slowLightMs: slowLightResp.ms,
        slowLightStubAborted: result.slowLight.stub.aborted,
        slowPlanMs: slowPlanResp.ms,
        slowPlanLlmHits: slowPlanStub.total,
        slowPlanBudget: result.slowPlan.budget,
        endToEndBounded: result.endToEndBounded,
        recoveryP50: result.recovery.latency.p50,
        verdict: result.verdict,
      },
      null,
      2,
    ),
  );
}

main().catch((e) => {
  console.error('FAILED', e);
  process.exit(1);
});