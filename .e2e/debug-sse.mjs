/** 调试：读取 SSE 原始流并完整输出事件名序列 */
const BASE = 'http://localhost:8080/api';

const r = await fetch(`${BASE}/auth/login`, {
  method: 'POST',
  headers: { 'Content-Type': 'application/json' },
  body: JSON.stringify({ username: 'demo', password: 'demo123' }),
});
const token = (await r.json()).data.token;

const msg = encodeURIComponent('杭州两日游，预算2500元');
const res = await fetch(`${BASE}/agent/stream?message=${msg}&token=${token}`);
console.log('status:', res.status);
const text = await res.text();
const names = [...text.matchAll(/event:(\w+)/g)].map((m) => m[1]);
console.log('事件序列:', names.join(' -> '));
console.log('流长度:', text.length, '字节');
console.log('--- 原始流尾部 500 字节 ---');
console.log(text.slice(-500));
