import { expect, test } from '@playwright/test';

/**
 * 结构化规划表单闭环（规则路径，不依赖 LLM）：
 * 表单填写 → POST /api/agent/stream（fetch SSE，非 GET EventSource）→ 方案确认弹窗 → 确认 → 新行程页。
 * 登录走 API 注入 Cookie（与 smoke.spec 一致），只断言稳定区域，避免与表单视觉迭代互相拖累。
 * 内置目录城市选「上海」：无 LLM 时规则引擎也能生成可确认方案。
 */

async function login(page: import('@playwright/test').Page) {
  const res = await page.request.post('/api/auth/login', {
    data: { username: 'demo', password: 'demo123' },
  });
  expect(res.ok()).toBeTruthy();
}

test('攻略整合模式：空提交校验 → 填表 → POST 流式 → 确认 → 新行程', async ({ page }) => {
  await login(page);
  await page.goto('/planner');
  await expect(page.getByText('规划新行程')).toBeVisible({ timeout: 15_000 });

  // 边界：未填目的地直接提交 → 提示而非发请求
  await page.getByRole('button', { name: '整合攻略并生成计划' }).click();
  await expect(page.getByText('先填写目的地')).toBeVisible();

  // 填写攻略模式表单（默认即攻略整合模式）
  await page.getByPlaceholder('目的地，例如：东京、巴黎、伦敦').fill('上海');
  await page.getByPlaceholder(/标题或来源/).fill('小红书｜上海三日');
  await page.getByPlaceholder('粘贴攻略正文……').fill(
    '第一天上午外滩，中午南京路吃生煎，下午豫园，晚上陆家嘴夜景。第二天上海博物馆、田子坊、新天地。第三天迪士尼。住宿人民广场附近。',
  );
  const days = page.getByPlaceholder('几天？');
  await days.fill('3');
  await days.press('Tab');

  // 提交必须走 POST 流式端点（长文本走请求体），而不是 GET EventSource
  const streamResp = page.waitForResponse(
    (r) => r.url().includes('/api/agent/stream') && r.request().method() === 'POST',
  );
  await page.getByRole('button', { name: '整合攻略并生成计划' }).click();
  const resp = await streamResp;
  expect(resp.status()).toBe(200);
  expect(resp.headers()['content-type']).toContain('text/event-stream');

  // 规则引擎生成方案 → 确认弹窗（Human-in-the-loop）
  const dialog = page.getByRole('dialog');
  await expect(dialog).toBeVisible({ timeout: 25_000 });
  await expect(dialog.getByText(/方案确认/)).toBeVisible();

  // 确认 → 新建行程并跳转行程页
  await dialog.getByRole('button', { name: '确认修改' }).click();
  await page.waitForURL(/\/trip\/\d+/, { timeout: 25_000 });
  await expect(page.getByRole('button', { name: /路书/ })).toBeVisible({ timeout: 15_000 });
});

test('从零规划模式：填目的地与天数 → 生成 → 确认 → 新行程', async ({ page }) => {
  await login(page);
  await page.goto('/planner');
  await expect(page.getByText('规划新行程')).toBeVisible({ timeout: 15_000 });

  // 切到从零模式：攻略正文段落消失，提交按钮文案变化
  await page.getByRole('button', { name: /我还没有攻略/ }).click();
  await expect(page.getByPlaceholder('粘贴攻略正文……')).toHaveCount(0);
  await page.getByPlaceholder('目的地，例如：东京、巴黎、伦敦').fill('上海');
  const days = page.getByPlaceholder('几天？');
  await days.fill('2');
  await days.press('Tab');

  const streamResp = page.waitForResponse(
    (r) => r.url().includes('/api/agent/stream') && r.request().method() === 'POST',
  );
  await page.getByRole('button', { name: '根据我的需求生成计划' }).click();
  expect((await streamResp).status()).toBe(200);

  const dialog = page.getByRole('dialog');
  await expect(dialog).toBeVisible({ timeout: 25_000 });
  await dialog.getByRole('button', { name: '确认修改' }).click();
  await page.waitForURL(/\/trip\/\d+/, { timeout: 25_000 });
});