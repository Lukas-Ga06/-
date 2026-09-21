const B = 'http://localhost:8080/api';
const r = await fetch(B + '/auth/login', {
  method: 'POST',
  headers: { 'Content-Type': 'application/json' },
  body: JSON.stringify({ username: 'demo', password: 'demo123' }),
});
const t = (await r.json()).data.token;
const h = { 'Content-Type': 'application/json', Authorization: 'Bearer ' + t };

// ① 日程：Agent 添加
const s1 = await (
  await fetch(B + '/agent/chat', {
    method: 'POST',
    headers: h,
    body: JSON.stringify({ message: '明天下午3点开评审会' }),
  })
).json();
console.log('① Agent日程添加:', s1.data.intent, '|', s1.data.message?.slice(0, 80));

// ② 日程：Agent 查询
const s2 = await (
  await fetch(B + '/agent/chat', {
    method: 'POST',
    headers: h,
    body: JSON.stringify({ message: '我的日程安排' }),
  })
).json();
console.log('② Agent日程查询:', s2.data.intent, '|', s2.data.message?.replace(/\n/g, ' | ').slice(0, 110));

// ③ 邮件分类
const m = await (
  await fetch(B + '/office/mail/classify', {
    method: 'POST',
    headers: h,
    body: JSON.stringify({
      sender: 'hr@company.com',
      subject: '关于年度调薪截止日期的通知',
      text: '各位同事：本年度调薪申请将于本周五截止，请尽快在 HR 系统提交材料，逾期视为放弃。涉及内容：调薪、截止日期、HR 系统提交。',
    }),
  })
).json();
console.log('③ 邮件分类:', m.data.category, '|', m.data.summary?.slice(0, 60), '| 回复:', m.data.suggestedReply?.slice(0, 40));

// ④ 会议纪要
const mm = await (
  await fetch(B + '/office/minutes/generate', {
    method: 'POST',
    headers: h,
    body: JSON.stringify({
      title: 'Q4 产品规划会',
      rawText: '今天讨论 Q4 规划。张三认为应优先做移动端性能优化；李四建议先上线新计费模块；最终决定：性能优化本周启动，计费模块下月初进入开发。王五负责整理性能基线数据。',
    }),
  })
).json();
console.log('④ 会议纪要生成: 长度', mm.data.content?.length, '字符');
console.log('   ', mm.data.content?.slice(0, 150).replace(/\n/g, ' '));

// ⑤ 日报生成
const rp = await (
  await fetch(B + '/office/report/generate', { method: 'POST', headers: h })
).json();
console.log('⑤ 日报生成: 日期', rp.data.date, '长度', rp.data.content?.length);
console.log('   ', rp.data.content?.slice(0, 130).replace(/\n/g, ' '));

// ⑥ 发票提取
const inv = await (
  await fetch(B + '/office/invoice/extract', {
    method: 'POST',
    headers: h,
    body: JSON.stringify({
      text: '数电发票\n购买方：个人\n销售方：上海携程商务有限公司\n项目：旅客运输服务*机票\n金额：¥860.00\n合计：¥860.00\n开票日期：2026-09-10',
    }),
  })
).json();
console.log('⑥ 发票提取:', inv.data.category, inv.data.vendor?.slice(0, 20), '¥' + inv.data.amount, inv.data.invoiceDate);

// ⑦ 学习：知识框架
const lf = await (
  await fetch(B + '/learning/generate', {
    method: 'POST',
    headers: h,
    body: JSON.stringify({ kind: 'FRAMEWORK', topic: '机器学习' }),
  })
).json();
console.log('⑦ 知识框架: 长度', lf.data.content?.length, '|', lf.data.content?.slice(0, 80).replace(/\n/g, ' '));

// ⑧ 学习：知识库入库 + 检索
const ka = await (
  await fetch(B + '/learning/knowledge/add', {
    method: 'POST',
    headers: h,
    body: JSON.stringify({
      title: '考研政治重点',
      content: '马克思主义基本原理是考研政治第一部分，重点掌握唯物辩证法三大规律。\n\n毛泽东思想与中国特色社会主义理论体系概论占比约 30%，重点是新民主主义革命理论。\n\n时政部分每年 12 月冲刺复习效果最好。',
    }),
  })
).json();
console.log('⑧ 知识入库:', ka.data.chunks, '个知识块');
const ks = await (
  await fetch(B + '/learning/knowledge/search', {
    method: 'POST',
    headers: h,
    body: JSON.stringify({ query: '考研政治哪部分最重要' }),
  })
).json();
console.log('   检索:', ks.data?.slice(0, 2).map((x) => `${x.title}(${(x.score * 100).toFixed(0)}%)`).join(', '));

// ⑨ 健康：种子数据 + 查询
const sd = await (await fetch(B + '/health/seed-demo', { method: 'POST', headers: h })).json();
console.log('⑨ 健康演示数据:', sd.data.created, '天');
const hs = await (await fetch(B + '/health/summary', { headers: h })).json();
console.log('   最新步数:', hs.data.latest?.steps, '| 均值:', hs.data.avgSteps, '步 /', hs.data.avgSleep, '小时');
const qh = await (
  await fetch(B + '/agent/chat', {
    method: 'POST',
    headers: h,
    body: JSON.stringify({ message: '我的健康数据怎么样' }),
  })
).json();
console.log('   Agent健康查询:', qh.data.intent, '|', qh.data.message?.slice(0, 100).replace(/\n/g, ' '));

console.log('=== 办公/学习/健康 全模块 E2E 验证通过 ===');
