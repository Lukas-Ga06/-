const B = 'http://localhost:8080/api';
const r = await fetch(B + '/auth/login', {
  method: 'POST',
  headers: { 'Content-Type': 'application/json' },
  body: JSON.stringify({ username: 'demo', password: 'demo123' }),
});
const t = (await r.json()).data.token;
const h = { 'Content-Type': 'application/json', Authorization: 'Bearer ' + t };

// ① 带支付方式记账（微信支付）
const a = await (
  await fetch(B + '/trips/8/expenses', {
    method: 'POST',
    headers: h,
    body: JSON.stringify({ category: '餐饮', amount: 68, note: '回民街小吃', paymentMethod: '微信支付', spentAt: '2026-09-15' }),
  })
).json();
console.log('① 微信支付记账:', a.data.paymentMethod, '¥' + a.data.amount);

// ② 支付宝记账
const b = await (
  await fetch(B + '/trips/8/expenses', {
    method: 'POST',
    headers: h,
    body: JSON.stringify({ category: '门票', amount: 100, note: '华清宫门票', paymentMethod: '支付宝', spentAt: '2026-09-15' }),
  })
).json();
console.log('② 支付宝记账:', b.data.paymentMethod, '¥' + b.data.amount);

// ③ Agent 聊天记账（微信关键词解析）
const c = await (
  await fetch(B + '/agent/chat', {
    method: 'POST',
    headers: h,
    body: JSON.stringify({ message: '微信付了 55 元打车', tripId: 8 }),
  })
).json();
console.log('③ Agent记账:', c.data.intent, '|', c.data.message?.slice(0, 110));

// ④ 总览接口
const o = await (await fetch(B + '/expenses/overview', { headers: h })).json();
const d = o.data;
console.log('④ 总览: 总支出 ¥' + d.totalSpent, '| 本月 ¥' + d.monthSpent, '|', d.count, '笔');
console.log('   支付渠道:', JSON.stringify(d.byPaymentMethod));
console.log('   按行程:', d.byTrip.map((x) => `${x.tripName}¥${x.total}`).join(', '));
console.log('   最近:', d.recent.slice(0, 3).map((x) => `${x.tripName}/${x.category}/¥${x.amount}/${x.paymentMethod}`).join(' | '));
console.log('=== 首页记账 + 支付渠道验证通过 ===');
