import { expect, type Page, type CDPSession } from '@playwright/test';

/**
 * 弱网 / 多端并发 e2e 的公共工具（`test:e2e:network`、`test:e2e:multi` 专用）。
 *
 * 硬约束写在最前面，避免以后有人把断言写错方向：
 *
 * **弱网只能跑在 Chromium**：`Network.emulateNetworkConditions` 是 CDP 命令，
 * 其它引擎不支持（`CDP session is only available in Chromium`），
 * 所以离线/弱网用例固定使用 `chromium` 项目。
 */

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
 * 只在 Chromium 上可用，所以弱网用例固定挂在 chromium 项目上。
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
