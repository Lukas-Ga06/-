#!/usr/bin/env node
/**
 * P2 端到端验证：无效高德 Key 下的「路线口径自报」。
 *
 * 背景（修复前 P2 缺陷，见 docs/review/2026-11-19-resilience.md 与
 * .evidence/reports/resilience-prefix-baseline.json 的 thirdParty.amap_18100_invalid_key）：
 *   配了无效 AMAP_KEY 时，route-legs 明明全部 source=estimated，
 *   /api/system/status 却报 routeProvider=amap / routeHint=「路线使用高德真实路径规划」。
 *
 * 本脚本用 --amap-key=INVALID_... 起自建实例后，真实走一遍
 *   规划 → 确认（落库生成行程）→ GET /api/trips/{tripId}/route-legs → GET /api/system/status，
 * 逐条断言：
 *   A1 route-legs 非空（有可校验样本）
 *   A2 全部 source=estimated（无一条假 amap）
 *   A3 全部 polyline 是空数组（估算不给假几何点，ADR-006/009）
 *   A4 routeProvider=estimated（不再自称高德）
 *   A5 routeConfigured=true（「配了」与「可用」分离）
 *   A6 amapVerified=false（最近一次真实调用失败）
 *   A7 routeLastError 含 infocode（10001/10009 等，且不含 Key）
 *   A8 routeHint 不再宣称「使用高德真实路径规划」
 *   A9 对照：本次运行前（尚未调用过真实路径）就是 estimated（配置了≠可用，调用前后都成立）
 *   A10 规划前 routeLastError 为 null、自动调用后变非空（失败原因确实由真实调用写入）
 *
 * 用法：
 *   node .e2e/resilience/manage-instance.mjs serve --amap-key=INVALID_amap_key_for_test   # 后台作业
 *   node .e2e/resilience/run-amap-selfreport.mjs [--port=18100]
 *
 * 结果写入 .evidence/reports/resilience-postfix.json 的 p2SelfReport 段。
 */
import { existsSync, readFileSync } from 'node:fs';
import { join } from 'node:path';
import { ROOT, base, httpJson, registerTemp, writeJson, countBy, logLineCursor, logSince } from './lib.mjs';

const POSTFIX = join(ROOT, '.evidence', 'reports', 'resilience-postfix.json');
const BASELINE = join(ROOT, '.evidence', 'reports', 'resilience-prefix-baseline.json');
const INSTANCE_LOG = join(ROOT, '.evidence', 'resilience-run', 'instance.log');
const PLAN_MSG = '杭州两日游，两个人，预算2000元';

function arg(name, fallback = null) {
  const hit = process.argv.find((a) => a.startsWith(`--${name}=`));
  return hit ? hit.slice(name.length + 3) : fallback;
}
const PORT = Number(arg('port', process.env.AMAP_PORT || '18100'));
const AMAP_LABEL = arg('label', 'INVALID_amap_key_for_test（故意无效的 Key）');

const statusOf = (sys) => ({
  routeProvider: sys?.routeProvider ?? null,
  routeConfigured: sys?.routeConfigured ?? null,
  amapVerified: sys?.amapVerified ?? null,
  routeLastError: sys?.routeLastError ?? null,
  routeHint: sys?.routeHint ?? null,
  amapConfigured: sys?.amapConfigured ?? null,
  geoProvider: sys?.geoProvider ?? null,
});

/** 规划 → 确认（沿用 run-third-party 的路径，失败时记录但不抛） */
async function planAndConfirm(token) {
  const plan = await httpJson(`${base(PORT)}/api/agent/chat`, {
    method: 'POST',
    token,
    body: { message: PLAN_MSG },
    timeoutMs: 240_000,
  });
  const planId = plan.json?.data?.planId ?? null;
  const out = {
    plan: {
      status: plan.status,
      ok: plan.ok,
      code: plan.json?.code,
      ms: plan.ms,
      intent: plan.json?.data?.intent,
      resultStatus: plan.json?.data?.status,
      planId,
      error: plan.error,
    },
    contentHash: null,
    confirm: null,
    tripId: null,
  };
  if (!planId) return out;
  const preview = await httpJson(`${base(PORT)}/api/agent/plans/${planId}`, { token, timeoutMs: 30_000 });
  out.contentHash = preview.json?.data?.confirmation?.contentHash ?? null;
  const body = { planId, contentHash: out.contentHash, acknowledgeIssues: true };
  const c = await httpJson(`${base(PORT)}/api/agent/confirm`, { method: 'POST', token, body, timeoutMs: 120_000 });
  out.confirm = {
    status: c.status,
    ok: c.ok,
    code: c.json?.code,
    ms: c.ms,
    message: (c.json?.data?.message || c.json?.message || '').slice(0, 240),
    error: c.error,
  };
  out.tripId = c.json?.data?.tripId ?? null;
  return out;
}

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
  report.meta = {
    ...(report.meta || {}),
    generatedAt: new Date().toISOString(),
    node: process.version,
    jar: 'travel-agent-server-0.1.0.jar',
    instancePort: PORT,
    percentile: 'nearest-rank',
  };
  writeJson(POSTFIX, report);
}

async function main() {
  const startedAt = new Date().toISOString();
  const user = await registerTemp(PORT, 'amap');
  const token = user.token;
  const logCursor = logLineCursor(INSTANCE_LOG);

  // 规划前：只登录过，还没有任何真实路径调用
  const beforeSysResp = await httpJson(`${base(PORT)}/api/system/status`, { token, timeoutMs: 30_000 });
  const before = statusOf(beforeSysResp.json?.data);

  const flow = await planAndConfirm(token);

  const legsResp = flow.tripId
    ? await httpJson(`${base(PORT)}/api/trips/${flow.tripId}/route-legs`, { token, timeoutMs: 60_000 })
    : { status: 0, ok: false, json: null, text: 'no tripId', error: 'no tripId' };
  const legList = Array.isArray(legsResp.json?.data) ? legsResp.json.data : [];
  const sources = countBy(legList, (l) => l.source);
  const polylineLengths = legList.map((l) => (Array.isArray(l.polyline) ? l.polyline.length : null));
  const allPolylinesEmptyArray = legList.length > 0 && polylineLengths.every((n) => n === 0);
  const allEstimated = legList.length > 0 && legList.every((l) => l.source === 'estimated');

  // 规划/确认之后：真实路径调用已发生（失败）
  const afterSysResp = await httpJson(`${base(PORT)}/api/system/status`, { token, timeoutMs: 30_000 });
  const after = statusOf(afterSysResp.json?.data);

  const amapLogs = logSince(INSTANCE_LOG, logCursor, /高德路径调用失败|高德 REST 路径不可用|infocode=/).slice(0, 6);

  const checks = [
    { id: 'A1 route-legs 非空', expected: 'count > 0', actual: legList.length, pass: legList.length > 0 },
    { id: 'A2 全部 source=estimated', expected: 'every(source==="estimated")', actual: sources, pass: allEstimated },
    {
      id: 'A3 全部 polyline 为空数组',
      expected: 'every(Array.isArray && length===0)',
      actual: { polylineLengths, allPolylinesEmptyArray },
      pass: allPolylinesEmptyArray,
    },
    {
      id: 'A4 routeProvider=estimated',
      expected: 'estimated',
      actual: after.routeProvider,
      pass: after.routeProvider === 'estimated',
    },
    {
      id: 'A5 routeConfigured=true',
      expected: true,
      actual: after.routeConfigured,
      pass: after.routeConfigured === true,
    },
    {
      id: 'A6 amapVerified=false',
      expected: false,
      actual: after.amapVerified,
      pass: after.amapVerified === false,
    },
    {
      id: 'A7 routeLastError 含 infocode',
      expected: '/infocode=\\d+/',
      actual: after.routeLastError,
      pass: typeof after.routeLastError === 'string' && /infocode=\d+/.test(after.routeLastError),
    },
    {
      id: 'A8 routeHint 不宣称高德真实路径',
      expected: '不含「使用高德真实路径」',
      actual: after.routeHint,
      pass: typeof after.routeHint === 'string' && !after.routeHint.includes('使用高德真实路径'),
    },
    {
      id: 'A9 规划前就已自报 estimated',
      expected: 'estimated',
      actual: before.routeProvider,
      pass: before.routeProvider === 'estimated',
    },
    {
      id: 'A10 失败原因由真实调用写入',
      expected: 'before=null && after!=null',
      actual: { before: before.routeLastError, after: after.routeLastError },
      pass: before.routeLastError == null && typeof after.routeLastError === 'string' && after.routeLastError.length > 0,
    },
  ];

  /** 修复前基线（同一脚本口径）：从旧 resilience.json 快照里读取，不手填 */
  const old = loadJson(BASELINE)?.thirdParty?.amap_18100_invalid_key ?? null;
  const history = old
    ? {
        source: '.evidence/reports/resilience-prefix-baseline.json → thirdParty.amap_18100_invalid_key',
        oldRouteProvider: old.systemStatus?.routeProvider ?? null,
        oldRouteHint: old.systemStatus?.routeHint ?? null,
        oldLegSources: old.routeLegs?.sourceDistribution ?? null,
        oldLegCount: old.routeLegs?.count ?? null,
        oldVerdict: old.verdict ?? null,
      }
    : null;

  const answer = {
    question: '无效 Key 下状态页还会不会宣称使用高德真实路径？',
    beforeFix: history
      ? `会：routeProvider=${history.oldRouteProvider}、routeHint=「${history.oldRouteHint}」，而 ${history.oldLegCount} 段路线全部 source=estimated（自相矛盾）`
      : '（未找到修复前快照）',
    afterFix: `不会：routeProvider=${after.routeProvider}、amapVerified=${after.amapVerified}、routeHint=「${after.routeHint}」，与 route-legs（${legList.length} 段全 estimated、polyline 全空数组）一致`,
  };

  const verdict = checks.every((c) => c.pass) ? 'PASS' : 'FAIL';
  const result = {
    name: 'p2-amap-selfreport',
    startedAt,
    finishedAt: new Date().toISOString(),
    instance: `http://127.0.0.1:${PORT}（自建实例，独立 H2）`,
    amapKeyLabel: AMAP_LABEL,
    steps: { beforeStatus: beforeSysResp.status, plan: flow.plan, confirm: flow.confirm, tripId: flow.tripId, routeLegsStatus: legsResp.status },
    statusBeforeCall: before,
    statusAfterCall: after,
    routeLegs: {
      count: legList.length,
      sourceDistribution: sources,
      polylineLengths,
      allEstimated,
      allPolylinesEmptyArray,
      sample: legList.slice(0, 2),
    },
    amapFailureLogSample: amapLogs,
    checks,
    history,
    answer,
    verdict,
  };
  saveSection('p2SelfReport', result);

  console.log(
    JSON.stringify(
      {
        verdict,
        instance: result.instance,
        amapKeyLabel: AMAP_LABEL,
        planMs: flow.plan.ms,
        confirmMs: flow.confirm?.ms ?? null,
        tripId: flow.tripId,
        legs: { count: legList.length, sources, polylineLengths },
        statusBeforeCall: before,
        statusAfterCall: after,
        routeLastError: after.routeLastError,
        checks: checks.map((c) => ({ id: c.id, pass: c.pass, actual: c.actual })),
        answer,
      },
      null,
      2,
    ),
  );
  if (verdict !== 'PASS') process.exitCode = 2;
}

main().catch((e) => {
  console.error('FAILED', e);
  process.exit(1);
});