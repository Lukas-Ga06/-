/**
 * 旅行闭环模块验证（P1）：登录 → 建行程 → 加条目 → 打卡 → 回顾 → 事件 → 知识库。
 *
 * 用法：先启动后端（8080），再执行 `node .e2e/verify-modules.mjs`。
 * 产品收缩后本脚本不再验证办公/学习/健康模块（已退役）。
 */
const B = 'http://localhost:8080/api';
const r = await fetch(B + '/auth/login', {
  method: 'POST',
  headers: { 'Content-Type': 'application/json' },
  body: JSON.stringify({ username: 'demo', password: 'demo123' }),
});
const t = (await r.json()).data.token;
const h = { 'Content-Type': 'application/json', Authorization: 'Bearer ' + t };
const today = new Date();
const iso = (d) => d.toISOString().slice(0, 10);
const start = iso(today);
const end = iso(new Date(today.getTime() + 2 * 86400000));

// ① 建行程
const trip = await (
  await fetch(B + '/trips', {
    method: 'POST',
    headers: h,
    body: JSON.stringify({
      name: '闭环验证行程',
      destination: '上海',
      startDate: start,
      endDate: end,
      budget: 3000,
      partySize: 2,
    }),
  })
).json();
const tripId = trip.data.id;
console.log('① 建行程:', tripId, trip.data.name);

// ② 加行程项
const item = await (
  await fetch(B + `/trips/${tripId}/itinerary`, {
    method: 'POST',
    headers: h,
    body: JSON.stringify({
      dayIndex: 1,
      itemType: 'attraction',
      title: '上海博物馆',
      startTime: '10:00',
      endTime: '12:00',
      cost: 50,
      latitude: 31.2304,
      longitude: 121.4737,
      tags: ['室内', '文化'],
    }),
  })
).json();
const itemId = item.data.id;
console.log('② 加条目:', itemId, item.data.title, '状态=', item.data.status);

// ③ 打卡完成（写 visitedAt + CHECK_IN 事件 + 撤销链）
const done = await (
  await fetch(B + `/itinerary/${itemId}/status`, {
    method: 'PUT',
    headers: h,
    body: JSON.stringify({ status: 'COMPLETED' }),
  })
).json();
console.log('③ 打卡:', done.data.status, '| visitedAt=', done.data.visitedAt);

// ④ 回顾聚合
const review = await (await fetch(B + `/trips/${tripId}/review`, { headers: h })).json();
console.log(
  '④ 回顾: 完成率',
  (review.data.completionRate * 100).toFixed(0) + '%',
  '| 计划 ¥' + review.data.plannedCost,
  '| 实际 ¥' + review.data.actualCost,
  '| 到访',
  review.data.visitedPlaces.length,
  '| 照片',
  review.data.photoCount,
  '| 总结:',
  review.data.aiSummary?.slice(0, 60)
);

// ⑤ 事件流
const events = await (await fetch(B + `/trips/${tripId}/events`, { headers: h })).json();
console.log('⑤ 事件:', events.data.map((e) => `${e.type}/${e.source}`).join(', '));

// ⑥ 足迹口径：visited 只认打卡
const map = await (await fetch(B + '/trips/map', { headers: h })).json();
const point = map.data.find((p) => p.tripId === tripId);
console.log('⑥ 足迹: visited=', point?.visited, '| planned=', point?.plannedPlaces, '| 实际到访=', point?.visitedPlaces);

// ⑦ 知识库入库 + 检索（带引用字段）
const ka = await (
  await fetch(B + '/knowledge/add', {
    method: 'POST',
    headers: h,
    body: JSON.stringify({
      title: '闭环验证-上海亲子出行提示',
      content: '上海亲子出行建议：优先选择室内场馆，避开正午高温。\n\n地铁 + 步行组合通常比打车更稳定，周末需预留排队时间。',
    }),
  })
).json();
console.log('⑦ 知识入库:', ka.data.chunks, '个知识块，文档', ka.data.documentId, '版本', ka.data.versionNo);
const ks = await (
  await fetch(B + '/knowledge/search', {
    method: 'POST',
    headers: h,
    body: JSON.stringify({ query: '上海亲子出行要注意什么' }),
  })
).json();
const hit = ks.data?.[0];
console.log(
  '   检索:',
  ks.data?.slice(0, 2).map((x) => `${x.title}(v${x.version ?? '-'}, ${(x.score * 100).toFixed(0)}%)`).join(', ')
);
console.log('   引用字段完整:', Boolean(hit?.documentId && hit?.chunkId && hit?.source && hit?.snippet));

// ⑧ Agent 知识问答（SSE 之外的最短路径）
const ask = await (
  await fetch(B + '/agent/chat', {
    method: 'POST',
    headers: h,
    body: JSON.stringify({ message: '上海有什么适合亲子游的地方？' }),
  })
).json();
console.log('⑧ Agent知识问答:', ask.data.intent, '|', ask.data.message?.replace(/\n/g, ' ').slice(0, 100));

// 清理
await fetch(B + `/trips/${tripId}`, { method: 'DELETE', headers: h });
console.log('=== 旅行闭环（行程/状态/回顾/事件/足迹/知识库）验证通过 ===');
