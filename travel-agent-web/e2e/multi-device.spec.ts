import { test } from '@playwright/test';
import { expect } from './support';
import { fetchConversationMessages, postAgentTurn } from './support-device';

/**
 * 多端并发：同一账号、两个独立 browserContext（各自一份 storageState）模拟两台设备。
 *
 * 要证明的三件事：
 * 1. 两端**各自**能正常对话（都用真实 `POST /api/agent/stream`，同账号 Cookie）；
 * 2. 两端**互不阻塞**：并发发出的两轮都能自己跑完，谁也不等谁（时间上不串行叠加）；
 * 3. 服务端**不串号**：每段会话的记忆只包含自己的消息（没有对方的问答混进来），
 *    且全程没有 5xx。
 *
 * 用两台「设备」而不是两个标签页：标签页共享同一 context 的 Cookie 与缓存，
 * 测的是前端状态隔离；两台设备各自独立 Cookie 容器，才接近「手机 + 电脑同时登录」。
 *
 * 注意：本用例直接打 API 而不点 UI——要严格断言服务端按 conversationId 的隔离语义，
 * UI 渲染时序会把这类断言变成 flaky。UI 侧的多端并发见 mobile-safari 项目的底栏/抽屉用例。
 */

test('同账号两台设备并发对话：各自成功、互不阻塞、记忆不串号', async ({ playwright }) => {
  test.slow(); // 无 LLM 时规则引擎也可能跑几秒，放宽到 3 倍超时

  const baseURL = test.info().project.use.baseURL!;
  const storageState = test.info().project.use.storageState!;

  // 两台设备：两个独立 context（携带同一份登录态）
  const phone = await playwright.request.newContext({ baseURL, storageState });
  const laptop = await playwright.request.newContext({ baseURL, storageState });

  const runTag = `multi-${Date.now().toString(36)}`;
  const phoneConv = `${runTag}-phone`;
  const laptopConv = `${runTag}-laptop`;

  // 两个 context 上记录所有 5xx，最后统一断言
  const serverErrors: string[] = [];
  for (const [name, ctx] of [
    ['phone', phone],
    ['laptop', laptop],
  ] as const) {
    ctx.on('response', (response) => {
      if (response.status() >= 500) {
        serverErrors.push(
          `${name} ${response.status()} ${response.request().method()} ${response.url()}`,
        );
      }
    });
  }

  try {
    // ---- 1. 并发首轮：两端同时发出，谁的响应都不应等另一条 ----
    const [phoneTurn, laptopTurn] = await Promise.all([
      postAgentTurn(phone, {
        message: `记一下明天办签证 ${runTag}`,
        conversationId: phoneConv,
        scope: `device:phone:${runTag}`,
      }),
      postAgentTurn(laptop, {
        message: `上海有什么适合亲子游的地方？ ${runTag}`,
        conversationId: laptopConv,
        scope: `device:laptop:${runTag}`,
      }),
    ]);

    expect(phoneTurn.status, '手机端流式请求应 200').toBe(200);
    expect(laptopTurn.status, '电脑端流式请求应 200').toBe(200);
    expect(phoneTurn.result?.status, '手机端应有终态结果').toBeTruthy();
    expect(laptopTurn.result?.status, '电脑端应有终态结果').toBeTruthy();
    // 两端拿到的是各自独立的会话，不是同一个
    expect(phoneTurn.conversationId).not.toBe(laptopTurn.conversationId);

    // 互不阻塞：两条并发请求的总耗时不应接近「串行之和」
    const concurrentTotal = Math.max(phoneTurn.ms, laptopTurn.ms);
    const serialSum = phoneTurn.ms + laptopTurn.ms;
    expect(
      concurrentTotal,
      `并发首轮耗时 ${concurrentTotal}ms 与串行之和 ${serialSum}ms 太接近，疑似互相阻塞`,
    ).toBeLessThan(serialSum * 0.95);

    // ---- 2. 各自追一轮：仍是自己的会话，且记忆里没有被对方污染 ----
    const phoneFollowUp = await postAgentTurn(phone, {
      message: `记一下后天取护照 ${runTag}`,
      conversationId: phoneConv,
      scope: `device:phone:${runTag}`,
    });
    const laptopFollowUp = await postAgentTurn(laptop, {
      message: `再补充一个适合雨天的地方 ${runTag}`,
      conversationId: laptopConv,
      scope: `device:laptop:${runTag}`,
    });
    expect(phoneFollowUp.status).toBe(200);
    expect(laptopFollowUp.status).toBe(200);
    expect(phoneFollowUp.result?.status).toBeTruthy();
    expect(laptopFollowUp.result?.status).toBeTruthy();

    // ---- 3. 不串号：会话历史只含自己那两轮（user/assistant 各两条） ----
    const phoneMessages = await fetchConversationMessages(phone, phoneConv);
    const laptopMessages = await fetchConversationMessages(laptop, laptopConv);

    expect(phoneMessages.length, `手机端会话应有 4 条消息，实际 ${phoneMessages.length}`).toBe(4);
    expect(laptopMessages.length, `电脑端会话应有 4 条消息，实际 ${laptopMessages.length}`).toBe(4);

    const phoneText = phoneMessages.map((m) => m.content).join('\n');
    const laptopText = laptopMessages.map((m) => m.content).join('\n');

    // 手机端能看到自己的两轮，看不到电脑端的提问
    expect(phoneText).toContain(`记一下明天办签证 ${runTag}`);
    expect(phoneText).toContain(`记一下后天取护照 ${runTag}`);
    expect(phoneText, '手机端会话里混入了电脑端的消息（串号）').not.toContain('适合亲子游的地方');
    expect(phoneText, '手机端会话里混入了电脑端的消息（串号）').not.toContain('适合雨天的地方');

    // 电脑端同理
    expect(laptopText).toContain(`适合亲子游的地方？ ${runTag}`);
    expect(laptopText).toContain(`适合雨天的地方 ${runTag}`);
    expect(laptopText, '电脑端会话里混入了手机端的消息（串号）').not.toContain('办签证');
    expect(laptopText, '电脑端会话里混入了手机端的消息（串号）').not.toContain('取护照');

    // 反过来：拿电脑端的凭证去读手机端的会话，也读不到内容（隔离在服务端，不只是客户端约定）
    const crossRead = await laptop.get(
      `/api/agent/conversations/${encodeURIComponent(phoneConv)}/messages`,
    );
    if (crossRead.ok()) {
      const crossBody = await crossRead.json();
      const crossMessages = crossBody?.data?.messages ?? [];
      // 同账号读取自己的会话是允许的（两台设备同账号），这里断言的是「不混入对方会话内容」
      const crossText = JSON.stringify(crossMessages);
      expect(crossText, '读取单段会话时混入了另一段会话的消息').not.toContain('适合亲子游的地方');
    }

    // ---- 4. 全局：没有任何 5xx ----
    expect(serverErrors, `并发期间出现服务端错误：\n${serverErrors.join('\n')}`).toEqual([]);
  } finally {
    await phone.dispose();
    await laptop.dispose();
  }
});

test('两台设备各自打开页面：同账号登录态都能渲染首页，互不影响', async ({
  browser,
  baseURL,
}, testInfo) => {
  // browser.newContext() 默认继承项目 use，这里显式复制登录态让「两台设备」的关系更直白
  const storageState = testInfo.project.use.storageState!;
  const deviceA = await browser.newContext({ baseURL, storageState });
  const deviceB = await browser.newContext({ baseURL, storageState });
  try {
    const [pageA, pageB] = await Promise.all([deviceA.newPage(), deviceB.newPage()]);
    const failures: string[] = [];
    for (const [name, page] of [
      ['A', pageA],
      ['B', pageB],
    ] as const) {
      page.on('response', (r) => {
        if (r.status() >= 500) failures.push(`${name} ${r.status()} ${r.url()}`);
      });
    }

    await Promise.all([pageA.goto('/'), pageB.goto('/')]);
    await Promise.all([
      expect(pageA.locator('.dash-greeting')).toBeVisible({ timeout: 20_000 }),
      expect(pageB.locator('.dash-greeting')).toBeVisible({ timeout: 20_000 }),
    ]);

    // 两端都认出了同一个账号（同账号登录态，不是各自被踢回登录页）
    await Promise.all([
      expect(pageA.locator('.dash-greeting h1')).toContainText('演示用户'),
      expect(pageB.locator('.dash-greeting h1')).toContainText('演示用户'),
    ]);
    expect(pageA.url()).not.toContain('/login');
    expect(pageB.url()).not.toContain('/login');
    expect(failures).toEqual([]);
  } finally {
    await deviceA.close();
    await deviceB.close();
  }
});
