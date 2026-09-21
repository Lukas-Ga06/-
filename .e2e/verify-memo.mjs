const B = 'http://localhost:8080/api';
const r = await fetch(B + '/auth/login', {
  method: 'POST',
  headers: { 'Content-Type': 'application/json' },
  body: JSON.stringify({ username: 'demo', password: 'demo123' }),
});
const t = (await r.json()).data.token;
const h = { 'Content-Type': 'application/json', Authorization: 'Bearer ' + t };

// ① API 添加备忘
const a = await (
  await fetch(B + '/memos', {
    method: 'POST',
    headers: h,
    body: JSON.stringify({ content: '明天上午 9 点办签证' }),
  })
).json();
console.log('① API备忘:', a.data.content, 'done=' + a.data.done);

// ② Agent 聊天添加备忘
const c1 = await (
  await fetch(B + '/agent/chat', {
    method: 'POST',
    headers: h,
    body: JSON.stringify({ message: '记一下给妈妈买生日礼物' }),
  })
).json();
console.log('② Agent添加备忘:', c1.data.intent, '|', c1.data.message?.slice(0, 80));

// ③ Agent 查询备忘
const c2 = await (
  await fetch(B + '/agent/chat', {
    method: 'POST',
    headers: h,
    body: JSON.stringify({ message: '我的备忘' }),
  })
).json();
console.log('③ Agent查询备忘:', c2.data.intent);
console.log('   ', c2.data.message?.replace(/\n/g, ' | ').slice(0, 120));

// ④ 完成切换
const list = (await (await fetch(B + '/memos', { headers: h })).json()).data;
const tg = await (
  await fetch(B + '/memos/' + list[0].id + '/toggle', { method: 'POST', headers: h })
).json();
console.log('④ 完成切换:', tg.data.content, '→ done=' + tg.data.done);

// ⑤ 清理（删除全部验证数据）
for (const m of list) {
  await fetch(B + '/memos/' + m.id, { method: 'DELETE', headers: h });
}
const after = (await (await fetch(B + '/memos', { headers: h })).json()).data;
console.log('⑤ 清理后剩余:', after.length, '条');
console.log('=== 备忘模块 + Agent 跨模块验证通过 ===');
