import { test, expect } from '@playwright/test';
import AxeBuilder from '@axe-core/playwright';

/**
 * 自动化无障碍检查（体验方案阶段 E · §13）。
 *
 * 口径：
 * - 只拦 **critical / serious**（moderate/minor 进报告但不失败），避免把门禁变成噪声源；
 * - 扫描 WCAG 2.0/2.1 A + AA（WCAG 2.2 的 A/AA 标签在 axe 4.13 中已并入 wcag21aa 的规则集）；
 * - 登录页用匿名上下文，其余页面复用 globalSetup 的 storageState；
 * - 每个页面先等主内容可见再扫，避免扫到 Suspense 占位骨架。
 *
 * 失败时输出「规则 id + 影响 + 选择器 + 一句话」，方便直接定位到组件。
 */

const PAGES: Array<{
  name: string;
  path: string;
  anchor: (page: import('@playwright/test').Page) => Promise<void>;
}> = [
  {
    name: '登录页',
    path: '/login',
    anchor: async (page) => {
      await expect(page.getByRole('heading', { name: /AI Travel Agent/ })).toBeVisible({
        timeout: 15_000,
      });
    },
  },
  {
    name: '首页',
    path: '/',
    anchor: async (page) => {
      await expect(page.getByTestId('app-shell-brand')).toBeVisible({ timeout: 15_000 });
    },
  },
  {
    name: 'AI 助手',
    path: '/assistant',
    anchor: async (page) => {
      await expect(page.getByRole('heading', { name: /AI 助手/ })).toBeVisible({ timeout: 15_000 });
    },
  },
  {
    name: '旅行列表',
    path: '/travel',
    anchor: async (page) => {
      await expect(page.getByTestId('app-shell-brand')).toBeVisible({ timeout: 15_000 });
    },
  },
  {
    name: '旅行回顾',
    path: '/review',
    anchor: async (page) => {
      await expect(page.getByRole('heading', { name: /旅行回顾/ })).toBeVisible({
        timeout: 15_000,
      });
    },
  },
];

const WCAG_TAGS = ['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa'];
const BLOCKING = new Set(['critical', 'serious']);

function describeViolations(
  violations: Awaited<ReturnType<AxeBuilder['analyze']>>['violations'],
): string {
  return violations
    .map((v) => {
      const targets = v.nodes
        .slice(0, 3)
        .map((n) => n.target.join(' '))
        .join(' | ');
      return `[${v.impact}] ${v.id}: ${v.help} → ${targets}`;
    })
    .join('\n');
}

for (const item of PAGES) {
  test(`无障碍扫描：${item.name}`, async ({ page }) => {
    if (item.path === '/login') {
      // 未登录访客：清空 storageState，否则路由守卫会把它带回首页
      await page.context().clearCookies();
    }
    await page.goto(item.path);
    await item.anchor(page);
    // 等样式/字体/入场动画落定再扫：antd 的 CSS-in-JS 与 fade-up 过渡在首帧还没稳定，
    // 过早扫描会把「过渡中的中间色」当成最终对比度（误报）。
    await page.waitForLoadState('networkidle').catch(() => {});
    await page.waitForTimeout(1000);

    const results = await new AxeBuilder({ page }).withTags(WCAG_TAGS).analyze();
    const blocking = results.violations.filter((v) => BLOCKING.has(v.impact ?? ''));

    expect(blocking, `无障碍问题（critical/serious）：\n${describeViolations(blocking)}`).toEqual(
      [],
    );
  });
}
