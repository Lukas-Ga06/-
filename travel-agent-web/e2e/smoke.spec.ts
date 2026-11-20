import { test } from '@playwright/test';
import { expect, expectOk } from './support';

/**
 * 冒烟链路（全部走规则路径，不依赖 LLM/外部服务）：
 * 未登录重定向 → 首页 → 建行程 → 行程页（路书）→ PWA 资源。
 *
 * 可重复性（B7）：
 * - 登录态来自 globalSetup 写入的 storageState（e2e/.auth/demo.json），用例不再各自登录；
 * - 只有「未登录重定向」这条需要真正的匿名上下文，用 browser.newContext() 单独开一个；
 * - 元素定位优先 data-testid / role，不再依赖易变的中文文案。
 */
test('未登录访问首页 → 跳登录页（路由守卫）', async ({ browser }) => {
  // 不带 storageState 的全新上下文 = 真正的未登录访客。
  // 注意：browser.newContext() 会继承项目 use 里的 storageState，必须显式给空状态覆盖掉。
  const anonymous = await browser.newContext({
    baseURL: process.env.E2E_BASE_URL ?? 'http://localhost:4173',
    storageState: { cookies: [], origins: [] },
  });
  try {
    const page = await anonymous.newPage();
    await page.goto('/');
    await expect(page).toHaveURL(/\/login/);
  } finally {
    await anonymous.close();
  }
});

test('登录 → 建行程 → 行程页与路书可达', async ({ page }) => {
  // 1. 经代理 API 建行程（携带 storageState 里的登录 Cookie），数据准备不走 UI 表单
  const tripName = `e2e-${Date.now()}`;
  const created = await page.request.post('/api/trips', {
    data: {
      name: tripName,
      destination: '上海',
      startDate: '2026-12-01',
      endDate: '2026-12-02',
      budget: 3000,
    },
  });
  await expectOk(created, '创建行程 POST /api/trips');
  const tripId = (await created.json()).data.id;

  // 2. 行程页：AppShell 外壳（testid 定位）+ 标题 + 路书按钮
  //    只做一次页面加载：每次整页加载都会触发一次 /api/auth/me，auth 限流窗口内要尽量省
  await page.goto(`/trip/${tripId}`);
  await expect(page.getByTestId('app-shell-brand')).toBeVisible({ timeout: 15_000 });
  await expect(page.getByRole('heading', { name: tripName })).toBeVisible();
  await expect(page.getByRole('button', { name: /路书/ })).toBeVisible();

  // 3. 路书 PDF 真实导出（走 Cookie 同源 GET）
  const pdf = await page.request.get(`/api/trips/${tripId}/guidebook.pdf`);
  await expectOk(pdf, '导出路书 GET /api/trips/:id/guidebook.pdf');
  expect((await pdf.body())[0]).toBe(0x25); // '%' of %PDF
});

test('PWA 资源可达：manifest 与 service worker', async ({ request }) => {
  const manifest = await request.get('/manifest.webmanifest');
  await expectOk(manifest, '取 PWA manifest');
  expect((await manifest.json()).name).toBe('AI 智能旅行助手');

  const sw = await request.get('/sw.js');
  await expectOk(sw, '取 service worker');
  expect(await sw.text()).toContain('precache');
});