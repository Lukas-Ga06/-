#!/usr/bin/env node
/**
 * 测试 1：并发（主实例 :18090，LLM 关闭 / 限流关闭）。
 *
 * 每个测试轮次：
 *   - 20 并发 POST /api/agent/chat（简单规划，每人预算不同，用于检测响应串号）；
 *   - 紧接 10 并发 GET /api/agent/runs?limit=20。
 * 共 2 轮，统计：成功率、HTTP 状态分布、p50/p95/max、5xx/超时数、
 * planId 唯一性、预算回显一致性（串号/错乱检测）、tripId 异常。
 *
 * 用法：node run-concurrency.mjs
 */
import { existsSync, readFileSync } from 'node:fs';
import { join } from 'node:path';
import { ROOT, PORTS, base, httpJson, login, registerTemp, stats, countBy, writeJson, runConcurrent, ACCOUNT, metricValue as mval } from './lib.mjs';

const MAIN = PORTS.main;
const REPORT = join(ROOT, '.evidence', 'reports', 'resilience.json');
const ROUNDS = 2;
const CHAT_CONCURRENCY = 20;
const RUNS_CONCURRENCY = 10;
const ROUND_GAP_MS = 3000;

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
  report.meta = { ...(report.meta || {}), generatedAt: new Date().toISOString(), node: process.version, host: '127.0.0.1', percentile: 'nearest-rank' };
  writeJson(REPORT, report);
}

async function serverMetric(token, name, tag) {
  const q = tag ? `?tag=${encodeURIComponent(tag)}` : '';
  const r = await httpJson(`${base(MAIN)}/actuator/metrics/${name}${q}`, { token });
  return r.ok ? r.json : { missing: true, status: r.status };
}

function classify(req) {
  if (req.error && req.status === 0) return req.aborted ? 'client-timeout' : 'network-error';
  if (req.status >= 500) return '5xx';
  if (req.status === 429) return '429';
  if (req.status === 401 || req.status === 403) return 'auth';
  if (req.status !== 200) return `http-${req.status}`;
  if (req.code !== 0) return `biz-${req.code}`;
  return 'ok';
}

async function main() {
  const startedAt = new Date().toISOString();
  const sys = await httpJson(`${base(MAIN)}/api/system/status`, { token: await login(MAIN) });
  const demoToken = await login(MAIN, ACCOUNT.username, ACCOUNT.password);
  const chatMetricBefore = await serverMetric(demoToken, 'http.server.requests');
  const user = await registerTemp(MAIN, 'conc');
  const token = user.token;

  const result = {
    name: 'concurrency',
    startedAt,
    endpoint: `http://127.0.0.1:${MAIN}`,
    systemStatus: sys.json?.data ?? null,
    params: {
      rounds: ROUNDS,
      chatConcurrency: CHAT_CONCURRENCY,
      runsConcurrency: RUNS_CONCURRENCY,
      chatTimeoutMs: 120_000,
      messageTemplate: '杭州两日游，两个人，预算${2000+10*i}元',
      user: user.username,
      note: '独立临时账号，避免污染验收账号 demo',
    },
    rounds: [],
    planIds: [],
    serverMetrics: {},
    verdict: {},
  };

  for (let round = 1; round <= ROUNDS; round++) {
    // 20 并发规划请求；每人预算不同 → 响应里回显的预算可检测串号
    const budgets = Array.from({ length: CHAT_CONCURRENCY }, (_, i) => 2000 + round * 100 + i * 10);
    const messages = budgets.map((b) => `杭州两日游，两个人，预算${b}元`);
    const chatStart = Date.now();
    const chatResults = await runConcurrent(
      messages.map((message) => async () => {
        const r = await httpJson(`${base(MAIN)}/api/agent/chat`, {
          method: 'POST',
          token,
          body: { message },
          timeoutMs: 120_000,
        });
        return {
          message,
          budget: Number(message.match(/预算(\d+)元/)[1]),
          status: r.status,
          ms: r.ms,
          ok: r.ok,
          code: r.json?.code,
          intent: r.json?.data?.intent,
          resultStatus: r.json?.data?.status,
          planId: r.json?.data?.planId ?? null,
          tripId: r.json?.data?.tripId ?? null,
          requiresAck: r.json?.data?.confirmation?.requiresAcknowledgement ?? null,
          riskLevel: r.json?.data?.confirmation?.riskLevel ?? null,
          warnings: r.json?.data?.warnings?.length ?? 0,
          messageLen: (r.json?.data?.message || '').length,
          reply: (r.json?.data?.message || '').slice(0, 400),
          error: r.error,
          aborted: r.aborted === true,
        };
      }),
    );
    const chatWallMs = Date.now() - chatStart;

    // 10 并发 runs
    const runsStart = Date.now();
    const runsResults = await runConcurrent(
      Array.from({ length: RUNS_CONCURRENCY }, () => async () => {
        const r = await httpJson(`${base(MAIN)}/api/agent/runs?limit=20`, { token, timeoutMs: 60_000 });
        return {
          status: r.status,
          ms: r.ms,
          ok: r.ok,
          code: r.json?.code,
          count: Array.isArray(r.json?.data) ? r.json.data.length : null,
          error: r.error,
          aborted: r.aborted === true,
        };
      }),
    );
    const runsWallMs = Date.now() - runsStart;

    // ---- 一致性检查 ----
    const oks = chatResults.filter((r) => r.ok && r.code === 0);
    const planIds = oks.map((r) => r.planId).filter(Boolean);
    const uniquePlanIds = new Set(planIds);
    // 预算回显：回复里应出现自己的预算金额，且不得出现别人的预算
    const budgetMismatch = oks.filter((r) => !r.reply.includes(`¥${r.budget}`));
    const budgetLeak = oks.filter((r) => {
      const own = `¥${r.budget}`;
      return budgets.some((b) => b !== r.budget && r.reply.includes(`¥${b}`));
    });
    const tripIdAnomalies = oks.filter((r) => r.tripId !== null && r.tripId !== undefined);
    const nonPlan = oks.filter((r) => r.intent !== 'PLAN_TRIP' || !r.planId);

    result.planIds.push(...planIds);
    result.rounds.push({
      round,
      budgets,
      chat: {
        count: chatResults.length,
        wallMs: chatWallMs,
        latency: stats(chatResults.map((r) => r.ms)),
        statusDistribution: countBy(chatResults, (r) => classify(r)),
        httpStatusDistribution: countBy(chatResults, (r) => r.status),
        successCount: oks.length,
        successRate: Number((oks.length / chatResults.length).toFixed(4)),
        clientTimeouts: chatResults.filter((r) => r.aborted).length,
        server5xx: chatResults.filter((r) => r.status >= 500).length,
        planIdCount: planIds.length,
        uniquePlanIds: uniquePlanIds.size,
        duplicatePlanIds: planIds.filter((id, idx) => planIds.indexOf(id) !== idx),
        budgetEchoMismatch: budgetMismatch.map((r) => ({ budget: r.budget, planId: r.planId, reply: r.reply.slice(0, 160) })),
        budgetCrossLeak: budgetLeak.map((r) => ({ budget: r.budget, reply: r.reply.slice(0, 200) })),
        tripIdAnomalies: tripIdAnomalies.map((r) => ({ tripId: r.tripId, planId: r.planId })),
        nonPlanResponses: nonPlan.map((r) => ({ intent: r.intent, planId: r.planId, reply: r.reply.slice(0, 120) })),
        samples: chatResults.map((r) => ({
          budget: r.budget,
          status: r.status,
          ms: r.ms,
          code: r.code,
          intent: r.intent,
          planId: r.planId,
          warnings: r.warnings,
          requiresAck: r.requiresAck,
          error: r.error,
        })),
      },
      runs: {
        count: runsResults.length,
        wallMs: runsWallMs,
        latency: stats(runsResults.map((r) => r.ms)),
        statusDistribution: countBy(runsResults, (r) => (r.ok && r.code === 0 ? 'ok' : r.status >= 500 ? '5xx' : `http-${r.status}`)),
        successCount: runsResults.filter((r) => r.ok && r.code === 0).length,
        listSizes: runsResults.map((r) => r.count),
        server5xx: runsResults.filter((r) => r.status >= 500).length,
        samples: runsResults.map((r) => ({ status: r.status, ms: r.ms, count: r.count, error: r.error })),
      },
    });

    if (round < ROUNDS) await new Promise((r) => setTimeout(r, ROUND_GAP_MS));
  }

  // ---- 全量 runs 回查：确认两次并发的 40 条运行都落库且消息不串 ----
  await new Promise((r) => setTimeout(r, 800));
  const verify = await httpJson(`${base(MAIN)}/api/agent/runs?limit=60`, { token });
  const runs = Array.isArray(verify.json?.data) ? verify.json.data : [];
  const expectedMessages = new Set(result.rounds.flatMap((r) => r.budgets.map((b) => `杭州两日游，两个人，预算${b}元`)));
  const runMessages = runs.map((r) => r.message);
  const unexpectedRunMessages = runMessages.filter((m) => !expectedMessages.has(m));
  const runIdList = runs.map((r) => r.runId).filter(Boolean);
  const chatMetricAfter = await serverMetric(demoToken, 'http.server.requests');
  const status500 = await serverMetric(demoToken, 'http.server.requests', 'status:500');

  result.verification = {
    queriedRuns: runs.length,
    expectedChatRuns: ROUNDS * CHAT_CONCURRENCY,
    matchedChatRuns: runMessages.filter((m) => expectedMessages.has(m)).length,
    unexpectedRunMessages: unexpectedRunMessages.slice(0, 10),
    uniqueRunIds: new Set(runIdList).size,
    runStatusDistribution: countBy(runs, (r) => r.status),
    runDurationMs: stats(runs.map((r) => r.durationMs)),
    /** 关键不变量：同一次并发的每条运行都带自己的消息原文，无交叉 */
    messageIntegrityOk: unexpectedRunMessages.length === 0 && runMessages.filter((m) => expectedMessages.has(m)).length === ROUNDS * CHAT_CONCURRENCY,
  };
  result.serverMetrics = {
    httpServerRequestsBefore: mval(chatMetricBefore),
    httpServerRequestsAfter: mval(chatMetricAfter),
    status500Count: mval(status500),
  };

  const allRounds = result.rounds;
  result.summary = {
    chatLatency: stats(allRounds.flatMap((r) => r.chat.samples.map((s) => s.ms))),
    runsLatency: stats(allRounds.flatMap((r) => r.runs.samples.map((s) => s.ms))),
    totalChatRequests: allRounds.reduce((a, r) => a + r.chat.count, 0),
    totalChatSuccess: allRounds.reduce((a, r) => a + r.chat.successCount, 0),
    totalRunsRequests: allRounds.reduce((a, r) => a + r.runs.count, 0),
    totalRunsSuccess: allRounds.reduce((a, r) => a + r.runs.successCount, 0),
    server5xx: allRounds.reduce((a, r) => a + r.chat.server5xx + r.runs.server5xx, 0),
    clientTimeouts: allRounds.reduce((a, r) => a + r.chat.clientTimeouts, 0),
    duplicatePlanIds: allRounds.reduce((a, r) => a + r.chat.duplicatePlanIds.length, 0),
    budgetEchoMismatch: allRounds.reduce((a, r) => a + r.chat.budgetEchoMismatch.length, 0),
    budgetCrossLeak: allRounds.reduce((a, r) => a + r.chat.budgetCrossLeak.length, 0),
    tripIdAnomalies: allRounds.reduce((a, r) => a + r.chat.tripIdAnomalies.length, 0),
  };
  const s = result.summary;
  result.verdict = {
    allChatSucceeded: s.totalChatSuccess === s.totalChatRequests,
    allRunsSucceeded: s.totalRunsSuccess === s.totalRunsRequests,
    no5xx: s.server5xx === 0,
    noClientTimeout: s.clientTimeouts === 0,
    noDuplicatePlanId: s.duplicatePlanIds === 0,
    noBudgetCrossTalk: s.budgetEchoMismatch === 0 && s.budgetCrossLeak === 0,
    noTripIdAnomaly: s.tripIdAnomalies === 0,
    runsMessageIntegrity: result.verification.messageIntegrityOk,
  };
  result.conclusion = Object.values(result.verdict).every(Boolean) ? 'PASS' : 'REVIEW';
  result.finishedAt = new Date().toISOString();
  result.rateLimitEnabled = result.systemStatus?.rateLimitEnabled ?? null;
  result.llmConfigured = result.systemStatus?.llmConfigured ?? null;

  await saveSection('concurrency', result);
  console.log(JSON.stringify({ summary: result.summary, verdict: result.verdict, chatLatency: result.summary.chatLatency }, null, 2));
}

main().catch((e) => {
  console.error('FAILED', e);
  process.exit(1);
});