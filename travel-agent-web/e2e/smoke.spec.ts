import { expect, test } from '@playwright/test';

/**
 * 冒烟链路（全部走规则路径，不依赖 LLM/外部服务）：
 * 未登录重定向 → API 登录（demo）注入 Cookie → 首页 → 建行程 → 行程页（路书）→ PWA 资源。
 * 登录走 API 而不是 UI 表单：登录页处于持续迭代中，e2e 只断言稳定区域
 * （路由守卫、AppShell、行程页），避免与页面重构互相拖累。
 */
test('登录 → 建行程 → 行程页与路书可达', async ({ page }) => {
  // 1. 未登录访问首页 → 跳登录页（路由守卫生效）
  await page.goto('/');
  await page.waitForURL(/\/login/);

  // 2. API 登录（demo 账号由 DataInitializer 创建）：page.request 与浏览器共享 Cookie
  const login = await page.request.post('/api/auth/login', {
    data: { username: 'demo', password: 'demo123' },
  });
  expect(login.ok()).toBeTruthy();

  // 3. 登录态进入首页：AppShell 外壳渲染成功
  await page.goto('/');
  await expect(page.getByText('AI 个人助理').first()).toBeVisible({ timeout: 15_000 });

  // 4. 经代理 API 建行程（携带登录 Cookie），数据准备不走 UI 表单
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
  expect(created.ok()).toBeTruthy();
  const tripId = (await created.json()).data.id;

  // 5. 行程页：标题 + 路书按钮
  await page.goto(`/trip/${tripId}`);
  await expect(page.getByRole('heading', { name: tripName })).toBeVisible();
  await expect(page.getByRole('button', { name: /路书/ })).toBeVisible();

  // 6. 路书 PDF 真实导出（走 Cookie 同源 GET）
  const pdf = await page.request.get(`/api/trips/${tripId}/guidebook.pdf`);
  expect(pdf.ok()).toBeTruthy();
  expect((await pdf.body())[0]).toBe(0x25); // '%' of %PDF
});

test('PWA 资源可达：manifest 与 service worker', async ({ request }) => {
  const manifest = await request.get('/manifest.webmanifest');
  expect(manifest.ok()).toBeTruthy();
  expect((await manifest.json()).name).toBe('AI 个人助理');

  const sw = await request.get('/sw.js');
  expect(sw.ok()).toBeTruthy();
  expect(await sw.text()).toContain('precache');
});
