import { test } from '@playwright/test';
import { expect, expectOk } from './support';

/**
 * 结构化规划表单闭环（规则路径，不依赖 LLM）：
 * 三步填需求（§8.3）→ POST /api/agent/stream（fetch SSE，非 GET EventSource）→ 方案确认弹窗 → 确认 → 新行程页。
 *
 * 登录态来自 globalSetup 的 storageState，用例不再各自调 /api/auth/login
 * （否则每轮 8 次 auth 请求，两轮必触发 10/300s 的 IP 限流）。
 * 定位优先 data-testid / role：planner-destination、planner-next、planner-submit、
 * plan-diff-dialog、plan-diff-confirm。
 * 内置目录城市选「上海」：无 LLM 时规则引擎也能生成可确认方案。
 */

function waitForPlanStream(page: import('@playwright/test').Page) {
  return page.waitForResponse(
    (r) => r.url().includes('/api/agent/stream') && r.request().method() === 'POST',
  );
}

test('攻略整合模式：空继续校验 → 三步填表 → POST 流式 → 确认 → 新行程', async ({ page }) => {
  await page.goto('/planner');
  const destination = page.getByTestId('planner-destination');
  await expect(destination).toBeVisible({ timeout: 15_000 });

  // 边界：第 1 步未填目的地就继续 → 原地提示，不进入下一步也不发请求
  await page.getByTestId('planner-next').click();
  await expect(page.getByTestId('planner-required-hint')).toContainText('先填写目的地');
  await expect(page.getByTestId('planner-step-indicator')).toContainText('步骤 1/3');

  // 第 1 步：目的地 + 天数（攻略模式允许稍后再补正文）
  await destination.fill('上海');
  const days = page.getByPlaceholder('几天？');
  await days.fill('3');
  await days.press('Tab');
  await page.getByTestId('planner-next').click();
  await expect(page.getByTestId('planner-step-indicator')).toContainText('步骤 2/3');

  // 第 2 步：攻略正文（填写攻略模式表单，默认即攻略整合模式）
  await page.getByPlaceholder(/标题或来源/).fill('小红书｜上海三日');
  await page
    .getByPlaceholder('粘贴攻略正文……')
    .fill(
      '第一天上午外滩，中午南京路吃生煎，下午豫园，晚上陆家嘴夜景。第二天上海博物馆、田子坊、新天地。第三天迪士尼。住宿人民广场附近。',
    );
  await page.getByTestId('planner-next').click();

  // 第 3 步：确认结构化摘要后再生成
  await expect(page.getByTestId('planner-step-indicator')).toContainText('步骤 3/3');
  await expect(page.getByTestId('planner-summary')).toContainText('上海');

  // 提交必须走 POST 流式端点（长文本走请求体），而不是 GET EventSource
  const streamResponse = waitForPlanStream(page);
  await page.getByTestId('planner-submit').click();
  const resp = await streamResponse;
  await expectOk(resp, 'POST /api/agent/stream（攻略整合）');
  expect(resp.headers()['content-type']).toContain('text/event-stream');

  // 规则引擎生成方案 → 确认弹窗（Human-in-the-loop）：外层用 role=dialog，内容区用 testid
  const dialog = page.getByRole('dialog');
  await expect(dialog).toBeVisible({ timeout: 25_000 });
  await expect(dialog).toContainText('方案确认');
  await expect(page.getByTestId('plan-diff-dialog')).toBeVisible();

  // 确认 → 新建行程并跳转行程页
  await page.getByTestId('plan-diff-confirm').click();
  await page.waitForURL(/\/trip\/\d+/, { timeout: 25_000 });
  // 路书导出按设计 P1-05 收进「更多行程操作」二级菜单（2026-11-20 按新布局修正断言）
  await page.getByRole('button', { name: '更多行程操作' }).click();
  await expect(page.getByRole('menuitem', { name: /导出路书 PDF/ })).toBeVisible({
    timeout: 15_000,
  });
});

test('从零规划模式：三步填目的地与天数 → 生成 → 确认 → 新行程', async ({ page }) => {
  await page.goto('/planner');
  const destination = page.getByTestId('planner-destination');
  await expect(destination).toBeVisible({ timeout: 15_000 });

  // 第 1 步：目的地 + 天数
  await destination.fill('上海');
  const days = page.getByPlaceholder('几天？');
  await days.fill('2');
  await days.press('Tab');
  await page.getByTestId('planner-next').click();

  // 第 2 步切到从零模式：攻略正文段落消失，可以整段跳过
  await page.getByRole('button', { name: /我还没有攻略/ }).click();
  await expect(page.getByPlaceholder('粘贴攻略正文……')).toHaveCount(0);
  await page.getByTestId('planner-next').click();

  // 第 3 步：生成计划
  await expect(page.getByTestId('planner-summary')).toContainText('上海');
  const streamResponse = waitForPlanStream(page);
  await page.getByTestId('planner-submit').click();
  await expectOk(await streamResponse, 'POST /api/agent/stream（从零规划）');

  const dialog = page.getByRole('dialog');
  await expect(dialog).toBeVisible({ timeout: 25_000 });
  await expect(page.getByTestId('plan-diff-dialog')).toBeVisible();
  await page.getByTestId('plan-diff-confirm').click();
  await page.waitForURL(/\/trip\/\d+/, { timeout: 25_000 });
});
