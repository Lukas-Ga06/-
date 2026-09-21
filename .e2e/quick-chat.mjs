const BASE = 'http://localhost:8080/api';
const r = await fetch(BASE + '/auth/login', {
  method: 'POST',
  headers: { 'Content-Type': 'application/json' },
  body: JSON.stringify({ username: 'demo', password: 'demo123' }),
});
const token = (await r.json()).data.token;
const t = Date.now();
const c = await fetch(BASE + '/agent/chat', {
  method: 'POST',
  headers: { 'Content-Type': 'application/json', Authorization: 'Bearer ' + token },
  body: JSON.stringify({ message: '上海三日游，预算3000元' }),
});
const data = (await c.json()).data;
console.log('HTTP', c.status, '耗时', Date.now() - t, 'ms');
console.log('intent:', data?.intent, '| status:', data?.status);
console.log('message:', data?.message?.slice(0, 80));
