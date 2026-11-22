/** 通过 Vite 代理（5173）完整测试 SSE 流 */
const BASE = 'http://localhost:5173/api';
const r = await fetch(BASE + '/auth/login', {
  method: 'POST',
  headers: { 'Content-Type': 'application/json' },
  body: JSON.stringify({ username: 'demo', password: 'demo123' }),
});
const token = (await r.json()).data.token;
console.log('登录 OK，token 长度', token.length);

const res = await fetch(`${BASE}/agent/stream?message=${encodeURIComponent('杭州两日游，预算2500元')}&token=${token}`);
console.log('SSE status:', res.status, res.headers.get('content-type'));
const text = await res.text();
const names = [...text.matchAll(/event:(\w+)/g)].map((m) => m[1]);
console.log('事件数:', names.length);
console.log('事件序列（去重）:', [...new Set(names)].join(' -> '));
console.log(names.includes('complete') ? '✅ SSE 经代理完整可用' : '❌ SSE 经代理不完整');
