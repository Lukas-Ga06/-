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
 * - **扫之前先确保关键状态存在**（见 `ensureHomeWidgets`）：首页的「全部待办 →」只有存在
 *   未完成备忘时才渲染。如果不管这一条，这个按钮的对比度是过是不过就取决于 demo 账号里
 *   有没有残留备忘 —— 全新库（CI）永远扫不到它，本地跑久了反而必红，门禁变成掷骰子。
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

/**
 * 首页的「全部待办 →」等组件只在存在未完成备忘时才渲染。
 *
 * 这里**显式造一条**未完成备忘，让扫描覆盖确定的 DOM；扫完删掉，不污染后续用例与本地库。
 * 用 API 而不是点 UI：造数据不该依赖界面文案，也不该被动画/时序干扰。
 */
async function ensureHomeWidgets(
  page: import('@playwright/test').Page,
): Promise<() => Promise<void>> {
  const created = await page.request.post('/api/memos', {
    data: { content: `a11y 扫描占位备忘 ${Date.now()}` },
  });
  const cleanup = async () => {
    if (!created.ok()) return;
    const body = await created.json().catch(() => null);
    const id = body?.data?.id;
    if (id != null) await page.request.delete(`/api/memos/${id}`).catch(() => {});
  };
  // 造完刷新一次，确保按钮真的进 DOM（而不是等 React 的重渲染）
  await page.reload();
  await expect(page.getByTestId('app-shell-brand')).toBeVisible({ timeout: 15_000 });
  return cleanup;
}

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
    // 首页多一步：把「依赖数据才出现」的组件造出来，否则覆盖率取决于库里有没有残留数据
    const cleanup = item.path === '/' ? await ensureHomeWidgets(page) : null;
    try {
      // 等样式/字体/入场动画落定再扫：antd 的 CSS-in-JS 与 fade-up 过渡在首帧还没稳定，
      // 过早扫描会把「过渡中的中间色」当成最终对比度（误报）。
      await page.waitForLoadState('networkidle').catch(() => {});
      await page.waitForTimeout(1000);

      const results = await new AxeBuilder({ page }).withTags(WCAG_TAGS).analyze();
      const blocking = results.violations.filter((v) => BLOCKING.has(v.impact ?? ''));

      expect(blocking, `无障碍问题（critical/serious）：\n${describeViolations(blocking)}`).toEqual(
        [],
      );
    } finally {
      await cleanup?.();
    }
  });
}
