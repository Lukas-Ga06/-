#!/usr/bin/env node
/**
 * 暗色地图 / 暗色图表 / 自检可见性 的真实浏览器验收（Chromium）。
 *
 * 前置（脚本不负责起后端/静态站）：
 *   node .e2e/dark/start-instance.mjs serve        # 后端 18096 + 独立 H2
 *   node .e2e/mobile/serve-dist.mjs --port 4173 --api http://127.0.0.1:18096
 *
 * 用法：
 *   node .e2e/dark/verify-dark.mjs [--base http://127.0.0.1:4173] [--api http://127.0.0.1:18096] \
 *     [--out .evidence/dark/dark-evidence.json] [--screens .evidence/dark/screens]
 *
 * 产出：
 *   - 行程页浅色/暗色地图元素截图 + 整页截图
 *   - 预算分析（BudgetChart）/ 记账（ExpensePanel）抽屉暗色截图
 *   - 健康页「运行能力自检」卡片截图与文案
 *   - JSON：容器上的 data-map-style 等可复核状态、页面文本、控制台错误
 */
import { createRequire } from 'node:module';
import { mkdirSync, writeFileSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const HERE = dirname(fileURLToPath(import.meta.url));
const REPO = resolve(HERE, '..', '..');
const require = createRequire(join(REPO, 'travel-agent-web', 'package.json'));
const { chromium } = require('playwright');

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

function arg(name, fallback) {
  const hit = process.argv.find((a) => a.startsWith(`--${name}=`));
  return hit ? hit.slice(name.length + 3) : fallback;
}

const BASE = (arg('base', 'http://127.0.0.1:4173')).replace(/\/+$/, '');
const API = (arg('api', 'http://127.0.0.1:18096')).replace(/\/+$/, '');
const OUT = resolve(arg('out', join(REPO, '.evidence', 'dark', 'dark-evidence.json')));
const SCREENS = resolve(arg('screens', join(REPO, '.evidence', 'dark', 'screens')));

mkdirSync(SCREENS, { recursive: true });
mkdirSync(dirname(OUT), { recursive: true });

function log(msg) {
  process.stdout.write(`[dark-verify] ${msg}\n`);
}

async function api(path, { method = 'GET', token, body } = {}) {
  const res = await fetch(`${API}${path}`, {
    method,
    headers: {
      'Content-Type': 'application/json',
      ...(token ? { Authorization: `Bearer ${token}` } : {}),
    },
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  const json = await res.json().catch(() => null);
  if (!json || json.code !== 0) {
    throw new Error(`${method} ${path} -> HTTP ${res.status} ${json?.code} ${json?.message}`);
  }
  return json.data;
}

/** 播种一份带坐标/花费/支出的行程，供地图与两个图表真实渲染 */
async function seed() {
  const login = await api('/api/auth/login', {
    method: 'POST',
    body: { username: 'demo', password: 'demo123' },
  });
  const token = login.token;
  const trip = await api('/api/trips', {
    method: 'POST',
    token,
    body: {
      name: `暗色验收 · 上海 ${new Date().toISOString().slice(0, 10)}`,
      destination: '上海',
      startDate: '2026-12-01',
      endDate: '2026-12-02',
      budget: 3000,
      partySize: 1,
    },
  });
  const items = [
    { dayIndex: 1, itemType: 'hotel', title: '外滩江景酒店', startTime: '09:00', endTime: '10:00', cost: 880, latitude: 31.2402, longitude: 121.4902 },
    { dayIndex: 1, itemType: 'attraction', title: '外滩', startTime: '10:30', endTime: '12:00', cost: 0, latitude: 31.2397, longitude: 121.4912 },
    { dayIndex: 1, itemType: 'restaurant', title: '南京东路午餐', startTime: '12:30', endTime: '13:30', cost: 220, latitude: 31.2356, longitude: 121.4783 },
    { dayIndex: 1, itemType: 'attraction', title: '豫园', startTime: '14:00', endTime: '16:00', cost: 40, latitude: 31.2271, longitude: 121.4921 },
    { dayIndex: 2, itemType: 'attraction', title: '上海博物馆', startTime: '09:30', endTime: '12:00', cost: 0, latitude: 31.2303, longitude: 121.4756 },
    { dayIndex: 2, itemType: 'restaurant', title: '田子坊晚餐', startTime: '18:00', endTime: '19:30', cost: 180, latitude: 31.2091, longitude: 121.4682 },
  ];
  const itemIds = [];
  for (const item of items) {
    const created = await api(`/api/trips/${trip.id}/itinerary`, { method: 'POST', token, body: item });
    itemIds.push(created.id);
  }
  // 触发一次 route-legs：用于拿到真实的 routeLastError（自检卡片要显示它）
  let routeLegs = [];
  try {
    routeLegs = await api(`/api/trips/${trip.id}/route-legs`, { token });
  } catch (err) {
    log(`route-legs 请求失败（继续）：${err.message}`);
  }
  const expenses = [
    { category: '住宿', amount: 880, note: '外滩酒店', spentAt: '2026-12-01', paymentMethod: '微信支付' },
    { category: '餐饮', amount: 220, note: '南京东路午餐', spentAt: '2026-12-01', paymentMethod: '微信支付' },
    { category: '交通', amount: 96, note: '打车', spentAt: '2026-12-02', paymentMethod: '支付宝' },
    { category: '门票', amount: 40, note: '豫园', spentAt: '2026-12-01', paymentMethod: '微信支付' },
  ];
  for (const expense of expenses) {
    await api(`/api/trips/${trip.id}/expenses`, { method: 'POST', token, body: expense });
  }
  await api(`/api/trips/${trip.id}/status`, { method: 'PUT', token, body: { status: 'CONFIRMED' } });
  return { tripId: trip.id, token, itemIds, items: items.length, expenses: expenses.length, routeLegs: routeLegs.length };
}

async function main() {
  const health = await fetch(`${API}/actuator/health`).then((r) => r.json());
  if (health.status !== 'UP') throw new Error(`后端 ${API} 未就绪：${JSON.stringify(health)}`);
  const baseOk = await fetch(`${BASE}/`).then((r) => r.ok).catch(() => false);
  if (!baseOk) throw new Error(`静态站 ${BASE} 不可用`);

  const seeded = await seed();
  const status = await api('/api/system/status', { token: seeded.token });
  log(`seeded trip=${seeded.tripId} items=${seeded.items} expenses=${seeded.expenses} routeLegs=${seeded.routeLegs}`);
  log(`system/status: llmConfigured=${status.llmConfigured} llmVerified=${status.llmVerified} routeConfigured=${status.routeConfigured} amapVerified=${status.amapVerified} routeProvider=${status.routeProvider}`);

  const browser = await chromium.launch({ headless: true });
  const context = await browser.newContext({
    viewport: { width: 1440, height: 1000 },
    deviceScaleFactor: 1,
    locale: 'zh-CN',
    timezoneId: 'Asia/Shanghai',
    colorScheme: 'light',
  });
  const page = await context.newPage();
  const consoleErrors = [];
  page.on('console', (msg) => {
    if (msg.type() === 'error') consoleErrors.push(msg.text());
  });
  page.on('pageerror', (err) => consoleErrors.push(`pageerror: ${err.message}`));

  const result = {
    generatedAt: new Date().toISOString(),
    base: BASE,
    api: API,
    systemStatus: status,
    // token 不落证据文件
    seed: { tripId: seeded.tripId, itemIds: seeded.itemIds, items: seeded.items, expenses: seeded.expenses, routeLegs: seeded.routeLegs },
    map: {},
    charts: {},
    health: {},
    consoleErrors,
    screenshots: {},
  };

  // ---------------- 登录 + 行程页 ----------------
  await page.goto(`${BASE}/login`, { waitUntil: 'domcontentloaded' });
  await page.waitForSelector('button[type=submit]', { timeout: 30000 });
  await page.click('button[type=submit]');
  await page.waitForURL((url) => !url.pathname.startsWith('/login'), { timeout: 30000 });

  await page.goto(`${BASE}/trip/${seeded.tripId}`, { waitUntil: 'domcontentloaded' });
  await page.waitForSelector('.trip-map-body [data-map-ready="ok"]', { timeout: 90000 });
  await page.waitForSelector('.trip-map-body canvas', { timeout: 30000 });
  await sleep(3500); // 3D 建筑的入场动画结束后再截图

  const mapLight = await page.evaluate(() => {
    const host = document.querySelector('.trip-map-body [data-map-style]');
    return {
      theme: document.documentElement.dataset.theme,
      mapStyle: host?.getAttribute('data-map-style') ?? null,
      mapTheme: host?.getAttribute('data-map-theme') ?? null,
      ready: host?.getAttribute('data-map-ready') ?? null,
      canvasCount: document.querySelectorAll('.trip-map-body canvas').length,
      mapBox: (() => {
        const box = document.querySelector('.trip-map-body')?.getBoundingClientRect();
        return box ? { width: Math.round(box.width), height: Math.round(box.height) } : null;
      })(),
    };
  });
  await page.locator('.trip-map-body').screenshot({ path: join(SCREENS, 'map-light.png') });
  await page.screenshot({ path: join(SCREENS, 'trip-light-full.png'), fullPage: false });

  // ---------------- 切暗色主题 ----------------
  await page.click('[data-testid=theme-toggle-sidebar]');
  await page.waitForFunction(() => document.documentElement.dataset.theme === 'dark', null, { timeout: 15000 });
  await page.waitForFunction(
    () => document.querySelector('.trip-map-body [data-map-style]')?.getAttribute('data-map-style') === 'amap://styles/dark',
    null,
    { timeout: 15000 },
  );
  await sleep(7000); // 等高德 dark 底图瓦片重载完成（样式切换是异步的）
  const mapDark = await page.evaluate(() => {
    const host = document.querySelector('.trip-map-body [data-map-style]');
    return {
      theme: document.documentElement.dataset.theme,
      mapStyle: host?.getAttribute('data-map-style') ?? null,
      mapTheme: host?.getAttribute('data-map-theme') ?? null,
      ready: host?.getAttribute('data-map-ready') ?? null,
      mapBackground: getComputedStyle(document.querySelector('.trip-map-body')).backgroundColor,
    };
  });
  await page.locator('.trip-map-body').screenshot({ path: join(SCREENS, 'map-dark.png') });
  await page.screenshot({ path: join(SCREENS, 'trip-dark-full.png'), fullPage: false });
  result.map = { light: mapLight, dark: mapDark };
  result.screenshots.mapLight = join(SCREENS, 'map-light.png');
  result.screenshots.mapDark = join(SCREENS, 'map-dark.png');
  log(`map: light=${mapLight.mapStyle} dark=${mapDark.mapStyle} theme=${mapDark.theme}`);

  // ---------------- 预算分析抽屉（BudgetChart） ----------------
  // 侧栏也有「记账」等导航项：按钮必须限定在行程页头部操作区，否则会点去别的页面
  await page.locator('.page-actions button:has-text("预算分析")').first().click();
  await page.waitForSelector('.ant-drawer-open .ant-drawer-body canvas', { timeout: 30000 });
  await sleep(2000);
  const budgetText = (await page.locator('.ant-drawer-open .ant-drawer-body').innerText()).slice(0, 400);
  await page.locator('.ant-drawer-open .ant-drawer-body').screenshot({ path: join(SCREENS, 'budget-dark-drawer.png') });
  await page.locator('.ant-drawer-open .ant-drawer-body canvas').first().screenshot({ path: join(SCREENS, 'budget-dark-canvas.png') });
  result.charts.budget = { drawerText: budgetText };
  result.screenshots.budget = join(SCREENS, 'budget-dark-canvas.png');
  await page.locator('.ant-drawer-open .ant-drawer-close').first().click();
  await page.waitForFunction(() => document.querySelectorAll('.ant-drawer-open').length === 0, null, { timeout: 15000 }).catch(() => undefined);
  await sleep(800);

  // ---------------- 记账抽屉（ExpensePanel 饼图） ----------------
  await page.locator('.page-actions button:has-text("记账")').first().click();
  await page.waitForSelector('.ant-drawer-open .ant-drawer-body canvas', { timeout: 30000 });
  await sleep(2000);
  const expenseText = (await page.locator('.ant-drawer-open .ant-drawer-body').innerText()).slice(0, 500);
  await page.locator('.ant-drawer-open .ant-drawer-body').screenshot({ path: join(SCREENS, 'expense-dark-drawer.png') });
  await page.locator('.ant-drawer-open .ant-drawer-body canvas').first().screenshot({ path: join(SCREENS, 'expense-dark-canvas.png') });
  result.charts.expense = { drawerText: expenseText };
  result.screenshots.expense = join(SCREENS, 'expense-dark-canvas.png');
  await page.locator('.ant-drawer-open .ant-drawer-close').first().click();
  await page.waitForFunction(() => document.querySelectorAll('.ant-drawer-open').length === 0, null, { timeout: 15000 }).catch(() => undefined);

  // ---------------- 健康页：运行能力自检（暗色） ----------------
  await page.goto(`${BASE}/health`, { waitUntil: 'domcontentloaded' });
  await page.waitForSelector('[data-testid=selfcheck-route-state]', { timeout: 30000 });
  const selfcheck = await page.evaluate(() => {
    const rowText = (testId) => document.querySelector(`[data-testid=${testId}]`)?.innerText ?? '';
    return {
      theme: document.documentElement.dataset.theme,
      llmState: document.querySelector('[data-testid=selfcheck-llm-state]')?.textContent?.trim() ?? '',
      llmText: rowText('selfcheck-llm'),
      routeState: document.querySelector('[data-testid=selfcheck-route-state]')?.textContent?.trim() ?? '',
      routeText: rowText('selfcheck-route'),
    };
  });
  const card = page.locator('.dash-card').filter({ hasText: '运行能力自检' }).first();
  await card.screenshot({ path: join(SCREENS, 'health-selfcheck-dark.png') });
  result.health = selfcheck;
  result.screenshots.health = join(SCREENS, 'health-selfcheck-dark.png');
  log(`selfcheck: llm=${selfcheck.llmState} route=${selfcheck.routeState}`);

  await browser.close();

  result.ok = {
    mapDarkStyle: mapDark.mapStyle === 'amap://styles/dark',
    routeStateIsUnverified: selfcheck.routeState.includes('未验证'),
    routeStateNotVerified: selfcheck.routeState !== '已验证',
    chartScreenshots: result.screenshots.budget != null && result.screenshots.expense != null,
  };
  writeFileSync(OUT, JSON.stringify(result, null, 2), 'utf8');
  log(`evidence -> ${OUT}`);
  log(`ok=${JSON.stringify(result.ok)}`);
  if (!result.ok.mapDarkStyle || !result.ok.routeStateIsUnverified || !result.ok.routeStateNotVerified) {
    process.exitCode = 1;
  }
}

main().catch((err) => {
  console.error(`[dark-verify] FAILED: ${err?.stack ?? err}`);
  process.exit(1);
});