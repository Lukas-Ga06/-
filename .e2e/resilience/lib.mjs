/**
 * 韧性测试公共库（V2.2：并发 / 慢接口 / 第三方失败 / 长时间运行）。
 *
 * 只依赖 Node 原生模块（node:22 fetch / node:fs），不引第三方依赖。
 * 所有时间统计均为「客户端墙钟毫秒」，用 performance.now() 取单调时钟。
 */
import { writeFileSync, mkdirSync, readFileSync, existsSync } from 'node:fs';
import { dirname, resolve } from 'node:path';

export const ROOT = resolve(new URL('../..', import.meta.url).pathname.replace(/^\/([A-Za-z]:)/, '$1'));
export const PORTS = {
  main: 18090,      // 主实例：LLM 关闭、限流关闭、无 AMAP_KEY（不要动）
  llm402: 18091,    // 真实 DeepSeek Key，余额 0 → 402（不要动）
  llmStub: 18092,   // 指向本地 LLM 替身 18099（不要动）
  own: 18100,       // 本测试自建实例
  stub: 18110,      // 本测试的 OpenAI 兼容替身
};

export const ACCOUNT = { username: 'demo', password: 'demo123' };

export function base(port) {
  return `http://127.0.0.1:${port}`;
}

export const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

export function nowMs() {
  return performance.now();
}

/** 单调时钟毫秒，保留 1 位小数 */
export function round1(n) {
  return Math.round(n * 10) / 10;
}

/**
 * 单次 HTTP 请求，永远不抛：失败以 {ok:false, error} 形式返回，便于统计失败分类。
 * @param {string} url 绝对 URL
 * @param {{method?:string, token?:string, body?:any, timeoutMs?:number, headers?:object}} opts
 */
export async function httpJson(url, opts = {}) {
  const { method = 'GET', token, body, timeoutMs = 120_000, headers = {} } = opts;
  const started = nowMs();
  const h = { ...headers };
  if (token) h.Authorization = `Bearer ${token}`;
  let payload;
  if (body !== undefined) {
    h['Content-Type'] = 'application/json';
    payload = typeof body === 'string' ? body : JSON.stringify(body);
  }
  const ctrl = new AbortController();
  const timer = setTimeout(() => ctrl.abort(new Error(`client-timeout-${timeoutMs}ms`)), timeoutMs);
  try {
    const res = await fetch(url, { method, headers: h, body: payload, signal: ctrl.signal });
    const text = await res.text();
    const ms = nowMs() - started;
    let json = null;
    try {
      json = text ? JSON.parse(text) : null;
    } catch {
      /* 非 JSON（如网关错误页）保留原文 */
    }
    return { ok: res.ok, status: res.status, ms: round1(ms), json, text: text.slice(0, 2000), error: null };
  } catch (e) {
    const aborted = ctrl.signal.aborted;
    return {
      ok: false,
      status: 0,
      ms: round1(nowMs() - started),
      json: null,
      text: '',
      error: aborted ? `client-timeout(${timeoutMs}ms)` : `${e.name}: ${e.message}`,
      aborted,
    };
  } finally {
    clearTimeout(timer);
  }
}

export async function login(port, username = ACCOUNT.username, password = ACCOUNT.password) {
  const r = await httpJson(`${base(port)}/api/auth/login`, {
    method: 'POST',
    body: { username, password },
    timeoutMs: 15_000,
  });
  if (!r.ok || !r.json?.data?.token) {
    throw new Error(`login failed on :${port} status=${r.status} body=${r.text?.slice(0, 300)}`);
  }
  return r.json.data.token;
}

/**
 * 注册一个临时账号（避免污染验收账号 demo 的数据）。
 * 撞名时自动加后缀重试。
 */
export async function registerTemp(port, prefix = 'resil', password = 'Resil12345') {
  for (let i = 0; i < 3; i++) {
    const username = `${prefix}${Date.now().toString(36)}${i}`.slice(0, 24);
    const r = await httpJson(`${base(port)}/api/auth/register`, {
      method: 'POST',
      body: { username, password, nickname: '韧性压测临时账号' },
      timeoutMs: 15_000,
    });
    if (r.ok && r.json?.data?.token) {
      return { username, password, token: r.json.data.token, userId: r.json.data.id };
    }
    if (r.status === 409 || (r.json && r.json.code !== 0 && /已存在/.test(r.json.message || ''))) continue;
    throw new Error(`register failed on :${port} status=${r.status} body=${r.text?.slice(0, 300)}`);
  }
  throw new Error('register failed after retries');
}

/** 最近秩（nearest-rank）分位数：返回值必然来自样本本身 */
export function percentile(sortedAsc, p) {
  if (!sortedAsc.length) return null;
  const rank = Math.ceil((p / 100) * sortedAsc.length);
  const idx = Math.min(sortedAsc.length - 1, Math.max(0, rank - 1));
  return sortedAsc[idx];
}

/** 对一组耗时样本给出 count/min/p50/p90/p95/p99/max/mean */
export function stats(samples) {
  const xs = samples.filter((x) => typeof x === 'number' && Number.isFinite(x)).sort((a, b) => a - b);
  if (!xs.length) return { count: 0 };
  const sum = xs.reduce((a, b) => a + b, 0);
  return {
    count: xs.length,
    min: round1(xs[0]),
    p50: round1(percentile(xs, 50)),
    p90: round1(percentile(xs, 90)),
    p95: round1(percentile(xs, 95)),
    p99: round1(percentile(xs, 99)),
    max: round1(xs[xs.length - 1]),
    mean: round1(sum / xs.length),
  };
}

/** 线性回归斜率（用于 RSS / p95 随时间趋势），x 单位秒，y 单位同入参 */
export function slope(points) {
  const n = points.length;
  if (n < 2) return null;
  const sx = points.reduce((a, p) => a + p.x, 0);
  const sy = points.reduce((a, p) => a + p.y, 0);
  const sxx = points.reduce((a, p) => a + p.x * p.x, 0);
  const sxy = points.reduce((a, p) => a + p.x * p.y, 0);
  const denom = n * sxx - sx * sx;
  if (Math.abs(denom) < 1e-9) return null;
  return (n * sxy - sx * sy) / denom;
}

export function ensureDir(path) {
  mkdirSync(path, { recursive: true });
}

export function writeJson(path, data) {
  ensureDir(dirname(path));
  writeFileSync(path, JSON.stringify(data, null, 2), 'utf8');
  return path;
}

/** 并发执行一组任务，返回结果数组（顺序与输入一致），单任务失败不影响其它 */
export async function runConcurrent(tasks) {
  const settled = await Promise.allSettled(tasks.map((t) => t()));
  return settled.map((s, i) =>
    s.status === 'fulfilled' ? s.value : { ok: false, status: 0, ms: null, error: `threw: ${s.reason?.message || s.reason}`, index: i },
  );
}

/** 分组计数工具 */
export function countBy(list, keyFn) {
  const out = {};
  for (const item of list) {
    const k = String(keyFn(item));
    out[k] = (out[k] || 0) + 1;
  }
  return out;
}

/** 从 /actuator/metrics 返回体里取数值：Gauge 是 VALUE，Counter/Timer 是 COUNT */
export function metricValue(metric) {
  const ms = metric?.measurements;
  if (!Array.isArray(ms) || !ms.length) return null;
  return (ms.find((x) => x.statistic === 'VALUE') || ms.find((x) => x.statistic === 'COUNT') || ms[0])?.value ?? null;
}

/* ---------------- 后端日志读取（兼容 GBK） ----------------
 * Boot 的 file appender 默认用平台字符集：中文 Windows 上日志文件是 GBK，
 * 直接按 UTF-8 读会把中文变成乱码，导致「降级」这类关键字匹配不到。
 * 这里先按 UTF-8 解码，出现替换字符（U+FFFD）时回退 GBK。
 */
export function decodeLog(buf) {
  const utf8 = buf.toString('utf8');
  if (!utf8.includes('\uFFFD')) return utf8;
  try {
    return new TextDecoder('gbk').decode(buf);
  } catch {
    return utf8;
  }
}

export function readLogLines(path) {
  if (!existsSync(path)) return [];
  return decodeLog(readFileSync(path)).split(/\r?\n/);
}

export function logLineCursor(path) {
  return readLogLines(path).length;
}

/** 返回自 startIndex 行之后、匹配 pattern 的行（去掉时间戳前缀，截断到 400 字符） */
export function logSince(path, startIndex, pattern) {
  return readLogLines(path)
    .slice(startIndex)
    .filter((l) => l && pattern.test(l))
    .map((l) => l.replace(/^\d{4}-\d{2}-\d{2}T[^ ]+\s+/, '').slice(0, 400));
}