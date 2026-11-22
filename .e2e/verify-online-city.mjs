/** 验证联网规划：目录外城市（洛阳）应触发 search_city_online 并生成真实行程 */
const BASE = 'http://localhost:8080/api';

const r = await fetch(BASE + '/auth/login', {
  method: 'POST',
  headers: { 'Content-Type': 'application/json' },
  body: JSON.stringify({ username: 'demo', password: 'demo123' }),
});
const token = (await r.json()).data.token;
const auth = { 'Content-Type': 'application/json', Authorization: 'Bearer ' + token };

// 走 SSE 流，观察 search_city_online 工具调用过程
const t0 = Date.now();
const res = await fetch(`${BASE}/agent/stream?message=${encodeURIComponent('洛阳三日游，预算3000元')}&token=${token}`);
console.log('SSE status:', res.status);
const text = await res.text();
const events = [...text.matchAll(/event:(\w+)/g)].map((m) => m[1]);
console.log('耗时:', Date.now() - t0, 'ms，事件数:', events.length);
console.log('事件序列（去重）:', [...new Set(events)].join(' -> '));

const completeMatch = text.match(/event:complete\ndata:(.+)\n/);
if (completeMatch) {
  const result = JSON.parse(completeMatch[1]);
  console.log('intent:', result.intent, '| status:', result.status);
  console.log('message:', result.message?.slice(0, 130));
  console.log('confirmation:', result.confirmation?.summary, '[risk:', result.confirmation?.riskLevel + ']');
  const changes = result.diff?.changes ?? [];
  const added = changes.filter((c) => c.type === 'ADD');
  console.log('新增安排（含坐标数）:');
  added.forEach((c) => console.log(`  Day${c.dayIndex} [${c.itemType}] ${c.title} ¥${c.cost ?? 0}`));
}

// 若需确认则提交，检查坐标落库
const planId = completeMatch && JSON.parse(completeMatch[1]).confirmation?.planId;
if (planId) {
  const c = await fetch(BASE + '/agent/confirm', { method: 'POST', headers: auth, body: JSON.stringify({ planId }) });
  const committed = (await c.json()).data;
  console.log('确认提交:', committed.status, 'tripId:', committed.tripId);
  const items = (await (await fetch(BASE + '/trips/' + committed.tripId + '/itinerary', { headers: auth })).json()).data;
  const withGeo = items.filter((i) => i.latitude != null).length;
  console.log('行程条目:', items.length, '项，含经纬度:', withGeo, '项');
  items.slice(0, 6).forEach((i) =>
    console.log(`  Day${i.dayIndex} ${i.title} (${i.latitude?.toFixed(4)}, ${i.longitude?.toFixed(4)})`));
}
