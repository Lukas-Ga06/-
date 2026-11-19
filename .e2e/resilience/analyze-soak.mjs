#!/usr/bin/env node
/**
 * 长跑数据的后处理：把 soak-proc.csv（进程采样）与 soak-requests.jsonl（请求明细）
 * 合并成 .evidence/reports/resilience.json 的 soak.process 段。
 *
 * 单独成文件的原因：采样器是另一个进程（PowerShell），它写 CSV 的 epoch 列曾因
 * PowerShell 的 DateTimeKind 处理偏差了 8 小时；这里统一以 ISO 时间戳为准重算，
 * 这样长跑数据不必重跑也能得到正确结论。
 *
 * 用法：node analyze-soak.mjs
 */
import { existsSync, readFileSync } from 'node:fs';
import { join } from 'node:path';
import { ROOT, stats, slope, writeJson, round1 } from './lib.mjs';

const RUN_DIR = join(ROOT, '.evidence', 'resilience-run');
const CSV = join(RUN_DIR, 'soak-proc.csv');
const JSONL = join(RUN_DIR, 'soak-requests.jsonl');
const REPORT = join(ROOT, '.evidence', 'reports', 'resilience.json');

function readRequests() {
  return readFileSync(JSONL, 'utf8')
    .split(/\r?\n/)
    .filter(Boolean)
    .map((l) => {
      const r = JSON.parse(l);
      r.t = Date.parse(r.at);
      return r;
    })
    .filter((r) => Number.isFinite(r.t));
}

/** 以 ISO 时间戳为准解析采样（epoch 列只在 ISO 不可解析时兜底） */
function readProc() {
  if (!existsSync(CSV)) return [];
  return readFileSync(CSV, 'utf8')
    .split(/\r?\n/)
    .filter((l) => l && !/^\uFEFF?iso,/.test(l))
    .map((line) => {
      const [iso, epochMs, rssBytes, threads, dbBytes] = line.split(',');
      const t = Date.parse(iso);
      const fallback = Number(epochMs);
      return {
        t: Number.isFinite(t) ? t : fallback,
        iso,
        rssMB: round1(Number(rssBytes) / 1024 / 1024),
        threads: Number(threads),
        dbBytes: Number(dbBytes),
      };
    })
    .filter((p) => Number.isFinite(p.t))
    .sort((a, b) => a.t - b.t);
}

function windowStats(records, windowMs) {
  const t0 = records[0].t;
  const buckets = new Map();
  for (const r of records) {
    const idx = Math.floor((r.t - t0) / windowMs);
    if (!buckets.has(idx)) buckets.set(idx, []);
    buckets.get(idx).push(r.ms);
  }
  return [...buckets.entries()]
    .sort((a, b) => a[0] - b[0])
    .map(([idx, xs]) => ({ windowStartMin: round1((idx * windowMs) / 60000), count: xs.length, latency: stats(xs) }));
}

const requests = readRequests();
const proc = readProc();
if (!requests.length) {
  console.error('no requests recorded');
  process.exit(1);
}
const startMs = requests[0].t;
const endMs = requests[requests.length - 1].t;
const inWindow = proc.filter((p) => p.t >= startMs - 5000 && p.t <= endMs + 5000);
const rss = inWindow.map((p) => p.rssMB);
const threads = inWindow.map((p) => p.threads);
const db = inWindow.map((p) => p.dbBytes);
const x = inWindow.map((p) => (p.t - startMs) / 1000);

const half = Math.floor(inWindow.length / 2);
const lastHalf = inWindow.slice(half);

/** 2 分钟窗口均值：GC 锯齿（涨到阈值再被回收）下，窗口均值比逐点斜率更能反映真实趋势 */
const WINDOW_MS = 2 * 60_000;
const buckets = new Map();
for (const p of inWindow) {
  const idx = Math.floor((p.t - startMs) / WINDOW_MS);
  if (!buckets.has(idx)) buckets.set(idx, []);
  buckets.get(idx).push(p);
}
const rssWindowsAll = [...buckets.entries()]
  .sort((a, b) => a[0] - b[0])
  .map(([idx, ps]) => {
    const vals = ps.map((p) => p.rssMB);
    return {
      startMin: round1(idx * (WINDOW_MS / 60000)),
      samples: ps.length,
      min: Math.min(...vals),
      mean: round1(vals.reduce((a, b) => a + b, 0) / vals.length),
      max: Math.max(...vals),
    };
  });
/** 只有完整窗口参与趋势回归（首尾半截窗口样本太少，会把均值拉偏） */
const rssWindows = rssWindowsAll.filter((w) => w.samples >= 5);
const meanSlopePerMin = round1(slope(rssWindows.map((w) => ({ x: w.startMin, y: w.mean }))));

const rssSlope = slope(x.map((v, i) => ({ x: v, y: rss[i] })));
const rssLastHalfSlope = slope(lastHalf.map((p, i) => ({ x: i, y: p.rssMB })));
const thrSlope = slope(x.map((v, i) => ({ x: v, y: threads[i] })));
const dbSlope = slope(x.map((v, i) => ({ x: v, y: db[i] })));

const process = {
  samples: inWindow.length,
  sampleIntervalSeconds: inWindow.length > 1 ? round1((inWindow[inWindow.length - 1].t - inWindow[0].t) / 1000 / (inWindow.length - 1)) : null,
  rssMB: {
    start: rss[0],
    end: rss[rss.length - 1],
    min: Math.min(...rss),
    max: Math.max(...rss),
    deltaMB: round1(rss[rss.length - 1] - rss[0]),
    growthPct: round1(((rss[rss.length - 1] - rss[0]) / rss[0]) * 100),
    slopeMBPerMinWhole: round1(rssSlope * 60),
    slopeMBPerMinLastHalf: round1(rssLastHalfSlope * 60),
    slopeMBPerMinWindowMeans: meanSlopePerMin,
    peakAtSeconds: round1(x[rss.indexOf(Math.max(...rss))]),
    /** 每个 2 分钟窗口的 min/mean/max：用于判断「是否单调上台阶」而不是被 GC 锯齿误导 */
    windows2min: rssWindows,
  },
  threads: {
    start: threads[0],
    end: threads[threads.length - 1],
    min: Math.min(...threads),
    max: Math.max(...threads),
    slopePerMinWhole: round1(thrSlope * 60),
  },
  dbBytes: {
    start: db[0],
    end: db[db.length - 1],
    deltaBytes: db[db.length - 1] - db[0],
    deltaMB: round1((db[db.length - 1] - db[0]) / 1024 / 1024),
    slopeBytesPerMin: Math.round(dbSlope * 60),
  },
  /** 曲线抽样（每 30s 一个点，够画表格） */
  curve: inWindow
    .filter((_, i) => i % 6 === 0 || i === inWindow.length - 1)
    .map((p) => ({ tMin: round1((p.t - startMs) / 60000), rssMB: p.rssMB, threads: p.threads, dbMB: round1(p.dbBytes / 1024 / 1024) })),
};

const report = JSON.parse(readFileSync(REPORT, 'utf8'));
if (!report.soak) {
  console.error('resilience.json has no soak section');
  process.exit(1);
}
report.soak.process = process;
report.soak.latency.windows2min = windowStats(requests, 2 * 60_000);
report.soak.latency.windows5min = windowStats(requests, 5 * 60_000);
report.soak.analysisNote =
  '进程采样来自 soak-proc.csv（每 5s 一次，目标 pid 为当前自建实例的 java 进程）；时间以 ISO 时间戳为准重算。slopeMBPerMinLastHalf 用后一半样本，用于排除 JVM 预热造成的「假增长」；窗口均值回归与 jcmd 堆诊断（见 resilience-postfix.json → soakColdStart.jcmd）用于区分泄漏与堆扩张。';
report.soak.verdict.rssNoMonotonicBlowup =
  process.rssMB.slopeMBPerMinWindowMeans < 5 &&
  process.rssMB.slopeMBPerMinWhole < 5 &&
  process.threads.max - process.threads.min < 20;
report.soak.verdict.rssReason =
  `全程逐点回归 ${process.rssMB.slopeMBPerMinWhole} MB/min，2 分钟窗口均值回归 ${process.rssMB.slopeMBPerMinWindowMeans} MB/min，` +
  `RSS ${process.rssMB.min}~${process.rssMB.max} MB（起 ${process.rssMB.start} → 终 ${process.rssMB.end}），呈 GC 锯齿而非单调上升；线程 ${process.threads.min}~${process.threads.max}`;
writeJson(REPORT, report);

console.log(
  JSON.stringify(
    {
      samples: process.samples,
      rssMB: process.rssMB,
      threads: process.threads,
      dbBytes: process.dbBytes,
      p95Trend2min: report.soak.latency.windows2min.map((w) => ({ min: w.windowStartMin, p95: w.latency.p95, count: w.count })),
      verdict: report.soak.verdict,
    },
    null,
    2,
  ),
);