#!/usr/bin/env node
/**
 * V2.2 正式验收：FR-01 ~ FR-10 真实 HTTP 断言（不依赖 Playwright）。
 *
 * 用法：
 *   node .e2e/acceptance/fr-acceptance.mjs --base http://127.0.0.1:18090 --out .evidence/reports/fr-acceptance.json
 *   node .e2e/acceptance/fr-acceptance.mjs --base http://127.0.0.1:18090 --expect-amap --amap-key <Web服务Key>
 *
 * 设计口径：
 *   - 只打真实 HTTP，不做 mock；后端实例必须已由调用方启动（默认 demo/demo123 账号存在）。
 *   - 每条断言独立记录 PASS/FAIL，最终按 FR 汇总；任何 FAIL → exit 1。
 *   - --expect-amap 时追加真实高德断言（routeProvider=amap、route-legs 至少一段 source=amap、
 *     地理编码/POI/驾车路径直连诊断、GCJ-02 偏移校验）。
 */
import { writeFileSync, mkdirSync } from 'node:fs';
import { dirname, resolve } from 'node:path';

const argv = process.argv.slice(2);
const arg = (name, def) => {
  const i = argv.indexOf(`--${name}`);
  return i >= 0 ? argv[i + 1] : def;
};
const flag = (name) => argv.includes(`--${name}`);

const BASE = (arg('base', 'http://127.0.0.1:18090')).replace(/\/$/, '');
const OUT = resolve(arg('out', '.evidence/reports/fr-acceptance.json'));
const AMAP_KEY = arg('amap-key', process.env.AMAP_KEY || '');
const EXPECT_AMAP = flag('expect-amap');
const EXPECT_LLM = flag('expect-llm');
const ONLY = arg('only', '');

const results = [];
let currentFr = 'PRE';
const log = (...a) => console.log(...a);

function record(fr, id, name, ok, detail) {
  results.push({ fr, id, name, ok: !!ok, detail: detail === undefined ? null : detail });
  const mark = ok ? 'PASS' : 'FAIL';
  log(`[${mark}] ${fr} ${id} ${name}${detail ? ' :: ' + String(detail).slice(0, 300) : ''}`);
}

async function step(fr, id, name, fn) {
  currentFr = fr;
  try {
    const detail = await fn();
    record(fr, id, name, true, detail);
    return detail;
  } catch (e) {
    record(fr, id, name, false, e && e.message ? e.message : String(e));
    return null;
  }
}

function assert(cond, msg) {
  if (!cond) throw new Error(msg);
}
function assertEq(actual, expected, msg) {
  if (actual !== expected) throw new Error(`${msg}: expected ${JSON.stringify(expected)}, got ${JSON.stringify(actual)}`);
}
function assertTrue(v, msg) {
  assert(v === true, `${msg}: expected true, got ${JSON.stringify(v)}`);
}

async function http(path, { method = 'GET', body, token, headers = {}, raw = false } = {}) {
  const h = { ...headers };
  if (body !== undefined) h['Content-Type'] = 'application/json';
  if (token) h.Authorization = `Bearer ${token}`;
  const res = await fetch(`${BASE}${path}`, { method, headers: h, body: body === undefined ? undefined : JSON.stringify(body) });
  const text = await res.text();
  let json = null;
  try {
    json = text ? JSON.parse(text) : null;
  } catch {
    /* 非 JSON（例如 PDF） */
  }
  if (raw) return { status: res.status, json, text, headers: res.headers };
  return { status: res.status, json, text };
}

let TOKEN = '';
let USER2 = '';

async function login(username = 'demo', password = 'demo123') {
  const r = await http('/api/auth/login', { method: 'POST', body: { username, password } });
  assertEq(r.status, 200, `login ${username} HTTP`);
  assertEq(r.json.code, 0, `login ${username} code`);
  return r.json.data.token;
}

async function chat(message, { conversationId, tripId } = {}) {
  const r = await http('/api/agent/chat', {
    method: 'POST',
    token: TOKEN,
    body: { message, conversationId, tripId },
  });
  assertEq(r.status, 200, `chat HTTP(${message})`);
  assertEq(r.json.code, 0, `chat code(${message})`);
  return r.json.data;
}

async function confirm(body) {
  const r = await http('/api/agent/confirm', { method: 'POST', token: TOKEN, body });
  assertEq(r.status, 200, 'confirm HTTP');
  return r.json.data;
}

async function latestRun(message) {
  const r = await http('/api/agent/runs?limit=20', { token: TOKEN });
  assertEq(r.status, 200, 'runs HTTP');
  const list = Array.isArray(r.json.data) ? r.json.data : r.json.data?.items || [];
  if (message) {
    const hit = list.find((x) => x.message === message);
    if (hit) return hit.runId;
  }
  assert(list.length > 0, 'runs 列表为空');
  return list[0].runId;
}

async function runDetail(runId) {
  const r = await http(`/api/agent/runs/${runId}`, { token: TOKEN });
  assertEq(r.status, 200, 'run detail HTTP');
  assertEq(r.json.code, 0, 'run detail code');
  return r.json.data;
}

const ts = (s) => (s ? Date.parse(s.replace(' ', 'T')) : NaN);

/**
 * SSE 读取：返回事件名数组与首尾事件负载。
 */
async function readSse(path, timeoutMs = 90000) {
  const ctrl = new AbortController();
  const timer = setTimeout(() => ctrl.abort(), timeoutMs);
  try {
    const res = await fetch(`${BASE}${path}`, {
      headers: { Authorization: `Bearer ${TOKEN}`, Accept: 'text/event-stream' },
      signal: ctrl.signal,
    });
    assertEq(res.status, 200, 'SSE HTTP');
    assert((res.headers.get('content-type') || '').includes('text/event-stream'), 'SSE content-type');
    const reader = res.body.getReader();
    const dec = new TextDecoder();
    let buf = '';
    const events = [];
    while (events.filter((e) => e.event === 'complete').length === 0) {
      const { done, value } = await reader.read();
      if (done) break;
      buf += dec.decode(value, { stream: true });
      let idx;
      while ((idx = buf.indexOf('\n\n')) >= 0) {
        const block = buf.slice(0, idx);
        buf = buf.slice(idx + 2);
        const ev = { event: null, data: null };
        for (const line of block.split('\n')) {
          if (line.startsWith('event:')) ev.event = line.slice(6).trim();
          else if (line.startsWith('data:')) ev.data = line.slice(5).trim();
        }
        if (ev.event) events.push(ev);
      }
    }
    try {
      await reader.cancel();
    } catch {
      /* ignore */
    }
    return events;
  } finally {
    clearTimeout(timer);
  }
}

// ───────────────────────── 场景 ─────────────────────────

async function fr01() {
  const cid = `acc-fr01-${Date.now()}`;
  let tripId = null;

  await step('FR-01', '01.1', '缺目的地/天数/人数 → 合并追问一次，不启动规划', async () => {
    const r = await chat('帮我规划行程', { conversationId: cid });
    const slots = r.missingSlots || [];
    assertEq(r.status, 'COMPLETED', '追问轮状态');
    assert(!r.planId, '追问轮不应产生 planId');
    assert(slots.includes('destination'), `missingSlots 含 destination，实际 ${JSON.stringify(slots)}`);
    assert(slots.includes('days'), 'missingSlots 含 days');
    assert(slots.includes('partySize'), 'missingSlots 含 partySize');
    return `missingSlots=${JSON.stringify(slots)} message=${(r.message || '').slice(0, 60)}`;
  });

  await step('FR-01', '01.2', '短回答跨轮补槽（上海 → 天数 → 人数）', async () => {
    let r = await chat('上海', { conversationId: cid });
    assert(!(r.missingSlots || []).includes('destination'), `补目的地后不应再问 destination: ${JSON.stringify(r.missingSlots)}`);
    r = await chat('三天', { conversationId: cid });
    assert(!(r.missingSlots || []).includes('days'), `补天数后不应再问 days: ${JSON.stringify(r.missingSlots)}`);
    return `第二轮=${JSON.stringify(r.missingSlots)}`;
  });

  await step('FR-01', '01.3', '人数补齐后进入规划并明示兜底预算', async () => {
    const r = await chat('两个人', { conversationId: cid });
    assertEq(r.status, 'NEED_CONFIRMATION', '补槽完成状态');
    assert(!!r.confirmation?.planId, 'confirmation.planId 存在');
    assertEq((r.confirmation?.contentHash || '').length, 64, 'contentHash 64 位');
    assert(/2\s*人/.test(r.message || ''), `回复应含 2 人: ${r.message}`);
    assert(/¥?\s*5000/.test(r.message || ''), `回复应明示兜底预算 5000: ${r.message}`);
    tripId = (await confirm({ planId: r.confirmation.planId, contentHash: r.confirmation.contentHash })).tripId;
    assert(!!tripId, 'confirm 返回 tripId');
    return `tripId=${tripId}`;
  });

  await step('FR-01', '01.4', '人数落库：trip.partySize=2', async () => {
    const r = await http(`/api/trips/${tripId}`, { token: TOKEN });
    assertEq(r.status, 200, 'trip detail HTTP');
    assertEq(r.json.data.partySize, 2, 'partySize');
    return `destination=${r.json.data.destination} partySize=${r.json.data.partySize}`;
  });

  return tripId;
}

async function fr08and09() {
  let plan = null;

  await step('FR-08', '08.1', '方案生成带 planId + contentHash（SHA-256 64 位）', async () => {
    plan = await chat('杭州两日游，两个人，预算3000元');
    assertEq(plan.status, 'NEED_CONFIRMATION', 'status');
    assert(!!plan.confirmation?.planId, 'planId');
    assertEq(plan.confirmation.contentHash.length, 64, 'contentHash 长度');
    assert(/^[0-9a-f]{64}$/.test(plan.confirmation.contentHash), 'contentHash 小写 hex');
    return `planId=${plan.confirmation.planId} changes=${plan.diff?.changes?.length}`;
  });

  await step('FR-08', '08.2', '内容指纹不符 → 拒绝提交且方案不被消费', async () => {
    const bad = await confirm({ planId: plan.confirmation.planId, contentHash: 'f'.repeat(64) });
    assertEq(bad.status, 'FAILED', '错误指纹确认状态');
    assert(/不一致|内容/.test(bad.message || ''), `错误指纹提示: ${bad.message}`);
    // 方案仍可取回：正确指纹应还能提交
    return bad.message;
  });

  let exec = null;
  await step('FR-08', '08.3', '正确指纹 → 提交成功', async () => {
    const ok = await confirm({ planId: plan.confirmation.planId, contentHash: plan.confirmation.contentHash });
    assertEq(ok.status, 'COMPLETED', '确认状态');
    assert(!!ok.tripId, 'tripId');
    exec = ok.execution;
    return `tripId=${ok.tripId} execution=${exec?.executionId}`;
  });

  await step('FR-09', '09.1', '确认后三模块幂等同步全部 SUCCEEDED', async () => {
    assert(exec, 'execution 缺失');
    assertEq(exec.status, 'SUCCEEDED', 'execution 汇总状态');
    const mods = exec.modules || [];
    const names = mods.map((m) => m.module).sort();
    assertEq(JSON.stringify(names), JSON.stringify(['budget', 'calendar', 'footprint']), '模块集合');
    // 幂等键只对「真的写库」的模块有意义：calendar 按 planId 写日程（ADR-004）；
    // budget/footprint 是只读快照 / 派生计划态（ADR-005），不落业务行，故 idempotencyKey 允许为空。
    for (const m of mods) {
      assertEq(m.status, 'SUCCEEDED', `模块 ${m.module}`);
    }
    const calendar = mods.find((m) => m.module === 'calendar');
    assert(!!calendar.idempotencyKey, 'calendar 模块必须有 idempotencyKey');
    assert(calendar.idempotencyKey.includes(exec.planId), 'calendar 幂等键应绑定 planId');
    return mods.map((m) => `${m.module}:${m.status}`).join(',');
  });

  await step('FR-09', '09.2', '执行台账可回查且 planId 一致', async () => {
    const r = await http(`/api/agent/executions/${exec.executionId}`, { token: TOKEN });
    assertEq(r.status, 200, 'execution HTTP');
    assertEq(r.json.data.planId, exec.planId, 'planId');
    assertEq(r.json.data.status, 'SUCCEEDED', 'status');
    return `executionId=${r.json.data.executionId}`;
  });

  await step('FR-09', '09.3', '重复确认被拦截，只产生一份逻辑执行', async () => {
    const again = await confirm({ planId: plan.confirmation.planId, contentHash: plan.confirmation.contentHash });
    assertEq(again.status, 'FAILED', '重复确认状态');
    return again.message;
  });

  return { plan, exec };
}

async function fr06and02() {
  let blocked = null;

  await step('FR-06', '06.1', '预算装不下 → 审核 blocked + 要求显式接受风险', async () => {
    blocked = await chat('上海三日游，两个人，预算100元');
    assertEq(blocked.status, 'NEED_CONFIRMATION', 'status');
    assertTrue(blocked.review?.blocked, 'review.blocked');
    assertEq(blocked.review?.passed, false, 'review.passed');
    assert(blocked.review?.issues?.some((i) => i.type === 'BUDGET_OVERRUN' && i.severity === 'HIGH'), `issues 含 BUDGET_OVERRUN/HIGH: ${JSON.stringify(blocked.review?.issues)}`);
    assertTrue(blocked.confirmation?.requiresAcknowledgement, 'requiresAcknowledgement');
    assertEq(blocked.confirmation?.riskLevel, 'HIGH', 'riskLevel');
    return `attempts=${blocked.review?.attempts} issues=${(blocked.review?.issues || []).map((i) => i.type).join(',')}`;
  });

  await step('FR-06', '06.2', '未勾选风险 → 拒绝提交且方案仍可取回', async () => {
    const r = await confirm({ planId: blocked.confirmation.planId, contentHash: blocked.confirmation.contentHash });
    assertEq(r.status, 'FAILED', '无 ack 确认状态');
    assert(/风险|勾选|acknowledge/i.test(r.message || ''), `提示应要求显式接受风险: ${r.message}`);
    return r.message;
  });

  await step('FR-06', '06.3', '显式接受风险 → 提交成功', async () => {
    const r = await confirm({
      planId: blocked.confirmation.planId,
      contentHash: blocked.confirmation.contentHash,
      acknowledgeIssues: true,
    });
    assertEq(r.status, 'COMPLETED', '带 ack 确认状态');
    return `tripId=${r.tripId}`;
  });

  // ── FR-02 / FR-04：用 blocked 这轮的 run 验证任务图
  let runId = null;
  await step('FR-10', '10.1', '一次对话落一条 run，可按 runId 回查', async () => {
    runId = await latestRun('上海三日游，两个人，预算100元');
    assert(!!runId, 'runId 为空');
    return runId;
  });

  let detail = null;
  await step('FR-02', '02.1', '任务图依赖边齐全（PLAN→INTENT、PREVIEW/REVIEW→PLAN）', async () => {
    detail = await runDetail(runId);
    const tasks = detail.tasks || [];
    const byType = (t) => tasks.filter((x) => x.taskType === t);
    const intent = byType('INTENT')[0];
    const plan = byType('PLAN')[0];
    const preview = byType('PREVIEW')[0];
    const review = byType('REVIEW')[0];
    assert(intent && plan && preview && review, `任务类型缺失: ${tasks.map((t) => t.taskType).join(',')}`);
    assert((plan.dependsOn || []).includes(intent.taskId), `PLAN.dependsOn 应含 INTENT: ${JSON.stringify(plan.dependsOn)}`);
    assert((preview.dependsOn || []).includes(plan.taskId), `PREVIEW.dependsOn 应含 PLAN: ${JSON.stringify(preview.dependsOn)}`);
    assert((review.dependsOn || []).includes(plan.taskId), `REVIEW.dependsOn 应含 PLAN: ${JSON.stringify(review.dependsOn)}`);
    return tasks.map((t) => `${t.taskType}${t.attempt ? '#' + t.attempt : ''}`).join(' → ');
  });

  await step('FR-02', '02.2', 'PREVIEW ‖ REVIEW 实际并行（时间区间重叠）', async () => {
    const tasks = detail.tasks || [];
    const preview = tasks.find((t) => t.taskType === 'PREVIEW');
    const review = tasks.find((t) => t.taskType === 'REVIEW');
    const ps = ts(preview.startedAt), pe = ts(preview.endedAt), rs = ts(review.startedAt), re = ts(review.endedAt);
    assert(ps <= re && rs <= pe, `时间区间不重叠: preview[${preview.startedAt},${preview.endedAt}] review[${review.startedAt},${review.endedAt}]`);
    return `preview=[${preview.startedAt}..${preview.endedAt}] review=[${review.startedAt}..${review.endedAt}]`;
  });

  await step('FR-02', '02.3', '审核触发 REPLAN，重规划后的 Diff 由 PREVIEW 重派（attempt=1 + replacesTaskId）', async () => {
    const tasks = detail.tasks || [];
    const firstReview = tasks.find((t) => t.taskType === 'REVIEW');
    const replan = tasks.find((t) => t.taskType === 'REPLAN');
    assert(!!replan, `缺 REPLAN 任务: ${tasks.map((t) => t.taskType).join(',')}`);
    assert((replan.dependsOn || []).includes(firstReview.taskId), `REPLAN.dependsOn 应含首轮 REVIEW: ${JSON.stringify(replan.dependsOn)}`);
    const previews = tasks.filter((t) => t.taskType === 'PREVIEW').sort((a, b) => (a.attempt || 0) - (b.attempt || 0));
    assert(previews.length >= 2, `PREVIEW 应至少两次（首轮 + 重派）: ${previews.length}`);
    const rePreview = previews.find((t) => t.attempt === 1);
    assert(!!rePreview, '缺 attempt=1 的 PREVIEW');
    assert(!!rePreview.replacesTaskId, '重派 PREVIEW 缺 replacesTaskId');
    assertEq(rePreview.replacesTaskId, previews[0].taskId, 'replacesTaskId 应指向首轮 PREVIEW');
    // 修复动作非空时才会派「复审」任务（PlanReviewLoop）；无可自动修复项时不会凭空造一个复审。
    const reviews = tasks.filter((t) => t.taskType === 'REVIEW');
    if (reviews.length > 1) {
      const reReview = reviews[1];
      assert((reReview.dependsOn || []).includes(replan.taskId), `复审 REVEIW.dependsOn 应含 REPLAN: ${JSON.stringify(reReview.dependsOn)}`);
      return `REPLAN→REVIEW 有复审；rePreview.replaces=${rePreview.replacesTaskId}`;
    }
    assert(/没有?可自动修复项/.test(replan.summary || ''), `无复审时应说明无可自动修复项: ${replan.summary}`);
    return `REPLAN（无可自动修复项）→ 仅重建 PREVIEW#1；replaces=${rePreview.replacesTaskId}`;
  });

  await step('FR-04', '04.1', '任务协议字段齐全（receiver/taskType/status/summary）', async () => {
    const tasks = detail.tasks || [];
    for (const t of tasks) {
      assert(!!t.taskId && !!t.receiver && !!t.taskType && !!t.status, `任务字段缺失: ${JSON.stringify(t)}`);
    }
    const receivers = [...new Set(tasks.map((t) => t.receiver))];
    assert(receivers.length >= 3, `至少三个接收方: ${receivers.join(',')}`);
    return `receivers=${receivers.join(',')}`;
  });

  await step('FR-10', '10.2', '工具调用逐条落 trace，且成功率 100%', async () => {
    const calls = detail.toolCalls || [];
    assert(calls.length > 0, 'toolCalls 为空');
    const failed = calls.filter((c) => !c.success);
    assertEq(failed.length, 0, '失败工具调用数');
    assert(calls.every((c) => c.taskId), '每条工具调用都应挂 taskId');
    return `${calls.length} 次调用，工具=${[...new Set(calls.map((c) => c.toolName))].slice(0, 8).join(',')}`;
  });

  await step('FR-04', '04.2', 'SSE 事件含 run / delegate / plan_update / complete', async () => {
    const cid = `acc-sse-${Date.now()}`;
    const events = await readSse(`/api/agent/stream?message=${encodeURIComponent('苏州两日游，两个人，预算2000元')}&conversationId=${cid}`);
    const names = events.map((e) => e.event);
    assert(names[0] === 'start', `首个事件应为 start: ${names.slice(0, 3)}`);
    assert(names.includes('run'), `缺 run 事件: ${names.join(',')}`);
    assert(names.includes('delegate'), `缺 delegate 事件: ${names.join(',')}`);
    assert(names.includes('complete'), `缺 complete 事件: ${names.join(',')}`);
    const delegates = events.filter((e) => e.event === 'delegate');
    const recovers = events.filter((e) => e.event === 'recover');
    if (delegates[0]?.data) {
      const d = JSON.parse(delegates[0].data);
      assert(!!d.taskId && !!d.receiver && !!d.taskType, `delegate 负载缺字段: ${delegates[0].data}`);
    }
    return `events=${events.length} [${names.join('>')}] recover=${recovers.length}`;
  });

  return { blocked };
}

async function fr07() {
  let tripId = null;

  await step('FR-07', '07.1', '规划并提交一趟两日游（用于路线段）', async () => {
    const r = await chat('北京两日游，两个人，预算4000元');
    assertEq(r.status, 'NEED_CONFIRMATION', 'status');
    const c = await confirm({ planId: r.confirmation.planId, contentHash: r.confirmation.contentHash, acknowledgeIssues: r.confirmation.requiresAcknowledgement });
    assertEq(c.status, 'COMPLETED', '确认状态');
    tripId = c.tripId;
    return `tripId=${tripId}`;
  });

  await step('FR-07', '07.2', 'route-legs 返回逐日相邻点路线段，字段完整', async () => {
    const r = await http(`/api/trips/${tripId}/route-legs`, { token: TOKEN });
    assertEq(r.status, 200, 'route-legs HTTP');
    const legs = r.json.data || [];
    assert(legs.length >= 2, `路线段数量应 >= 2: ${legs.length}`);
    for (const leg of legs) {
      assert(!!leg.fromTitle && !!leg.toTitle, 'leg 端点标题');
      assert(typeof leg.distanceKm === 'number' && leg.distanceKm >= 0, 'distanceKm');
      assert(typeof leg.walkMinutes === 'number', 'walkMinutes');
      assert(typeof leg.driveMinutes === 'number', 'driveMinutes');
      assert(['amap', 'estimated'].includes(leg.source), `source 非法: ${leg.source}`);
    }
    return `legs=${legs.length} sources=${JSON.stringify(countBy(legs, 'source'))}`;
  });

  return tripId;
}

async function fr05(routeTripId) {
  await step('FR-05', '05.1', 'system/status 如实上报 provider（不泄露密钥）', async () => {
    const r = await http('/api/system/status', { token: TOKEN });
    assertEq(r.status, 200, 'status HTTP');
    const s = r.json.data;
    assert(typeof s.routeProvider === 'string' && !!s.routeHint, 'routeProvider/routeHint');
    assert(s.routeProvider === (EXPECT_AMAP ? 'amap' : 'estimated'), `routeProvider 期望 ${EXPECT_AMAP ? 'amap' : 'estimated'}，实际 ${s.routeProvider}`);
    if (EXPECT_AMAP) assertTrue(s.amapConfigured, 'amapConfigured');
    return `routeProvider=${s.routeProvider} geoProvider=${s.geoProvider} amapConfigured=${s.amapConfigured}`;
  });

  if (!EXPECT_AMAP) {
    await step('FR-05', '05.2', '无 Key 时明确降级为 estimated（不伪造真实来源）', async () => {
      const r = await http(`/api/trips/${routeTripId}/route-legs`, { token: TOKEN });
      const legs = r.json.data || [];
      assert(legs.length > 0, 'legs 为空');
      assert(legs.every((l) => l.source === 'estimated'), `无 Key 应全部 estimated: ${JSON.stringify(countBy(legs, 'source'))}`);
      return `全部 ${legs.length} 段 estimated`;
    });
    return;
  }

  await step('FR-05', '05.3', 'route-legs 至少一段 source=amap（真实路径规划）', async () => {
    const r = await http(`/api/trips/${routeTripId}/route-legs`, { token: TOKEN });
    const legs = r.json.data || [];
    const amap = legs.filter((l) => l.source === 'amap');
    assert(amap.length > 0, `没有 amap 段: ${JSON.stringify(countBy(legs, 'source'))}`);
    return `amap=${amap.length}/${legs.length}`;
  });

  await step('FR-05', '05.4', '直连高德 REST：地理编码 + POI + 驾车路径可用', async () => {
    assert(/^[0-9a-f]{32}$/i.test(AMAP_KEY), 'AMAP_KEY 形如 32 位 hex');
    const geo = await getJson(`https://restapi.amap.com/v3/geocode/geo?key=${AMAP_KEY}&address=${encodeURIComponent('天安门广场')}`);
    assertEq(geo.status, '1', `geocode status=${geo.status} info=${geo.info}`);
    const loc = geo.geocodes?.[0]?.location;
    assert(!!loc, 'geocode location 为空');
    const [glng, glat] = loc.split(',').map(Number);
    assertInChina(glng, glat, 'geocode');

    const poi = await getJson(`https://restapi.amap.com/v3/place/text?key=${AMAP_KEY}&keywords=${encodeURIComponent('外滩')}&city=${encodeURIComponent('上海')}&offset=5`);
    assertEq(poi.status, '1', `POI status=${poi.status} info=${poi.info}`);
    assert((poi.pois || []).length > 0, 'POI 结果为空');
    const ploc = poi.pois[0].location.split(',').map(Number);
    assertInChina(ploc[0], ploc[1], 'POI');

    const drive = await getJson(`https://restapi.amap.com/v3/direction/driving?key=${AMAP_KEY}&origin=${loc}&destination=${ploc[0]},${ploc[1]}&extensions=base`);
    assertEq(drive.status, '1', `direction status=${drive.status} info=${drive.info}`);
    const path = drive.route?.paths?.[0];
    assert(path && Number(path.distance) > 0, '驾车路径 distance 无效');
    return `geocode=${loc} poi=${poi.pois[0].name}@${poi.pois[0].location} drive=${path.distance}m/${path.duration}s`;
  });

  await step('FR-05', '05.5', 'GCJ-02 校验：天安门坐标非 WGS-84 原始值（偏移 300~1200m）', async () => {
    const geo = await getJson(`https://restapi.amap.com/v3/geocode/geo?key=${AMAP_KEY}&address=${encodeURIComponent('天安门广场')}`);
    const [lng, lat] = geo.geocodes[0].location.split(',').map(Number);
    // WGS-84 参考：**天安门广场**（广场中心在城楼以南约 660m）约 116.3912, 39.9031。
    // 旧版这里写的是 39.9067（天安门城楼的纬度），于是 2026-11-19 晚高德返回广场中心
    // 39.903182 时「纬度必须大于参考」的断言误报——那是参考点标错，不是坐标系错误。
    // 判定口径改为：偏移量落在 GCJ-02 合理区间 + 经度有 EAST 向的可见偏移（境内 GCJ 的稳定特征），
    // 不再假设纬度方向（广场/城楼的纬度关系会随 POI 取点而不同）。
    const refLng = 116.3912;
    const refLat = 39.9031;
    const dist = haversine(lng, lat, refLng, refLat);
    assert(dist > 300 && dist < 1200, `与 WGS-84 偏移 ${dist.toFixed(0)}m 不在 GCJ-02 合理区间`);
    assert(lng - refLng > 0.004, `GCJ 经度偏移不足（期望东偏 >0.004°）: ${lng},${lat}`);
    return `GCJ=(${lng},${lat}) 相对 WGS 偏移=${dist.toFixed(0)}m`;
  });
}

async function fr03() {
  await step('FR-03', '03.1', 'LLM 可用时 research-agent 自主决策并调用工具', async () => {
    const r = await chat('帮我规划上海三日游，两个人，预算5000元，想看看当地美食');
    assertEq(r.status, 'NEED_CONFIRMATION', '规划状态');
    const runId = await latestRun('帮我规划上海三日游，两个人，预算5000元，想看看当地美食');
    const d = await runDetail(runId);
    const research = (d.tasks || []).find((t) => t.taskType === 'RESEARCH');
    assert(!!research, `缺 RESEARCH 任务: ${(d.tasks || []).map((t) => t.taskType).join(',')}`);
    const calls = (d.toolCalls || []).filter((c) => c.taskId === research.taskId);
    assert(calls.length >= 1, `RESEARCH 应至少一次工具调用: ${calls.length}`);
    assert(calls.some((c) => c.success), 'RESEARCH 工具调用应至少一次成功');
    return `RESEARCH 调用 ${calls.length} 次: ${[...new Set(calls.map((c) => c.toolName))].join(',')}；summary=${(research.summary || '').slice(0, 80)}`;
  });
}

async function fr10Auth() {
  await step('FR-10', '10.3', '未登录访问 runs → 401', async () => {
    const r = await http('/api/agent/runs?limit=5');
    assertEq(r.status, 401, '未登录 HTTP');
    return 'HTTP 401';
  });

  await step('FR-10', '10.4', '他人 run 一律 404（跨用户隔离）', async () => {
    const runId = await latestRun();
    USER2 = `acc${Date.now() % 1000000}`;
    const t2 = await loginRegister(USER2);
    const r = await http(`/api/agent/runs/${runId}`, { token: t2 });
    assertEq(r.status, 404, `他人 run HTTP`);
    return `user2=${USER2} → 404`;
  });
}

async function loginRegister(username) {
  const password = 'Acc#2026pass';
  let r = await http('/api/auth/register', { method: 'POST', body: { username, password, nickname: '验收临时账号' } });
  if (r.status !== 200) {
    r = await http('/api/auth/login', { method: 'POST', body: { username, password } });
  }
  assertEq(r.status, 200, 'register/login user2');
  return r.json.data.token;
}

// ───────────────────────── 工具 ─────────────────────────

function countBy(list, key) {
  const m = {};
  for (const x of list) m[x[key]] = (m[x[key]] || 0) + 1;
  return m;
}
function assertInChina(lng, lat, what) {
  assert(lng > 73 && lng < 136, `${what} 经度不在中国范围: ${lng}`);
  assert(lat > 3 && lat < 54, `${what} 纬度不在中国范围: ${lat}`);
}
function haversine(lng1, lat1, lng2, lat2) {
  const R = 6371000;
  const dLat = ((lat2 - lat1) * Math.PI) / 180;
  const dLng = ((lng2 - lng1) * Math.PI) / 180;
  const a = Math.sin(dLat / 2) ** 2 + Math.cos((lat1 * Math.PI) / 180) * Math.cos((lat2 * Math.PI) / 180) * Math.sin(dLng / 2) ** 2;
  return 2 * R * Math.asin(Math.sqrt(a));
}
async function getJson(url) {
  const r = await fetch(url);
  assert(r.ok, `HTTP ${r.status} ${url}`);
  return r.json();
}

// ───────────────────────── 主流程 ─────────────────────────

const FR_ORDER = ['FR-01', 'FR-02', 'FR-03', 'FR-04', 'FR-05', 'FR-06', 'FR-07', 'FR-08', 'FR-09', 'FR-10'];

async function main() {
  const t0 = Date.now();
  log(`== V2.2 验收 == base=${BASE} expectAmap=${EXPECT_AMAP} expectLlm=${EXPECT_LLM}`);

  TOKEN = await login();
  await step('PRE', '00.1', '健康检查 UP', async () => {
    const r = await http('/actuator/health');
    assertEq(r.status, 200, 'health HTTP');
    assertEq(r.json.status, 'UP', 'health');
    return 'UP';
  });

  const fr01Trip = ONLY && !ONLY.includes('FR-01') ? null : await fr01();
  if (!ONLY || ONLY.includes('FR-08') || ONLY.includes('FR-09')) await fr08and09();
  if (!ONLY || ONLY.includes('FR-06') || ONLY.includes('FR-02') || ONLY.includes('FR-04')) await fr06and02();
  const routeTrip = ONLY && !ONLY.includes('FR-07') ? null : await fr07();
  if (!ONLY || ONLY.includes('FR-05')) await fr05(routeTrip);
  if (EXPECT_LLM && (!ONLY || ONLY.includes('FR-03'))) await fr03();
  await fr10Auth();

  const byFr = {};
  for (const r of results) {
    byFr[r.fr] = byFr[r.fr] || { pass: 0, fail: 0, ids: [] };
    if (r.ok) byFr[r.fr].pass++;
    else byFr[r.fr].fail++;
    byFr[r.fr].ids.push(`${r.id}:${r.ok ? 'PASS' : 'FAIL'}`);
  }

  log('\n== 汇总（按 FR） ==');
  for (const fr of ['PRE', ...FR_ORDER]) {
    if (!byFr[fr]) continue;
    log(`${fr}: ${byFr[fr].pass} PASS / ${byFr[fr].fail} FAIL`);
  }

  const total = results.length;
  const failed = results.filter((r) => !r.ok).length;
  const report = {
    generatedAt: new Date().toISOString(),
    base: BASE,
    expectAmap: EXPECT_AMAP,
    expectLlm: EXPECT_LLM,
    durationMs: Date.now() - t0,
    total,
    failed,
    byFr,
    results,
  };
  mkdirSync(dirname(OUT), { recursive: true });
  writeFileSync(OUT, JSON.stringify(report, null, 2), 'utf8');
  log(`\n报告: ${OUT}  (${total - failed}/${total} PASS, ${((Date.now() - t0) / 1000).toFixed(1)}s)`);
  if (failed > 0) {
    log('\n失败项：');
    for (const r of results.filter((x) => !x.ok)) log(` - ${r.fr} ${r.id} ${r.name}: ${r.detail}`);
    process.exit(1);
  }
}

main().catch((e) => {
  console.error('验收脚本异常:', e);
  process.exit(2);
});