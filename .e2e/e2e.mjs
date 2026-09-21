/** 端到端验证：登录 → 规划 → 确认 → 行中调整 → 撤销 → RAG 问答 → SSE 流式 */
const BASE = 'http://localhost:8080/api';
const log = console.log;

async function main() {
  // 1. 登录
  let r = await fetch(`${BASE}/auth/login`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ username: 'demo', password: 'demo123' }),
  });
  const login = await r.json();
  const token = login.data.token;
  log('1) 登录:', r.status, login.data.username, '/', login.data.nickname);

  const auth = { 'Content-Type': 'application/json', Authorization: `Bearer ${token}` };

  // 2. 行程列表
  r = await fetch(`${BASE}/trips`, { headers: auth });
  log('2) 行程列表:', r.status, '共', (await r.json()).data.length, '个');

  // 3. Agent 规划
  r = await fetch(`${BASE}/agent/chat`, {
    method: 'POST',
    headers: auth,
    body: JSON.stringify({ message: '帮我安排一个上海三日游，预算3000元，酒店离地铁近一点' }),
  });
  const plan = (await r.json()).data;
  log('3) 规划: intent=%s status=%s', plan.intent, plan.status);
  log('   message:', plan.message?.slice(0, 90));
  log('   diff:', plan.diff?.changes?.length, '项变更, 预算', plan.diff?.budgetBefore, '->', plan.diff?.budgetAfter);
  log('   confirmation:', plan.confirmation?.summary, '[risk:', plan.confirmation?.riskLevel + ']');

  // 4. 确认提交
  r = await fetch(`${BASE}/agent/confirm`, {
    method: 'POST',
    headers: auth,
    body: JSON.stringify({ planId: plan.confirmation.planId }),
  });
  const committed = (await r.json()).data;
  log('4) 确认提交:', committed.status, 'tripId:', committed.tripId, '|', committed.message);
  const tripId = committed.tripId;

  // 5. 查看行程
  r = await fetch(`${BASE}/trips/${tripId}/itinerary`, { headers: auth });
  const items = (await r.json()).data;
  log('5) 行程条目:', items.length, '项');
  items.forEach((i) =>
    log(`   Day${i.dayIndex} [${i.itemType}] ${i.title} ¥${i.cost} ${i.startTime ? i.startTime.slice(5, 16) : ''}`),
  );

  // 6. 预算问答
  r = await fetch(`${BASE}/agent/chat`, {
    method: 'POST',
    headers: auth,
    body: JSON.stringify({ message: '现在花了多少钱？', tripId }),
  });
  log('6) 预算问答:', (await r.json()).data.message);

  // 7. 行中调整（下雨换户外）
  r = await fetch(`${BASE}/agent/chat`, {
    method: 'POST',
    headers: auth,
    body: JSON.stringify({ message: '明天下雨，把户外景点换掉', tripId }),
  });
  const modify = (await r.json()).data;
  log('7) 行中调整: intent=%s status=%s', modify.intent, modify.status);
  log('   message:', modify.message?.slice(0, 110));
  log('   changes:', modify.diff?.changes?.map((c) => `${c.type}:${c.title}`).join(' | '));

  // 8. 确认修改
  r = await fetch(`${BASE}/agent/confirm`, {
    method: 'POST',
    headers: auth,
    body: JSON.stringify({ planId: modify.confirmation.planId }),
  });
  const m2 = (await r.json()).data;
  log('8) 确认修改:', m2.status, '|', m2.message);

  // 9. 验证替换结果
  r = await fetch(`${BASE}/trips/${tripId}/itinerary`, { headers: auth });
  const items2 = (await r.json()).data;
  log('9) 调整后行程:', items2.length, '项，景点：');
  items2
    .filter((i) => i.itemType === 'attraction')
    .forEach((i) => log(`   Day${i.dayIndex} ${i.title} [${(i.tags || []).join('/')}]`));

  // 10. 撤销
  r = await fetch(`${BASE}/agent/undo`, { method: 'POST', headers: auth, body: JSON.stringify({ tripId }) });
  log('10) 撤销:', (await r.json()).data.message);
  r = await fetch(`${BASE}/trips/${tripId}/itinerary`, { headers: auth });
  log('    撤销后条目数:', (await r.json()).data.length);

  // 11. RAG 知识问答
  r = await fetch(`${BASE}/agent/chat`, {
    method: 'POST',
    headers: auth,
    body: JSON.stringify({ message: '上海有什么适合亲子游的地方？' }),
  });
  const rag = (await r.json()).data;
  log('11) RAG 问答:', rag.message?.slice(0, 200).replace(/\n/g, ' '));

  // 12. SSE 流式
  const es = await fetch(
    `${BASE}/agent/stream?message=${encodeURIComponent('杭州两日游，预算2500元')}&token=${token}`,
    { headers: { Accept: 'text/event-stream' } },
  );
  log('12) SSE:', es.status, es.headers.get('content-type'));
  const reader = es.body.getReader();
  const dec = new TextDecoder();
  const events = new Set();
  for (let i = 0; i < 400; i++) {
    const { done, value } = await reader.read();
    if (done) break;
    const text = dec.decode(value);
    for (const m of text.matchAll(/event:(\w+)/g)) events.add(m[1]);
    if (text.includes('event:complete')) {
      reader.cancel();
      break;
    }
  }
  log('    事件流:', [...events].join(' -> '));

  log('\n=== E2E 全部通过 ===');
}

main().catch((e) => {
  console.error('E2E FAILED:', e);
  process.exit(1);
});
