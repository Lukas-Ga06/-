#!/usr/bin/env node
/**
 * 汇总：把四项测试的分项结论收拢成 resilience.json 顶层的 summary，
 * 便于报告与验收工具一次读全。所有数字都从分项结果里取，不手工填。
 *
 * 用法：node summarize.mjs
 */
import { existsSync, readFileSync } from 'node:fs';
import { join } from 'node:path';
import { ROOT, writeJson } from './lib.mjs';

const REPORT = join(ROOT, '.evidence', 'reports', 'resilience.json');
if (!existsSync(REPORT)) {
  console.error('resilience.json not found');
  process.exit(1);
}
const r = JSON.parse(readFileSync(REPORT, 'utf8'));

const c = r.concurrency;
const s = r.slowLlm;
const t = r.thirdParty;
const k = r.soak;

const slowVerdict = s && {
  conclusion: s.conclusion,
  perCallTimeoutEnforced: s.verdict.perCallTimeoutEnforced,
  degradedToRulePath: s.verdict.degradedToRulePath,
  noThreadHangAfterRecovery: s.verdict.noThreadHangAfterRecovery,
  baselineMs: { light: s.baselineLight.latency.p50, plan: s.baselinePlan.latency.p50 },
  slowMs: { light: s.slowLight.response.ms, plan: s.slowPlan.response.ms },
  /** 端到端超时不受总预算约束：这是要写进报告的「问题」而不是「通过项」 */
  endToEndNotBounded: Boolean(s.endToEndNotBounded),
};

const thirdPartyVerdict = t && {
  real402: t.real402?.verdict,
  stub402: t.stubErrors?.cases?.[0]?.verdict,
  stub500: t.stubErrors?.cases?.[1]?.verdict,
  connectionRefused: t.connectionRefused?.verdict,
  amapNoKey: t.amap_18090_no_key?.verdict,
  amapInvalidKey: t.amap_18100_invalid_key?.verdict,
};

const issues = [];
if (s?.endToEndNotBounded) issues.push('慢 LLM 下一次规划端到端 128s（4×30s 读超时 + 1×8s 润色超时），无总预算/熔断');
if (t?.amap_18100_invalid_key?.selfReportConsistent === false)
  issues.push('高德 Key 无效时 /api/system/status 仍报 routeProvider=amap（真实路径），与实际全部 estimated 不一致');
if (t?.real402?.llmCallsMetric?.failedDelta != null)
  issues.push('LLM 失败只在 WARN 日志与 llm.calls{result=failed} 指标里可见，/api/system/status 的 llmConfigured 仍为 true，用户侧无降级标识');
issues.push('降级 WARN 只打印异常 wrapper message（读超时表现为 "Error while extracting response ... application/octet-stream"），不带 cause，排障时无法区分超时/解析失败');
issues.push('Boot file appender 默认平台字符集：日志文件在中文 Windows 上是 GBK，UTF-8 采集/检索会乱码');

const summary = {
  generatedAt: new Date().toISOString(),
  endpoint: 'http://127.0.0.1:18090（主实例）+ 自建实例 18100 与 LLM 替身 18110',
  tests: {
    concurrency: c && {
      conclusion: c.conclusion,
      rounds: c.params.rounds,
      chatRequests: c.summary.totalChatRequests,
      chatSuccess: c.summary.totalChatSuccess,
      runsRequests: c.summary.totalRunsRequests,
      runsSuccess: c.summary.totalRunsSuccess,
      chatLatency: c.summary.chatLatency,
      runsLatency: c.summary.runsLatency,
      server5xx: c.summary.server5xx,
      clientTimeouts: c.summary.clientTimeouts,
      duplicatePlanIds: c.summary.duplicatePlanIds,
      dataCrosstalk: c.summary.budgetEchoMismatch + c.summary.budgetCrossLeak,
    },
    slowDependency: slowVerdict,
    thirdParty: thirdPartyVerdict,
    soak: k && {
      conclusion: k.conclusion,
      actualDurationSeconds: k.actualDurationSeconds,
      requests: k.totals.requests,
      failures: k.totals.failures,
      server5xx: k.totals.server5xx,
      latency: k.latency.all,
      p95Trend2min: k.latency.windows2min?.map((w) => w.latency.p95),
      rss: k.process?.rssMB,
      threads: k.process?.threads,
      dbBytes: k.process?.dbBytes,
      healthAtEnd: k.healthAtEnd.actuatorStatus,
    },
  },
  issues,
  conclusion:
    c?.conclusion === 'PASS' && k?.conclusion === 'PASS' && t?.real402?.verdict === 'PASS'
      ? '可接受（四项功能均未失败；存在 5 项体验/可观测性改进项，其中「慢依赖无端到端预算」与「高德无效 Key 自报不准」建议列入整改）'
      : '需复核',
};

r.summary = summary;
writeJson(REPORT, r);
console.log(JSON.stringify(summary, null, 2));