import { test, expect, type Page } from '@playwright/test';
import { installApiMocks } from './fixtures';

/**
 * 关键页面截图基线（视觉回归）。
 *
 * 约定：截图内容只由「应用自身代码 + fixtures 固定数据 + 冻结时钟」决定，
 * 因此基线可以长期复用；一旦真机对比出现差异，先看 `test-results/` 里的
 * expected/actual/diff 三张图，再决定是修样式还是（有意改动时）更新基线。
 */
const FIXED_NOW = new Date('2026-11-21T10:00:00+08:00');

async function settle(page: Page): Promise<void> {
  await page.waitForLoadState('networkidle');
  // fade-up / page-transition 等入场动画最长 280ms，宁可多等一会也不要拍在中间帧
  await page.waitForTimeout(500);
}

test.beforeEach(async ({ page }) => {
  // 先冻结时钟再导航：首屏渲染就用固定「现在」
  await page.clock.setFixedTime(FIXED_NOW);
  // 外网一律断开：地图瓦片/字体 CDN 不可用时页面必须降级，而不是拖慢截图
  await page.route('**/*', (route) => {
    const host = new URL(route.request().url()).hostname;
    if (host === '127.0.0.1' || host === 'localhost') return route.continue();
    return route.abort();
  });
  // 后注册的 route 优先，API 桩覆盖上面的兜底
  await installApiMocks(page);
});

test('登录页', async ({ page }) => {
  await page.goto('/login');
  await settle(page);
  await expect(page).toHaveScreenshot('01-login.png', { fullPage: true });
});

test('首页（下一次旅行 + 今日/待处理）', async ({ page }) => {
  await page.goto('/');
  await settle(page);
  await expect(page).toHaveScreenshot('02-home.png', { fullPage: true });
});

test('旅行列表', async ({ page }) => {
  await page.goto('/travel');
  await settle(page);
  await expect(page).toHaveScreenshot('03-travel.png', { fullPage: true });
});

test('行程详情（桌面双栏）', async ({ page }) => {
  await page.goto('/trip/1');
  await settle(page);
  await expect(page).toHaveScreenshot('04-trip.png', { fullPage: true });
});

test('行程详情（390px 移动分段视图）', async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 });
  await page.goto('/trip/1');
  await settle(page);
  await expect(page).toHaveScreenshot('05-trip-mobile.png', { fullPage: true });
});

test('规划器（三步表单）', async ({ page }) => {
  await page.goto('/planner');
  await settle(page);
  await expect(page).toHaveScreenshot('06-planner.png', { fullPage: true });
});

test('AI 助手（空会话）', async ({ page }) => {
  await page.goto('/assistant');
  await settle(page);
  await expect(page).toHaveScreenshot('07-assistant.png', { fullPage: true });
});

test('旅行回顾列表', async ({ page }) => {
  await page.goto('/review');
  await settle(page);
  await expect(page).toHaveScreenshot('08-reviews.png', { fullPage: true });
});

test('攻略库', async ({ page }) => {
  await page.goto('/knowledge');
  await settle(page);
  await expect(page).toHaveScreenshot('09-knowledge.png', { fullPage: true });
});

test('设置 · 诊断', async ({ page }) => {
  await page.goto('/settings/diagnostics');
  await settle(page);
  await expect(page).toHaveScreenshot('10-diagnostics.png', { fullPage: true });
});

test('个人资料', async ({ page }) => {
  await page.goto('/profile');
  await settle(page);
  await expect(page).toHaveScreenshot('11-profile.png', { fullPage: true });
});
