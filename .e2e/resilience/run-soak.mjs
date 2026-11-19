#!/usr/bin/env node
/**
 * 测试 4：长时间运行（主实例 :18090，12 分钟，每 5s 一轮混合请求）。
 *
 * 每个 5s 周期顺序发 4 个请求：chat 轻量问答 / 行程列表 / runs / health。
 * 用独立临时账号（chat 只发不落行程的轻量问答，避免污染验收账号 demo 的数据）。
 *
 * 采集：
 *   - 每个请求的耗时/状态（客户端墙钟）；
 *   - 后端进程 RSS / 线程数 / H2 文件大小（由 soak-proc.ps1 后台每 5s 采样到 CSV，本脚本结束时读入）；
 *   - 结束时 /actuator/health 仍 UP。
 *
 * 判定：0 未预期失败、0 5xx、RSS 无单调暴涨（给出起点/终点/斜率）、health 仍 UP。
 *
 * 用法：node run-soak.mjs [--minutes=12] [--interval=5000]
 */
import { appendFileSync, existsSync, readFileSync, writeFileSync } from 'node:fs';
import { join } from 'node:path';
import { ROOT, PORTS, base, httpJson, registerTemp, stats, writeJson, countBy, slope, sleep, round1 } from './lib.mjs';

const MAIN = Number(process.env.MAIN_PORT || PORTS.main);
const REPORT = join(ROOT, '.evidence', 'reports', 'resilience.json');
const RUN_DIR = join(ROOT, '.evidence', 'resilience-run');
const RAW_JSONL = join(RUN_DIR, 'soak-requests.jsonl');
const PROC_CSV = join(RUN_DIR, 'soak-proc.csv');
const HEALTH = join(RUN_DIR, 'soak-health.json');

function argNum(name, fallback) {
  const hit = process.argv.find((a) => a.startsWith(`--${name}=`));
  return hit ? Number(hit.split('=')[1]) : fallback;
}

/** chat 用的轻量消息：全部不落库（无行程创建/待确认方案） */
const CHAT_MESSAGES = ['你好', '谢谢', '北京有哪些必去景点？', '两个人去成都玩三天怎么安排比较好？'];

function saveSection(section, data) {
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

function readProcCsv(fromMs, toMs) {
  if (!existsSync(PROC_CSV)) return [];
  const rows = readFileSync(PROC_CSV, 'utf8').split(/\r?\n/).filter((l) => l && !/^\uFEFF?iso,/.test(l));
  const out = [];
  for (const line of rows) {
    const [iso, epochMs, rssBytes, threads, dbBytes] = line.split(',');
    // 以 ISO 时间戳为准：PowerShell 侧算出的 epoch 可能受 DateTimeKind 影响而偏移
    const parsed = Date.parse(iso);
    const t = Number.isFinite(parsed) ? parsed : Number(epochMs);
    if (!Number.isFinite(t) || t < fromMs || t > toMs) continue;
    out.push({
      iso,
      epochMs: t,
      rssMB: round1(Number(rssBytes) / 1024 / 1024),
      threads: Number(threads),
      dbBytes: Number(dbBytes),
    });
  }
  return out;
}

function windowStats(records, windowMs) {
  if (!records.length) return [];
  const t0 = Date.parse(records[0].at);
  const buckets = new Map();
  for (const r of records) {
    const idx = Math.floor((Date.parse(r.at) - t0) / windowMs);
    if (!buckets.has(idx)) buckets.set(idx, []);
    buckets.get(idx).push(r.ms);
  }
  return [...buckets.entries()]
    .sort((a, b) => a[0] - b[0])
    .map(([idx, xs]) => ({ windowStartMin: round1((idx * windowMs) / 60000), count: xs.length, latency: stats(xs) }));
}

async function main() {
  const minutes = argNum('minutes', 12);
  const intervalMs = argNum('interval', 5000);
  const durationMs = minutes * 60_000;
  const startedAt = new Date().toISOString();
  const startMs = Date.now();
  const user = await registerTemp(MAIN, 'soak');
  const token = user.token;
  const sys = await httpJson(`${base(MAIN)}/api/system/status`, { token, timeoutMs: 20_000 });
  writeFileSync(RAW_JSONL, '', 'utf8');

  const records = [];
  let cycle = 0;
  let idx = 0;
  const kinds = ['chat', 'trips', 'runs', 'health'];
  const deadline = startMs + durationMs;
  while (Date.now() < deadline) {
    const cycleStart = Date.now();
    for (let k = 0; k < kinds.length; k++) {
      const kind = kinds[k];
      const at = new Date().toISOString();
      let r;
      if (kind === 'chat') {
        const msg = CHAT_MESSAGES[idx++ % CHAT_MESSAGES.length];
        r = await httpJson(`${base(MAIN)}/api/agent/chat`, { method: 'POST', token, body: { message: msg }, timeoutMs: 60_000 });
        r.extra = { message: msg, intent: r.json?.data?.intent, code: r.json?.code };
      } else if (kind === 'trips') {
        r = await httpJson(`${base(MAIN)}/api/trips`, { token, timeoutMs: 30_000 });
        r.extra = { count: Array.isArray(r.json?.data) ? r.json.data.length : null };
      } else if (kind === 'runs') {
        r = await httpJson(`${base(MAIN)}/api/agent/runs?limit=20`, { token, timeoutMs: 30_000 });
        r.extra = { count: Array.isArray(r.json?.data) ? r.json.data.length : null };
      } else {
        r = await httpJson(`${base(MAIN)}/actuator/health`, { timeoutMs: 10_000 });
        r.extra = { health: r.json?.status ?? null };
      }
      const rec = {
        at,
        cycle,
        kind,
        status: r.status,
        ms: r.ms,
        ok: r.ok,
        code: r.json?.code ?? null,
        error: r.error,
        extra: r.extra ?? null,
      };
      records.push(rec);
      appendFileSync(RAW_JSONL, `${JSON.stringify(rec)}\n`, 'utf8');
    }
    cycle++;
    const elapsed = Date.now() - cycleStart;
    const wait = Math.max(0, intervalMs - elapsed);
    if (Date.now() + wait < deadline) await sleep(wait);
    else break;
  }
  const endedAt = new Date().toISOString();
  const endMs = Date.now();

  // 收尾核验
  const finalHealth = await httpJson(`${base(MAIN)}/actuator/health`, { timeoutMs: 10_000 });
  const finalStatus = await httpJson(`${base(MAIN)}/api/system/status`, { token, timeoutMs: 20_000 });
  const finalRuns = await httpJson(`${base(MAIN)}/api/agent/runs?limit=5`, { token, timeoutMs: 20_000 });

  const proc = readProcCsv(startMs, endMs);
  const rss = proc.map((p) => p.rssMB);
  const threads = proc.map((p) => p.threads);
  const dbBytes = proc.map((p) => p.dbBytes);
  const rssPoints = proc.map((p) => ({ x: (p.epochMs - startMs) / 1000, y: p.rssMB }));
  const threadPoints = proc.map((p) => ({ x: (p.epochMs - startMs) / 1000, y: p.threads }));
  const dbPoints = proc.map((p) => ({ x: (p.epochMs - startMs) / 1000, y: p.dbBytes }));

  /** 单调暴涨判定：RSS 末值显著高于首值且斜率持续为正 → 需要排除「JIT/缓存预热」的正常爬升 */
  const rssStart = rss.length ? rss[0] : null;
  const rssEnd = rss.length ? rss[rss.length - 1] : null;
  const rssMin = rss.length ? Math.min(...rss) : null;
  const rssMax = rss.length ? Math.max(...rss) : null;
  const rssSlopeMBPerSec = slope(rssPoints);
  const rssSlopeMBPerMin = rssSlopeMBPerSec == null ? null : round1(rssSlopeMBPerSec * 60);
  const rssGrowthPct = rssStart ? round1(((rssEnd - rssStart) / rssStart) * 100) : null;

  const failures = records.filter((r) => !r.ok || (r.code != null && r.code !== 0));
  const untracked = failures.filter((r) => r.error && r.status === 0);
  const fivexx = records.filter((r) => r.status >= 500);

  const byKind = {};
  for (const k of kinds) {
    const subset = records.filter((r) => r.kind === k);
    byKind[k] = {
      count: subset.length,
      ok: subset.filter((r) => r.ok).length,
      failures: subset.filter((r) => !r.ok).map((r) => ({ at: r.at, status: r.status, error: r.error, code: r.code })),
      latency: stats(subset.map((r) => r.ms)),
    };
  }

  const result = {
    name: 'soak',
    endpoint: `http://127.0.0.1:${MAIN}`,
    startedAt,
    endedAt,
    actualDurationSeconds: round1((endMs - startMs) / 1000),
    requestedDurationMinutes: minutes,
    intervalMs,
    /** 实际时长不足时如实记录 */
    durationHonored: Math.abs(endMs - startMs - durationMs) < 15_000,
    user: user.username,
    systemStatus: sys.json?.data ?? null,
    mix: { perCycle: kinds, cycles: cycle, chatMessages: CHAT_MESSAGES },
    totals: {
      requests: records.length,
      ok: records.length - failures.length,
      failures: failures.length,
      untrackedNetworkFailures: untracked.length,
      server5xx: fivexx.length,
      byHttpStatus: countBy(records, (r) => r.status),
      byKindFailures: countBy(failures, (r) => r.kind),
    },
    latency: {
      all: stats(records.map((r) => r.ms)),
      byKind,
      windows2min: windowStats(records, 2 * 60_000),
      windows5min: windowStats(records, 5 * 60_000),
    },
    process: {
      samples: proc.length,
      rssMB: { start: rssStart, end: rssEnd, min: rssMin, max: rssMax, growthPct: rssGrowthPct, slopeMBPerMin: rssSlopeMBPerMin },
      threads: {
        start: threads.length ? threads[0] : null,
        end: threads.length ? threads[threads.length - 1] : null,
        min: threads.length ? Math.min(...threads) : null,
        max: threads.length ? Math.max(...threads) : null,
        slopePerMin: slope(threadPoints) == null ? null : round1(slope(threadPoints) * 60),
      },
      dbBytes: {
        start: dbBytes.length ? dbBytes[0] : null,
        end: dbBytes.length ? dbBytes[dbBytes.length - 1] : null,
        min: dbBytes.length ? Math.min(...dbBytes) : null,
        max: dbBytes.length ? Math.max(...dbBytes) : null,
        growthBytes: dbBytes.length ? dbBytes[dbBytes.length - 1] - dbBytes[0] : null,
        slopeBytesPerMin: slope(dbPoints) == null ? null : Math.round(slope(dbPoints) * 60),
      },
      curve: proc.filter((_, i) => i % 6 === 0 || i === proc.length - 1),
    },
    healthAtEnd: {
      actuatorStatus: finalHealth.json?.status ?? null,
      httpStatus: finalHealth.status,
      ms: finalHealth.ms,
      runsOk: finalRuns.ok,
      llmConfigured: finalStatus.json?.data?.llmConfigured ?? null,
      routeProvider: finalStatus.json?.data?.routeProvider ?? null,
    },
    failureSamples: failures.slice(0, 20),
  };
  result.verdict = {
    noUnexpectedFailures: failures.length === 0,
    noServer5xx: fivexx.length === 0,
    healthStillUp: finalHealth.json?.status === 'UP',
    rssNoMonotonicBlowup: rssSlopeMBPerMin == null ? null : rssSlopeMBPerMin < 5,
    durationHonored: result.durationHonored,
  };
  result.conclusion = Object.values(result.verdict).every((v) => v !== false) ? 'PASS' : 'REVIEW';
  saveSection('soak', result);
  writeJson(HEALTH, { at: new Date().toISOString(), health: finalHealth.json?.status, results: result.totals, rss: result.process.rssMB });
  console.log(
    JSON.stringify(
      {
        durationSeconds: result.actualDurationSeconds,
        totals: result.totals,
        latency: result.latency.all,
        p95Trend: result.latency.windows2min.map((w) => ({ min: w.windowStartMin, p95: w.latency.p95, p50: w.latency.p50, count: w.count })),
        rss: result.process.rssMB,
        threads: result.process.threads,
        dbBytes: result.process.dbBytes,
        healthAtEnd: result.healthAtEnd,
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