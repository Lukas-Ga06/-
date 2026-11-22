/** 验证：① 石家庄可正常规划 ② 不支持城市有明确提示 ③ LLM 降级是否正常 */
const BASE = 'http://localhost:8080/api';

const r = await fetch(BASE + '/auth/login', {
  method: 'POST',
  headers: { 'Content-Type': 'application/json' },
  body: JSON.stringify({ username: 'demo', password: 'demo123' }),
});
const token = (await r.json()).data.token;
const auth = { 'Content-Type': 'application/json', Authorization: 'Bearer ' + token };

async function chat(message, tripId) {
  const t = Date.now();
  const resp = await fetch(BASE + '/agent/chat', {
    method: 'POST',
    headers: auth,
    body: JSON.stringify({ message, tripId }),
  });
  const data = (await resp.json()).data;
  return { ms: Date.now() - t, data };
}

// 1. 石家庄三日游（用户原始需求）
let out = await chat('石家庄三日游，预算3000元，酒店离地铁近一点');
console.log('① 石家庄规划: intent=%s status=%s 耗时%dms', out.data.intent, out.data.status, out.ms);
console.log('   ', out.data.message?.slice(0, 100));
console.log('    confirmation:', out.data.confirmation?.summary);
if (out.data.status === 'NEED_CONFIRMATION') {
  const c = await fetch(BASE + '/agent/confirm', {
    method: 'POST',
    headers: auth,
    body: JSON.stringify({ planId: out.data.confirmation.planId }),
  });
  const committed = (await c.json()).data;
  console.log('    确认提交: tripId=%s | %s', committed.tripId, committed.message);
  const items = await (await fetch(BASE + '/trips/' + committed.tripId + '/itinerary', { headers: auth })).json();
  console.log('    行程:', items.data.map((i) => `Day${i.dayIndex} ${i.title}`).join(' | '));
}

// 2. 不支持的城市（洛阳）→ 应有明确提示而不是偷偷换成上海
out = await chat('洛阳三日游，预算2000元');
console.log('② 洛阳规划: status=%s 耗时%dms', out.data.status, out.ms);
console.log('   ', out.data.message?.slice(0, 120));

// 3. 知识问答（RAG + LLM 降级）
out = await chat('上海有什么适合亲子游的地方？');
console.log('③ 知识问答: intent=%s 耗时%dms', out.data.intent, out.ms);
console.log('   ', out.data.message?.slice(0, 80));
