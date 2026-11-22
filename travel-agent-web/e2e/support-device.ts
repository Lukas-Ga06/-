import { expect, type Page, type CDPSession } from '@playwright/test';

/**
 * 移动端 / 弱网 e2e 的公共工具（`test:e2e:mobile`、`test:e2e:network` 专用）。
 *
 * 两条硬约束写在最前面，避免以后有人把断言写错方向：
 *
 * 1. **移动项目是 Playwright WebKit + iPhone 预设**，即「Safari 的引擎 + 移动端 UA/视口/DPR」，
 *    **不是 iOS 真机**。它证不了：iOS 真机的 SafariUIWebView 差异、`env(safe-area-inset-*)`
 *    的真实取值（Playwright 下恒为 0）、真实触摸手势（滚动惯性、双指缩放）、
 *    iOS 的低电量/后台挂起与 WebKit 版本差异（老 iOS 的 `addListener` 分支）等。
 *
 * 2. **弱网只能跑在 Chromium**：`Network.emulateNetworkConditions` 是 CDP 命令，
 *    WebKit 不支持 CDP（`CDP session is only available in Chromium`），
 *    所以离线/弱网用例固定使用 `chromium` 项目。
 */

/**
 * 断言「没有横向溢出」。
 *
 * 移动端这条是真需求而不是形式主义：宽度溢出会直接产生左右滚动、底栏错位、
 * 抽屉（antd Drawer 写死 width）盖不住视口。390px 视口下任何溢出都会放大成明显缺陷。
 *
 * 除了覆盖层（固定定位的横幅/遮罩/抽屉）以外，逐个元素量右边界：
 * 只看 `scrollWidth` 会漏掉「被父级 overflow:hidden 裁掉」的越界元素。
 *
 * 但这不是「所有元素都必须在视口内」：
 * 设计上**故意**做成横向滚动条的容器（`overflow-x:auto/scroll，如移动端行程筛选条 .filter-row，
 * scrollWidth 411 > clientWidth 358）本身就允许子元素越界，它们不会撑破文档宽度。
 * 所以遍历时在最近的滚动容器处停止，真正要抓的是「没有被任何祖先裁住、直接顶出视口的元素」。
 */
export async function expectNoHorizontalOverflow(page: Page, label = '页面') {
  const metrics = await page.evaluate(() => {
    const vw = window.innerWidth;
    /** 最近的祖先里是否有「剪切或滚动」容器：有的话越界由它负责，不算文档溢出 */
    const clippedByAncestor = (el: Element): string | null => {
      for (let node: Element | null = el.parentElement; node; node = node.parentElement) {
        const style = getComputedStyle(node);
        const clipsX =
          ['hidden', 'auto', 'scroll', 'clip'].includes(style.overflowX) ||
          ['hidden', 'auto', 'scroll', 'clip'].includes(style.overflow);
        if (clipsX) return node.getAttribute('class') ?? node.tagName.toLowerCase();
        if (style.position === 'fixed') return 'fixed';
      }
      return null;
    };
    const offenders: {
      tag: string;
      cls: string;
      right: number;
      width: number;
      clippedBy: string | null;
    }[] = [];
    for (const el of Array.from(document.querySelectorAll('body *'))) {
      const rect = el.getBoundingClientRect();
      if (rect.width <= 0 || rect.height <= 0) continue;
      if (rect.right <= vw + 1) continue;
      offenders.push({
        tag: el.tagName.toLowerCase(),
        cls: (el.getAttribute('class') ?? '').slice(0, 60),
        right: Math.round(rect.right),
        width: Math.round(rect.width),
        clippedBy: clippedByAncestor(el),
      });
    }
    // 只报「没有任何祖先兜住」的越界元素：它们才会把文档撑宽
    const unclipped = offenders.filter((o) => o.clippedBy === null).slice(0, 10);
    return {
      documentScrollWidth: document.documentElement.scrollWidth,
      bodyScrollWidth: document.body.scrollWidth,
      innerWidth: vw,
      offenders: unclipped,
      // 被设计成横向滚动容器吸收掉的越界元素，作为说明保留（不断言失败）
      absorbedByScrollContainer: offenders.length - unclipped.length,
    };
  });

  expect(
    metrics.documentScrollWidth,
    `${label}横向溢出：documentElement.scrollWidth=${metrics.documentScrollWidth} > innerWidth=${metrics.innerWidth}`,
  ).toBeLessThanOrEqual(metrics.innerWidth);
  expect(
    metrics.bodyScrollWidth,
    `${label}横向溢出：body.scrollWidth=${metrics.bodyScrollWidth} > innerWidth=${metrics.innerWidth}`,
  ).toBeLessThanOrEqual(metrics.innerWidth);
  expect(
    metrics.offenders,
    `${label}有元素越出视口右边界：${JSON.stringify(metrics.offenders)}`,
  ).toEqual([]);
  return metrics;
}

/** 一次完整消息往返的结果（从 POST 到 complete 帧收全） */
export interface AgentTurn {
  status: number;
  ms: number;
  conversationId: string;
  assistantMessage: string;
  result: any;
}

/**
 * 直接打 `POST /api/agent/stream` 跑一轮对话。
 *
 * 用 API 而不是点 UI：多端并发要严格验证「服务端按 conversationId 隔离记忆」，
 * UI 驱动会把浏览器的渲染时序混进断言里。请求带 storageState 的登录 Cookie。
 */
export async function postAgentTurn(
  request: import('@playwright/test').APIRequestContext,
  input: {
    message: string;
    conversationId?: string | null;
    scope?: string | null;
    requestId?: string;
  },
): Promise<AgentTurn> {
  const conversationId = input.conversationId ?? input.requestId ?? `e2e-${Date.now()}`;
  const startedAt = Date.now();
  const response = await request.post('/api/agent/stream', {
    data: {
      message: input.message,
      tripId: null,
      currentDay: null,
      conversationId,
      scope: input.scope ?? 'assistant',
      requestId: input.requestId ?? `${conversationId}-${Date.now()}`,
      selectedElement: null,
      mapCenter: null,
    },
    timeout: 90_000,
  });
  const body = await response.text();
  const ms = Date.now() - startedAt;

  // 服务端事件流是 `event: x\ndata: {...}\n\n`，complete 是终止帧
  const frames = body
    .split('\n\n')
    .map((block) => block.trim())
    .filter(Boolean)
    .map((block) => {
      const lines = block.split('\n');
      const event = (lines.find((l) => l.startsWith('event:')) ?? 'event: message')
        .slice('event:'.length)
        .trim();
      const data = lines
        .filter((l) => l.startsWith('data:'))
        .map((l) => l.slice('data:'.length).trim())
        .join('\n');
      let parsed: any = null;
      try {
        parsed = data ? JSON.parse(data) : null;
      } catch {
        parsed = data;
      }
      return { event, data: parsed };
    });
  const complete = frames.find((f) => f.event === 'complete');
  if (!complete) {
    throw new Error(
      `未收到 complete 帧：HTTP ${response.status()}\n响应体前 800 字：\n${body.slice(0, 800)}`,
    );
  }
  return {
    status: response.status(),
    ms,
    conversationId,
    assistantMessage: String(complete.data?.message ?? ''),
    result: complete.data,
  };
}

/** 会话记忆接口：按 conversationId 取回历史（服务端只回当前用户自己的） */
export async function fetchConversationMessages(
  request: import('@playwright/test').APIRequestContext,
  conversationId: string,
): Promise<{ role: string; content: string }[]> {
  const response = await request.get(
    `/api/agent/conversations/${encodeURIComponent(conversationId)}/messages`,
  );
  expect(response.status(), `取会话 ${conversationId} 的消息失败`).toBe(200);
  const body = await response.json();
  return Array.isArray(body?.data?.messages) ? body.data.messages : [];
}

/**
 * Chromium 专属：开一块 CDP 会话并把网络条件调成 Slow-3G。
 *
 * 为什么必须走 CDP：Playwright 没有跨浏览器的网络限速 API；`Network.emulateNetworkConditions`
 * 只在 Chromium 上可用。WebKit/Firefox 用这个会直接报错——所以弱网用例不能挂到 mobile-safari 项目。
 */
export async function emulateSlow3G(page: Page): Promise<CDPSession> {
  const session = await page.context().newCDPSession(page);
  await session.send('Network.enable');
  await session.send('Network.emulateNetworkConditions', {
    offline: false,
    latency: 400,
    downloadThroughput: 50_000, // ≈ 400 kbps
    uploadThroughput: 20_000, // ≈ 160 kbps
    connectionType: 'cellular3g',
  });
  return session;
}

/** Chromium 专属：硬离线开关（等价于拔网线，会触发 navigator.onLine=false） */
export async function setCdpOffline(session: CDPSession, offline: boolean): Promise<void> {
  await session.send('Network.emulateNetworkConditions', {
    offline,
    latency: offline ? 0 : 400,
    downloadThroughput: offline ? 0 : 50_000,
    uploadThroughput: offline ? 0 : 20_000,
    connectionType: offline ? 'none' : 'cellular3g',
  });
}
