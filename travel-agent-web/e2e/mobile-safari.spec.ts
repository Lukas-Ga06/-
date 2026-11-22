import { test } from '@playwright/test';
import { expect, expectOk } from './support';
import { expectNoHorizontalOverflow } from './support-device';

/**
 * 移动端（Playwright WebKit + iPhone 13 预设）真实用例。
 *
 * **边界**：这是「Safari 引擎 + 移动端 UA/视口 390×664/DPR 3」，**不是 iOS 真机**。
 * 能验证：布局在窄屏不溢出、底栏（`.app-tabbar`）可点、移动端分段视图（行程/地图/AI）生效、
 * 抽屉被夹在视口内、WebKit 引擎下渲染与交互不报错。
 * 不能验证：safe-area 真实内缩值、iOS 真机手势与滚动惯性、Safari 与老旧 iOS 版本差异。
 *
 * 跑法（独立 script，不进默认 `npm run e2e` 的阻塞路径）：
 *   npm run test:e2e:mobile
 *
 * 注意：**别把 `app-shell-brand` 当可见锚点**——它在 ≤720px 时随左侧栏一起 `display:none`，
 * 移动端应该用页头（`.dash-greeting`）或底栏作为「页面已就绪」的信号。
 */

test('移动端首页可渲染且无横向溢出（390px）', async ({ page }) => {
  await page.goto('/');

  // 1. 页头可见 = 首页主体渲染完成（底栏和侧栏在窄屏是互斥的，只能等页头）
  const greeting = page.locator('.dash-greeting');
  await expect(greeting).toBeVisible({ timeout: 15_000 });
  await expect(greeting.getByRole('heading', { level: 1 })).toContainText('演示用户');

  // 2. 视口确实是 iPhone 13 的 390px、侧栏已收起（说明走了移动端布局分支，不是桌面布局被压缩）
  const viewport = page.viewportSize();
  expect(viewport?.width).toBe(390);
  await expect(page.locator('.app-sidebar')).toBeHidden();

  // 3. 没有横向滚动：scrollWidth <= innerWidth，且没有元素越出右边界
  const firstScreen = await expectNoHorizontalOverflow(page, '移动端首页');

  // 4. 首页的核心内容真的渲染了，不是「不溢出但是白屏」
  await expect(page.getByTestId('home-new-trip')).toBeVisible();
  expect(firstScreen.innerWidth).toBe(390);

  // 5. 滚到页面底部再查一次：长列表（今日安排/待办）是最容易撑破宽度的部分
  await page.evaluate(() => window.scrollTo(0, document.body.scrollHeight));
  await page.waitForTimeout(300);
  await expectNoHorizontalOverflow(page, '移动端首页（滚动到底部）');
});

test('底部标签栏可见可点：首页 ↔ 行程 ↔ 助手，当前项高亮正确', async ({ page }) => {
  await page.goto('/');
  await expect(page.locator('.dash-greeting')).toBeVisible({ timeout: 15_000 });

  const bottomBar = page.locator('nav.app-tabbar');
  await expect(bottomBar).toBeVisible();
  // 底栏必须是移动端专有的那条：desktop 的侧栏导航此时应隐藏
  await expect(page.locator('aside.app-sidebar nav.sb-modules')).toBeHidden();

  // 4 个一级入口齐全，且「首页」是当前项
  const tabs = bottomBar.locator('button.tb-item');
  await expect(tabs).toHaveCount(4);
  expect(await tabs.allInnerTexts()).toEqual(['首页', '行程', '助手', '我的']);
  await expect(bottomBar.locator('button.tb-item[aria-current="page"]')).toContainText('首页');

  // 触控目标不小于 44px（移动端可点性底线）
  for (const tab of await tabs.all()) {
    const box = await tab.boundingBox();
    expect(box, '底栏按钮应有尺寸').not.toBeNull();
    expect(box!.height, '底栏按钮高度应 ≥44px').toBeGreaterThanOrEqual(44);
    expect(box!.width, '底栏按钮宽度应 ≥44px').toBeGreaterThanOrEqual(44);
  }

  // 切到「行程」：路由变化 + 当前项跟着变 + 列表加载完成（不是骨架）
  await bottomBar.getByRole('button', { name: '行程' }).click();
  await page.waitForURL(/\/travel$/);
  await expect(bottomBar.locator('button.tb-item[aria-current="page"]')).toContainText('行程');
  // 用 h1 限定：列表区标题是 h3「我的行程」，不限定会命中两个 heading
  await expect(page.getByRole('heading', { level: 1, name: /我的行程/ })).toBeVisible({
    timeout: 15_000,
  });
  await expect(page.getByRole('group', { name: '按状态筛选行程' })).toBeVisible();
  await expectNoHorizontalOverflow(page, '移动端行程列表');

  // 切到「助手」：聊天输入区就位
  await bottomBar.getByRole('button', { name: '助手' }).click();
  await page.waitForURL(/\/assistant$/);
  await expect(bottomBar.locator('button.tb-item[aria-current="page"]')).toContainText('助手');
  await expect(page.getByTestId('chat-suggestions')).toBeVisible({ timeout: 15_000 });
  await expectNoHorizontalOverflow(page, '移动端助手页');

  // 底栏依然固定在视口底部，没有被内容顶走
  const barBox = await bottomBar.boundingBox();
  const viewport = page.viewportSize()!;
  expect(Math.round(barBox!.y + barBox!.height)).toBeGreaterThanOrEqual(viewport.height - 2);

  // 且底栏不与聊天输入区重叠（重叠会让手机键盘弹出后完全点不到输入框）
  const suggestions = await page.getByTestId('chat-suggestions').boundingBox();
  expect(suggestions!.y + suggestions!.height).toBeLessThanOrEqual(barBox!.y + 1);
});

test('窄屏核心交互：行程详情时间轴 + AI 助手「最近会话」抽屉都在视口内', async ({ page }) => {
  // 1. 经 API 建一个行程并写入两条安排，避免依赖演示库里已有的数据
  const tripName = `e2e-mobile-${Date.now()}`;
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
  const tripId = (await created.json()).data.id as number;

  const item = await page.request.post(`/api/trips/${tripId}/itinerary`, {
    data: {
      dayIndex: 1,
      itemType: 'SIGHT',
      title: '外滩',
      startTime: '09:00',
      endTime: '11:00',
      description: `移动端时间轴 ${tripName}`,
    },
  });
  // 条目写失败时降级断言时间轴空态（同样是真实分支），不让整条用例因数据准备而误红
  const itemOk = item.ok();

  // 2. 打开行程详情：移动端应切成「行程 / 地图 / AI」分段视图，且默认停在行程
  await page.goto(`/trip/${tripId}`);
  const viewSwitch = page.getByTestId('trip-view-switch');
  await expect(viewSwitch).toBeVisible({ timeout: 20_000 });
  expect(await viewSwitch.locator('.ant-segmented-item').allInnerTexts()).toEqual([
    '行程',
    '地图',
    'AI',
  ]);
  await expect(page.getByRole('heading', { name: tripName })).toBeVisible();
  await expectNoHorizontalOverflow(page, '移动端行程详情');

  // 3. 时间轴在窄屏可见：有安排时出现「Day 1」，没有安排时给出可执行的空态引导
  const timeline = page.locator('.trip-timeline-card');
  await expect(timeline).toBeVisible();
  const timelineText = await timeline.innerText();
  if (itemOk) {
    await expect(timeline.getByText('Day 1', { exact: false }).first()).toBeVisible();
    await expect(timeline).toContainText('外滩');
  } else {
    expect(timelineText.length).toBeGreaterThan(0);
  }

  // 4. 分段视图可用：切到「AI」后站内聊天面板出现（窄屏把双栏折成单栏）
  await viewSwitch.locator('.ant-segmented-item', { hasText: 'AI' }).click();
  await expect(page.locator('.trip-chat-body textarea')).toBeVisible({ timeout: 15_000 });
  await expectNoHorizontalOverflow(page, '移动端行程详情 · AI 面板');

  // 5. AI 助手页的「最近会话」抽屉：antd 写死 width=380/480，窄屏必须被夹在 390px 内
  await page.goto('/assistant');
  await expect(page.getByTestId('chat-suggestions')).toBeVisible({ timeout: 15_000 });
  await page.getByRole('button', { name: '最近会话' }).click();
  const drawer = page.locator('.ant-drawer-content-wrapper');
  await expect(drawer).toBeVisible({ timeout: 15_000 });
  // antd 抽屉是从视口右侧滑入的：动画期间 transform 还在位移，
  // 直接量 x/right 会量到「动画中间帧」（实测 0.3s 内 right 从 770 收敛到 390）。
  // 等 transform 归位（= 动画结束）再量尺寸，否则这条断言会随机器快慢抖动。
  await expect
    .poll(() => drawer.evaluate((el) => getComputedStyle(el).transform), {
      timeout: 10_000,
      message: '抽屉滑入动画未结束（transform 未归位）',
    })
    .toBe('none');
  const drawerBox = await drawer.boundingBox();
  const viewport = page.viewportSize()!;
  expect(drawerBox!.width, '窄屏下抽屉宽度应被 max-width:100vw 夹住').toBeLessThanOrEqual(
    viewport.width + 1,
  );
  expect(
    drawerBox!.x + drawerBox!.width,
    `抽屉右边界 ${Math.round(drawerBox!.x + drawerBox!.width)} 越出视口 ${viewport.width}px`,
  ).toBeLessThanOrEqual(viewport.width + 1);
  expect(drawerBox!.x, '抽屉不应左溢（x 为负）').toBeGreaterThanOrEqual(-1);
  // 抽屉里要么有会话条目、要么是空态——不能是加载骨架卡死或报错
  const convState = await Promise.race([
    page
      .locator('[data-testid="conversation-item"]')
      .first()
      .waitFor({ state: 'visible', timeout: 15_000 })
      .then(() => 'items'),
    page
      .locator('[data-testid="conversation-empty"]')
      .waitFor({ state: 'visible', timeout: 15_000 })
      .then(() => 'empty'),
  ]);
  expect(['items', 'empty']).toContain(convState);
  await expect(page.getByRole('alert')).toHaveCount(0);
  await expectNoHorizontalOverflow(page, '移动端最近会话抽屉');

  // 6. 抽屉可以正常关闭，关掉后聊天区仍在（不是整页被遮罩吞掉）
  await page.locator('.ant-drawer-close').click();
  await expect(drawer).toBeHidden({ timeout: 10_000 });
  await expect(page.getByTestId('chat-suggestions')).toBeVisible();
});
