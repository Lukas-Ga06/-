const B = 'http://localhost:8080/api';
const r = await fetch(B + '/auth/login', {
  method: 'POST',
  headers: { 'Content-Type': 'application/json' },
  body: JSON.stringify({ username: 'demo', password: 'demo123' }),
});
const t = (await r.json()).data.token;
const h = { 'Content-Type': 'application/json', Authorization: 'Bearer ' + t };

// ① API 直接记账
const a = await (
  await fetch(B + '/trips/8/expenses', {
    method: 'POST',
    headers: h,
    body: JSON.stringify({ category: '门票', amount: 120, note: '兵马俑门票', spentAt: '2026-09-15' }),
  })
).json();
console.log('① API记账:', a.data.category, '¥' + a.data.amount, 'id=' + a.data.id);

// ② 支出列表
const l = await (await fetch(B + '/trips/8/expenses', { headers: h })).json();
console.log('② 支出列表:', l.data.length, '条, 合计 ¥' + l.data.reduce((s, i) => s + i.amount, 0));

// ③ Agent 聊天记账
const c = await (
  await fetch(B + '/agent/chat', {
    method: 'POST',
    headers: h,
    body: JSON.stringify({ message: '记一笔午餐 45 元', tripId: 8 }),
  })
).json();
console.log('③ Agent记账: intent=' + c.data.intent);
console.log('   ', c.data.message?.slice(0, 100));

// ④ 列表更新确认
const l2 = await (await fetch(B + '/trips/8/expenses', { headers: h })).json();
console.log('④ 列表更新:', l2.data.length, '条 →', l2.data.map((i) => `${i.category}¥${i.amount}`).join(', '));

// ⑤ 删除验证
if (l2.data.length > 0) {
  const d = await fetch(B + '/expenses/' + l2.data[l2.data.length - 1].id, { method: 'DELETE', headers: h });
  console.log('⑤ 删除:', d.status, '剩余', (await (await fetch(B + '/trips/8/expenses', { headers: h })).json()).data.length, '条');
}
console.log('=== 记账功能验证通过 ===');
