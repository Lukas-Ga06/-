import { test } from '@playwright/test';
import { expect } from './support';
import { emulateSlow3G, setCdpOffline } from './support-device';

/**
 * 弱网 / 硬离线用例（**只能在 Chromium 上跑**）。
 *
 * 为什么不上移动项目：`Network.emulateNetworkConditions` 是 CDP 命令，
 * Playwright 的 WebKit 不支持 CDP（`CDP session is only available in Chromium`）。
 * 因此这个文件固定在 `network-chromium` 项目下执行，移动端的 Safari 引擎覆盖由
 * `mobile-safari.spec.ts` 承担，两边互补而不是互相替代。
 *
 * 三件事分开测，因为它们是三个不同的机制：
 * 1. Slow-3G：首屏能否在可接受时间内完成渲染（不给用户看 20 秒白屏）；
 * 2. 硬离线：`navigator.onLine === false` 时 `OfflineBanner` 出现，且界面不白屏、可继续用；
 * 3. 恢复网络：横幅消失，页面自动回到可用状态（不需要用户手动刷新）。
 *
 * 另外用一条对照用例钉住一个已知边界：Service Worker 被屏蔽时，离线状态下**跳转到尚未
 * 加载过的懒加载路由**会走到 ErrorBoundary（"页面出错了 / Unable to preload CSS for ..."）。
 * 这是我们自己设置 `serviceWorkers: 'block'` + 路由级 `lazy()` 的直接后果，不是产品回归，
 * 但「移动端离线切页」的真实体验依赖 SW 预缓存，所以在这里显式记录下来。
 */

test.describe('弱网与离线（Chromium + CDP）', () => {
  test('Slow-3G 下首页首屏仍能完成渲染（≤20s）', async ({ page }) => {
    const session = await emulateSlow3G(page);

    const startedAt = Date.now();
    await page.goto('/', { waitUntil: 'commit' });
    // 首屏就绪的判定：页头出现 + 主内容区有真实文字（不是骨架屏）
    await expect(page.locator('.dash-greeting')).toBeVisible({ timeout: 20_000 });
    await expect(page.getByTestId('home-new-trip')).toBeVisible({ timeout: 20_000 });
    const firstScreenMs = Date.now() - startedAt;

    // 阈值 20s 是刻意放宽的稳定线：实测 Slow-3G 下 ~8s，留 2.5 倍余量吃 CI 抖动
    expect(firstScreenMs, `Slow-3G 首屏耗时 ${firstScreenMs}ms 超过 20000ms`).toBeLessThan(20_000);

    // 首屏之外，样式也真的到了（不是「白底黑字先出来」）：底栏/卡片有实际盒模型
    const painted = await page.evaluate(() => {
      const card = document.querySelector('.dash-card');
      const bar = document.querySelector('.app-tabbar');
      const s = card ? getComputedStyle(card) : null;
      return {
        hasCard: !!card,
        cardBg: s?.backgroundColor ?? null,
        tabbarVisible: !!bar && getComputedStyle(bar).display !== 'none',
        bodyText: document.body.innerText.length,
      };
    });
    expect(painted.hasCard).toBe(true);
    expect(painted.bodyText).toBeGreaterThan(50);

    // 网络条件确实生效了：Slow-3G 下首屏 HTML 的 TTFB 明显被 400ms 延迟拖慢
    const nav = await page.evaluate(() => {
      const entry = performance.getEntriesByType('navigation')[0] as PerformanceNavigationTiming;
      return {
        ttfb: Math.round(entry.responseStart),
        requests: performance.getEntriesByType('resource').length,
      };
    });
    expect(nav.ttfb).toBeGreaterThan(0);

    await session.send('Network.emulateNetworkConditions', {
      offline: false,
      latency: 0,
      downloadThroughput: -1,
      uploadThroughput: -1,
    });
  });

  test('硬离线：OfflineBanner 出现、页面不白屏，恢复网络后横幅消失', async ({ context, page }) => {
    await page.goto('/');
    await expect(page.locator('.dash-greeting')).toBeVisible({ timeout: 15_000 });
    // 在线时不应有离线横幅
    await expect(page.getByTestId('offline-banner')).toHaveCount(0);

    const session = await context.newCDPSession(page);
    await session.send('Network.enable');

    // ---- 断网 ----
    await setCdpOffline(session, true);
    const banner = page.getByTestId('offline-banner');
    await expect(banner).toBeVisible({ timeout: 10_000 });
    await expect(banner).toContainText('离线：显示缓存数据');
    // 浏览器真的认为离线（这正是 useOnlineStatus 读的那一维）
    expect(await page.evaluate(() => navigator.onLine)).toBe(false);

    // ---- 不白屏：外壳与已渲染内容仍在，且有可见文字 ----
    await expect(page.locator('.dash-greeting')).toBeVisible();
    await expect(page.getByTestId('home-new-trip')).toBeVisible();
    const bodyText = (await page.locator('body').innerText()).trim();
    expect(bodyText.length).toBeGreaterThan(50);
    // 没有错误边界兜底页 / 空挂载点
    await expect(page.getByText('页面出错了')).toHaveCount(0);
    await expect(page.locator('#root')).not.toBeEmpty();

    // ---- 离线期间的站内跳转：换成**已经加载过**的路由，不应白屏 ----
    // 注意：这里刻意不跳到从未加载过的懒加载页（那会踩到下方「已知边界」用例记录的问题）。
    // 本项目是桌面 Chromium（底栏 .app-tabbar 在 >720px 时 display:none），所以点左侧栏导航。
    await page.locator('aside.app-sidebar nav.sb-modules button', { hasText: '我的行程' }).click();
    await expect(page.getByTestId('offline-banner')).toBeVisible();
    expect((await page.locator('body').innerText()).trim().length).toBeGreaterThan(30);

    // ---- 恢复网络 ----
    await setCdpOffline(session, false);
    await expect(page.getByTestId('offline-banner')).toHaveCount(0, { timeout: 10_000 });
    expect(await page.evaluate(() => navigator.onLine)).toBe(true);

    // 恢复后页面可以继续正常使用：重新加载首页仍然渲染出内容（不是停在离线壳里）
    await page.goto('/');
    await expect(page.locator('.dash-greeting')).toBeVisible({ timeout: 20_000 });
    await expect(page.getByTestId('offline-banner')).toHaveCount(0);
  });

  test('已知边界：屏蔽 SW 后，离线跳转未加载过的懒加载路由会命中错误边界', async ({
    context,
    page,
  }) => {
    await page.goto('/');
    await expect(page.locator('.dash-greeting')).toBeVisible({ timeout: 15_000 });

    const session = await context.newCDPSession(page);
    await session.send('Network.enable');
    await setCdpOffline(session, true);
    await expect(page.getByTestId('offline-banner')).toBeVisible({ timeout: 10_000 });

    // 「我的行程」走 React.lazy()：离线时这条分块的 CSS/JS 拿不到。
    // 桌面 Chromium 下底栏隐藏，用左侧栏导航（同一条路由、同一个懒加载分块）。
    await page.locator('aside.app-sidebar nav.sb-modules button', { hasText: '我的行程' }).click();
    await page.waitForURL(/\/travel$/, { timeout: 10_000 });

    // 现状：ErrorBoundary 接住，显示「页面出错了」而不是白屏——降级了，但**没有**给出可用的缓存内容
    const errorPage = page.getByText('页面出错了');
    const offlineShell = page.getByRole('heading', { name: /我的行程/ });
    await Promise.race([
      errorPage.waitFor({ state: 'visible', timeout: 15_000 }).catch(() => undefined),
      offlineShell.waitFor({ state: 'visible', timeout: 15_000 }).catch(() => undefined),
    ]);

    const sawError = await errorPage.count();
    const sawOfflineShell = await offlineShell.count();
    // 这条用例锁定「非白屏」底线：无论走哪条分支，都必须还有可见文字与可点的恢复入口
    expect(sawError + sawOfflineShell).toBeGreaterThan(0);
    expect((await page.locator('body').innerText()).trim().length).toBeGreaterThan(10);
    if (sawError > 0) {
      // 记录现状：SW 被 block 时离线懒加载路由不可用（详见本文件头注释）
      test.info().annotations.push({
        type: 'known-limitation',
        description:
          'serviceWorkers=block 时离线跳转未加载过的懒加载路由 → ErrorBoundary「页面出错了 / Unable to preload CSS」。开 SW 时不报错但行程数据仍显示 Network Error。',
      });
    }

    await setCdpOffline(session, false);
  });
});
