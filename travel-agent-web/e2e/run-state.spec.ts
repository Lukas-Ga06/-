import { test, type Page } from '@playwright/test';
import { expect, expectOk } from './support';

/**
 * 统一运行状态（P1-E）的核心场景：
 *
 * 1) 「部分同步失败 + 重试」——确认提交后 ExecutionResultPanel 里模块级失败要显示成
 *    failed + 重试（不是「暂无数据」），重试成功后同一面板翻转成完成；
 *    接口用 page.route 桩住，不依赖真实 LLM / 后端执行链路（R6：测试不触网）。
 * 2) 「地图失败但时间轴可用」——高德 SDK 不可达时行程页给出 failed 状态与重试出口，
 *    主栏时间轴照常渲染真实行程项；SDK 用真实失败路径（abort 请求），不改生产代码。
 * 3) 「预算降级」——SSE 桩流里下发 `budget_degraded`：过程时间线必须出现「降级」阶段、
 *    折叠态给出降级结论与原因，整块状态是「已降级」而不是绿色「完成」。
 *    这条同时是 `EVENT_NAMES` 的回归：白名单漏登记时浏览器直接丢弃该事件，断言必红。
 * 4) 「任务冲突/重派」——桩流里下发 `conflict`：时间线出现「冲突/重派」阶段，
 *    状态降级但**不**冒充「本次结果走了降级路径」。
 *
 * 3/4 两条的判定只看时间线（阶段标签 + 状态 + 折叠态结论），不看日志：
 * 过程事件一旦被传输层丢掉，这里就没有可断言的 DOM。
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

/**
 * SSE 桩：`run` → `start` → `complete`（带方案确认）。
 * 走的是聊天面板真实的 EventSource 传输，而不是「打开 URL 凭 planId 恢复」那条路径——
 * 后者在刷新恢复上有已知竞态（见卡 #5 回报），不适合当门禁用例。
 */
const PENDING_STREAM = [
  'event: run',
  `data: ${JSON.stringify({ runId: 'e2e-run-runstate' })}`,
  '',
  'event: start',
  'data: {}',
  '',
  'event: complete',
  `data: ${JSON.stringify(PENDING_PLAN)}`,
  '',
  '',
].join('\n');

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
  await page.route('**/api/access-ticket**', (route) =>
    route.fulfill(ok({ ticket: 'e2e-ticket', expiresInSeconds: 300 })),
  );
  await page.route('**/api/agent/stream*', (route) =>
    route.fulfill({ status: 200, contentType: 'text/event-stream', body: PENDING_STREAM }),
  );
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

  // 真实入口：在聊天面板发一句话 → SSE 返回待确认方案 → 确认弹窗 → 确认提交
  await page.goto('/assistant');
  await expect(page.getByTestId('chat-empty')).toBeVisible({ timeout: 15_000 });

  const input = page.getByRole('textbox').first();
  await input.fill('帮我规划一次杭州两日游');
  await page.getByRole('button', { name: '发送' }).click();

  const dialog = page.getByRole('dialog');
  await expect(dialog).toBeVisible({ timeout: 20_000 });
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

/** 把若干 SSE 帧拼成一段响应体（GET EventSource 一次性读完；事件名走真实监听白名单） */
function sse(...frames: [string, unknown][]): string {
  return frames
    .map(([event, data]) => `event: ${event}\ndata: ${JSON.stringify(data)}\n\n`)
    .join('');
}

/** 助手页的真实入口：发一句话，让 streamAgentChat 走桩住的 GET 通道 */
async function sendInAssistant(page: Page, message: string) {
  await page.route('**/api/access-ticket**', (route) =>
    route.fulfill(ok({ ticket: 'e2e-ticket', expiresInSeconds: 300 })),
  );
  await page.goto('/assistant');
  await expect(page.getByTestId('chat-empty')).toBeVisible({ timeout: 15_000 });
  await page.getByRole('textbox').first().fill(message);
  await page.getByRole('button', { name: '发送' }).click();
}

test('过程时间线：预算降级显示成「降级」阶段与原因，状态不是完成', async ({ page }) => {
  const detail = '预算耗尽，已跳过审核与重规划（未做路线/天气/预算硬约束校验）';
  await page.route('**/api/agent/stream*', (route) =>
    route.fulfill({
      status: 200,
      contentType: 'text/event-stream',
      body: sse(
        ['run', { runId: 'e2e-run-budget' }],
        ['start', {}],
        ['intent', { intent: 'PLAN_TRIP' }],
        ['planning', {}],
        ['budget_degraded', { stage: 'review', detail, remainingMs: 0 }],
        [
          'budget_degraded',
          { stage: 'narration', detail: '预算耗尽，跳过文案润色（使用模板文案）', remainingMs: 0 },
        ],
        ['complete', { status: 'COMPLETED', message: '已按预算优先给出方案，审核被跳过。' }],
      ),
    }),
  );

  await sendInAssistant(page, '帮我规划一次杭州两日游');

  const panel = page.getByTestId('agent-activity-panel');
  await expect(panel).toBeVisible({ timeout: 20_000 });

  // ① 整块状态是「已降级」，不是绿色「完成」——降级不许并进普通成功态
  const status = page.getByTestId('agent-activity-status');
  await expect(status).toHaveAttribute('data-status', 'degraded');
  await expect(status).toContainText('已降级');

  // ② 折叠态就能看到「走了降级路径」与原因（用户不必展开）
  const notice = page.getByTestId('agent-activity-degrade');
  await expect(notice).toBeVisible();
  await expect(notice).toContainText('本次结果走了降级路径');
  await expect(notice).toContainText('预算耗尽，已跳过审核与重规划');
  await expect(notice).toContainText('共 2 项');

  // ③ 展开后：降级行带「降级」阶段标签，原因来自服务端 detail（不是前端编的）
  await page.getByRole('button', { name: '查看运行详情' }).click();
  const degradeRows = page.locator('.aa-row[data-event="budget_degraded"]');
  await expect(degradeRows).toHaveCount(2);
  await expect(degradeRows.first()).toHaveAttribute('data-stage', 'degrade');
  await expect(degradeRows.first().locator('.aa-stage')).toHaveText('降级');
  await expect(degradeRows.first()).toContainText(detail);
  // 结论行仍在：本轮仍然收尾了，只是降级收尾
  await expect(page.locator('.aa-row[data-event="complete"]')).toContainText('本轮运行完成');
});

test('过程时间线：任务冲突/重派独立成阶段，状态降级但不冒充降级路径', async ({ page }) => {
  await page.route('**/api/agent/stream*', (route) =>
    route.fulfill({
      status: 200,
      contentType: 'text/event-stream',
      body: sse(
        ['run', { runId: 'e2e-run-conflict' }],
        ['start', {}],
        ['planning', {}],
        ['delegate', { receiver: 'planner-agent', taskType: 'PLAN', dependsOn: [], attempt: 0 }],
        [
          'conflict',
          {
            receiver: 'reflect-agent',
            taskType: 'REVIEW',
            missingDeps: ['t-plan'],
            reason: '前置未完成',
          },
        ],
        ['complete', { status: 'COMPLETED', message: '已生成方案。' }],
      ),
    }),
  );

  await sendInAssistant(page, '帮我规划一次上海两日游');

  const panel = page.getByTestId('agent-activity-panel');
  await expect(panel).toBeVisible({ timeout: 20_000 });

  // ① 冲突是过程缺口：整块状态降级，但不能说「走了降级路径」（那是预算/依赖降级的结论）
  await expect(page.getByTestId('agent-activity-status')).toHaveAttribute(
    'data-status',
    'degraded',
  );
  await expect(page.getByTestId('agent-activity-degrade')).toHaveCount(0);

  // ② 展开后：冲突行属于「冲突/重派」阶段，阶段标签只出现一次
  await page.getByRole('button', { name: '查看运行详情' }).click();
  const conflictRow = page.locator('.aa-row[data-event="conflict"]');
  await expect(conflictRow).toHaveAttribute('data-stage', 'dispatch');
  await expect(conflictRow).toContainText('任务冲突：reflect-agent/REVIEW 前置未完成，已回收');
  await expect(page.locator('.aa-stage[data-stage="dispatch"]')).toHaveCount(1);
  await expect(page.locator('.aa-stage[data-stage="dispatch"]')).toHaveText('冲突/重派');
  // 委派是正常编排，归「规划」而不是「冲突/重派」
  await expect(page.locator('.aa-row[data-event="delegate"]')).toHaveAttribute(
    'data-stage',
    'plan',
  );
});
