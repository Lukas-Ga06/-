import { test } from '@playwright/test';
import { expect, expectOk } from './support';

/**
 * 统一运行状态（P1-E）的两条核心场景：
 *
 * 1) 「部分同步失败 + 重试」——确认提交后 ExecutionResultPanel 里模块级失败要显示成
 *    failed + 重试（不是「暂无数据」），重试成功后同一面板翻转成完成；
 *    接口用 page.route 桩住，不依赖真实 LLM / 后端执行链路（R6：测试不触网）。
 * 2) 「地图失败但时间轴可用」——高德 SDK 不可达时行程页给出 failed 状态与重试出口，
 *    主栏时间轴照常渲染真实行程项；SDK 用真实失败路径（abort 请求），不改生产代码。
 */

/** 后端统一响应包络：{ code, data }（与 src/api/client.ts 一致） */
function ok(data: unknown) {
  return {
    status: 200,
    contentType: 'application/json',
    body: JSON.stringify({ code: 0, data }),
  };
}

const PLAN_ID = 'e2e-plan-runstate';
const EXECUTION_ID = 'e2e-exec-runstate';

const PENDING_PLAN = {
  planId: PLAN_ID,
  message: '方案已生成，确认后写入行程。',
  status: 'NEED_CONFIRMATION',
  confirmation: {
    planId: PLAN_ID,
    summary: '新增 3 个行程项',
    riskLevel: 'LOW',
    contentHash: 'a'.repeat(64),
  },
  diff: {
    changes: [{ type: 'ADD', title: '外滩', dayIndex: 1, cost: 0 }],
    budgetBefore: 3000,
    budgetAfter: 3000,
  },
};

const FAILED_EXECUTION = {
  executionId: EXECUTION_ID,
  planId: PLAN_ID,
  tripId: 1,
  status: 'PARTIAL_FAILED',
  modules: [
    { module: 'calendar', status: 'SUCCEEDED', detail: '已写入 3 个时间块' },
    { module: 'budget', status: 'FAILED', error: '预算服务超时' },
    { module: 'footprint', status: 'SUCCEEDED', detail: '已标记 2 个计划足迹' },
  ],
};

const RECOVERED_EXECUTION = {
  ...FAILED_EXECUTION,
  status: 'SUCCEEDED',
  modules: [
    { module: 'calendar', status: 'SUCCEEDED', detail: '已写入 3 个时间块' },
    { module: 'budget', status: 'SUCCEEDED', detail: '预算快照已写入' },
    { module: 'footprint', status: 'SUCCEEDED', detail: '已标记 2 个计划足迹' },
  ],
};

test('部分同步失败 → 模块失败态可重试 → 重试后翻转成完成', async ({ page }) => {
  // 只桩住这一条链路；其余请求（/auth/me 等）仍走隔离后端
  await page.route(`**/api/agent/plans/${PLAN_ID}`, (route) => route.fulfill(ok(PENDING_PLAN)));
  await page.route('**/api/agent/confirm', (route) =>
    route.fulfill(
      ok({
        planId: PLAN_ID,
        tripId: 1,
        message: '已提交，部分模块同步失败。',
        status: 'COMPLETED',
        execution: FAILED_EXECUTION,
      }),
    ),
  );
  await page.route(`**/api/agent/executions/${EXECUTION_ID}/retry`, (route) =>
    route.fulfill(ok(RECOVERED_EXECUTION)),
  );

  // 凭 URL 上的 planId 找回待确认方案（与刷新恢复是同一条真实路径），再走确认提交
  await page.goto(`/assistant?planId=${PLAN_ID}`);
  const dialog = page.getByRole('dialog');
  await expect(dialog).toBeVisible({ timeout: 15_000 });
  await expect(page.getByTestId('plan-diff-dialog')).toBeVisible();

  await page.getByTestId('plan-diff-confirm').click();

  const panel = page.getByTestId('execution-result-panel');
  await expect(panel).toBeVisible({ timeout: 15_000 });

  // ① 整体状态是「部分同步失败」= partial（warn），不是失败也不是空
  const status = panel.locator('.exec-status');
  await expect(status).toHaveAttribute('data-status', 'partial');
  await expect(status).toContainText('部分同步失败');

  // ② 失败模块行单独显示失败原因 + 重试；成功模块行保持「已同步」
  const budgetRow = panel.locator('[data-module="budget"]');
  await expect(budgetRow.locator('[data-status="failed"]')).toBeVisible();
  await expect(budgetRow).toContainText('预算服务超时');
  await expect(budgetRow.locator('.em-retry-btn')).toBeVisible();
  await expect(panel.locator('[data-module="calendar"]')).toContainText('已同步');
  // 错误没有被伪装成「暂无数据」
  await expect(panel).not.toContainText('暂无内容');

  // ③ 重试：只重试失败模块，成功后同一面板翻转成完成
  await budgetRow.locator('.em-retry-btn').click();
  await expect(panel.locator('.exec-status')).toHaveAttribute('data-status', 'complete', {
    timeout: 15_000,
  });
  await expect(panel.locator('.exec-status')).toContainText('全部同步完成');
  await expect(panel.locator('[data-module="budget"]')).toContainText('已同步');
  await expect(panel).not.toContainText('预算服务超时');
});

test('地图失败但时间轴可用：失败态可重试，主栏照常渲染行程项', async ({ page }) => {
  // 造一个带行程项的行程：时间轴要有真实内容，才能证明「地图坏了主栏还在」
  const tripName = `e2e-map-${Date.now()}`;
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

  const item = await page.request.post(`/api/trips/${tripId}/itinerary`, {
    data: {
      dayIndex: 1,
      itemType: 'attraction',
      title: '外滩',
      startTime: '09:00',
      endTime: '11:00',
    },
  });
  await expectOk(item, '新增行程项 POST /api/trips/:id/itinerary');

  // 高德 SDK 不可达：走真实的加载失败路径（不是改代码、也不是伪造状态）
  await page.route('https://webapi.amap.com/**', (route) => route.abort());

  await page.goto(`/trip/${tripId}`);

  const unavailable = page.getByTestId('map-unavailable');
  await expect(unavailable).toBeVisible({ timeout: 20_000 });
  // failed 档 + 可重试 + 不暴露 Key / 环境变量
  await expect(unavailable.locator('[data-status="failed"]')).toBeVisible();
  await expect(unavailable).toContainText('地图暂时不可用');
  await expect(unavailable).toContainText('行程时间轴与 AI 助手仍可正常使用');
  await expect(page.getByTestId('map-retry')).toBeVisible();

  // 时间轴（主栏）照常可用：同一行程项仍在页面里
  const timeline = page.locator('.trip-timeline-card');
  await expect(timeline).toBeVisible();
  await expect(timeline).toContainText('外滩');

  // 重试仍失败时不白屏、不丢时间轴（重试按钮是真的会重新加载地图）
  await page.getByTestId('map-retry').click();
  await expect(page.getByTestId('map-unavailable')).toBeVisible({ timeout: 20_000 });
  await expect(page.locator('.trip-timeline-card')).toContainText('外滩');
  await expect(page.getByText('页面出错了')).toHaveCount(0);

  // 「查看时间轴」是第二个出口：点击后仍在行程页（不跳走、不崩）
  await page.getByTestId('map-view-timeline').click();
  await expect(page).toHaveURL(new RegExp(`/trip/${tripId}$`));
  await expect(page.locator('.trip-timeline-card')).toContainText('外滩');
});