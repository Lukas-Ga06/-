import { request } from './client';
import { getAccessTicket } from './ticket';
import type {
  AgentResult,
  ExecutionSummary,
  RunDetail,
  RunSummary,
  SelectedElement,
} from '../types/agent';

export interface AgentStreamParams {
  message: string;
  tripId?: number | null;
  currentDay?: number | null;
  selectedElement?: SelectedElement | null;
  mapCenter?: { lat: number; lng: number } | null;
  /** 会话记忆标识（UUID）：后端据此回放历史做指代消解 */
  conversationId?: string | null;
  /**
   * 会话作用域（V21）：`assistant` / `planner` / `trip:<id>`，由调用方（store）按当前作用域填。
   * 服务端只把它记在会话上，用于「最近会话」分组与续聊时恢复行程绑定。
   */
  scope?: string | null;
  /**
   * 幂等标识（P0-01）：同一条命令的重放（EventSource 自动重连、代理重试、手动重发）
   * 必须带同一个值，服务端按 (userId, requestId) 保证写入最多一次。
   */
  requestId?: string | null;
}

/** 过程警告：服务端 `error` 事件只表示「本轮有模块没过」，流还会继续并最终 complete */
export interface AgentWarning {
  code?: string;
  message: string;
}

export interface AgentStreamHandlers {
  onEvent?: (event: string, data: any) => void;
  onComplete?: (result: AgentResult) => void;
  /** 过程警告：不终止本次流、不解锁输入（P0-02） */
  onWarning?: (warning: AgentWarning) => void;
  /** 用户主动停止，或服务端下发 cancelled：本轮已结束但没有最终结果 */
  onCancelled?: (payload?: any) => void;
  /** 终止性错误：连接中断 / HTTP 失败 / 未收到 complete 就断流 */
  onError?: (message: string) => void;
}

/**
 * 统一事件分发（GET / POST 两条传输共用，避免协议语义在两处漂移）。
 *
 * 语义约定（P0-02）：
 * - `complete` / `cancelled` 是终止事件，各自只回调一次并关闭连接；
 * - `error` 是**过程警告**：服务端随后仍会下发 complete，因此绝不在此终止流；
 * - 其余事件交给 onEvent，由调用方决定是否渲染。
 */
function dispatchFrame(
  name: string,
  data: any,
  handlers: AgentStreamHandlers,
  finish: (callback?: () => void) => void,
): void {
  if (name === 'complete') {
    finish(() => handlers.onComplete?.(data as AgentResult));
  } else if (name === 'cancelled') {
    finish(() => handlers.onCancelled?.(data));
  } else if (name === 'error') {
    const warning: AgentWarning = {
      code: typeof data?.code === 'string' ? data.code : undefined,
      message: data?.message || 'Agent 执行出错',
    };
    if (handlers.onWarning) {
      handlers.onWarning(warning);
    } else {
      // 没有 warning 通道的调用方仍能看到这条事件，但不会被当成终止
      handlers.onEvent?.('error', data);
    }
  } else {
    handlers.onEvent?.(name, data);
  }
}

const EVENT_NAMES = [
  // run：连接建立后紧跟 start 事件下发，data 为 { runId }（本轮运行的追踪标识，V2.2）
  // replan：审核未通过进入重规划时下发，data 为 { attempt, issues[] }（Batch 13）
  // delegate / recover / conflict：任务图事件（Batch 15），委派 / 回收重派 / 前置未完成被拒
  //   —— EventSource 只能按固定事件名监听，漏登记就等于静默丢弃，过程时间线会缺这三类行
  'run',
  'start',
  'intent',
  'planning',
  'replan',
  'tool_call',
  'tool_result',
  'rag_result',
  'plan_update',
  'focus',
  'confirmation',
  'complete',
  'cancelled',
  'error',
  'delegate',
  'recover',
  'conflict',
];

/**
 * SSE 流式对话。EventSource 无法自定义 Header，凭证走查询参数 ——
 * 用的是短期只读票据（api/ticket.ts），不是登录 JWT。
 * 返回关闭函数（票据获取是异步的，因此本函数返回 Promise）。
 */
export async function streamAgentChat(
  params: AgentStreamParams,
  handlers: AgentStreamHandlers,
): Promise<() => void> {
  // 票据按用途签发：SSE 必须用 agent-stream，照片原图用 media-read，不能互相挪用
  const ticket = await getAccessTicket('agent-stream');
  const qs = new URLSearchParams();
  qs.set('message', params.message);
  if (params.tripId) qs.set('tripId', String(params.tripId));
  if (params.currentDay) qs.set('currentDay', String(params.currentDay));
  if (params.conversationId) qs.set('conversationId', params.conversationId);
  if (params.scope) qs.set('scope', params.scope);
  // 幂等键必须进 URL：EventSource 断线自动重连会复用同一个 URL，服务端据此只写一次
  if (params.requestId) qs.set('requestId', params.requestId);
  if (params.selectedElement?.id) {
    qs.set('selType', params.selectedElement.type || 'itinerary_item');
    qs.set('selId', String(params.selectedElement.id));
    if (params.selectedElement.title) qs.set('selTitle', params.selectedElement.title);
  }
  if (params.mapCenter) {
    qs.set('lat', String(params.mapCenter.lat));
    qs.set('lng', String(params.mapCenter.lng));
  }
  qs.set('ticket', ticket);

  const es = new EventSource(`/api/agent/stream?${qs.toString()}`);
  let finished = false;

  /** 终止一次流：保证 onComplete / onError 只回调一次，并关闭连接 */
  const finish = (callback?: () => void) => {
    if (finished) {
      return;
    }
    finished = true;
    es.close();
    callback?.();
  };

  EVENT_NAMES.forEach((name) => {
    es.addEventListener(name, (e: MessageEvent) => {
      // EventSource 的连接层失败同样会派发名为 'error' 的 Event（没有 data）。
      // 那不是服务端下发的业务错误，交给 onerror 走终止路径，不能当过程警告。
      if (name === 'error' && !e.data) {
        return;
      }
      let data: any = null;
      try {
        data = e.data ? JSON.parse(e.data) : null;
      } catch {
        data = e.data;
      }
      dispatchFrame(name, data, handlers, finish);
    });
  });

  /**
   * 连接层错误：服务端 180s 超时、网络中断等情况下浏览器只会触发 onerror。
   * 旧实现只在 readyState === CLOSED 时才回调，导致既不 complete 也不 error，
   * 调用方的 streaming 状态永远停在 true（输入框被永久禁用）。
   *
   * 关键陷阱：服务端的业务错误也用 `event: error` 下发，浏览器会把这条 **带 data 的
   * MessageEvent** 同样派发给 onerror。那种帧已经由上面的监听器当过程警告处理，
   * 后面还会跟一条 complete；如果在这里无条件 finish，complete 会被丢掉，
   * 用户看到「连接已中断」，真实业务文案也随之消失。
   */
  es.onerror = (event?: Event) => {
    const data = (event as MessageEvent | undefined)?.data;
    if (typeof data === 'string' && data.length > 0) {
      return;
    }
    finish(() => handlers.onError?.('与 Agent 的连接已中断，请重试'));
  };

  return () => finish();
}

/**
 * POST 版 SSE 流：结构化规划表单（含参考攻略正文）专用。
 * EventSource 只能 GET、消息走 URL，长文本（攻略正文可上万字）放不下；
 * fetch POST 走请求体 + Cookie 认证，用 ReadableStream 手工解析 SSE 帧。
 * 返回关闭函数（语义与 streamAgentChat 一致）。
 */
export async function streamAgentChatPost(
  params: AgentStreamParams,
  handlers: AgentStreamHandlers,
): Promise<() => void> {
  const controller = new AbortController();
  let finished = false;
  const finish = (callback?: () => void) => {
    if (finished) {
      return;
    }
    finished = true;
    controller.abort();
    callback?.();
  };

  (async () => {
    try {
      const resp = await fetch('/api/agent/stream', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        credentials: 'same-origin',
        body: JSON.stringify({
          message: params.message,
          tripId: params.tripId ?? null,
          currentDay: params.currentDay ?? null,
          conversationId: params.conversationId ?? null,
          scope: params.scope ?? null,
          requestId: params.requestId ?? null,
          selectedElement: params.selectedElement ?? null,
          mapCenter: params.mapCenter ?? null,
        }),
        signal: controller.signal,
      });
      if (!resp.ok || !resp.body) {
        const text = await resp.text().catch(() => '');
        let msg = `请求失败（${resp.status}）`;
        try {
          const parsed = JSON.parse(text);
          if (parsed?.message) msg = parsed.message;
        } catch {
          // 非 JSON 响应体，保留默认提示
        }
        finish(() => handlers.onError?.(msg));
        return;
      }
      const reader = resp.body.getReader();
      const decoder = new TextDecoder();
      let buffer = '';
      const handleBlock = (block: string) => {
        let event = 'message';
        const dataLines: string[] = [];
        for (const line of block.split('\n')) {
          if (line.startsWith('event:')) {
            event = line.slice(6).trim();
          } else if (line.startsWith('data:')) {
            dataLines.push(line.slice(5).trimStart());
          }
        }
        const raw = dataLines.join('\n');
        if (!raw && event === 'message') {
          return;
        }
        let data: any = null;
        if (raw) {
          try {
            data = JSON.parse(raw);
          } catch {
            data = raw;
          }
        }
        dispatchFrame(event, data, handlers, finish);
      };
      for (;;) {
        const { done, value } = await reader.read();
        if (done) break;
        buffer += decoder.decode(value, { stream: true });
        let idx: number;
        while ((idx = buffer.indexOf('\n\n')) >= 0) {
          const block = buffer.slice(0, idx).replace(/\r/g, '');
          buffer = buffer.slice(idx + 2);
          handleBlock(block);
        }
      }
      // 连接关闭却没收到 complete：与 GET 版 onerror 语义一致
      finish(() => handlers.onError?.('与 Agent 的连接已中断，请重试'));
    } catch (e: any) {
      if (e?.name === 'AbortError') {
        finish();
        return;
      }
      finish(() => handlers.onError?.(e?.message || '无法建立与 Agent 的连接'));
    }
  })();

  return () => finish();
}

/**
 * 确认待确认方案。contentHash 来自方案的 confirmation.contentHash，
 * 回传后后端会把确认绑定到具体计划内容版本（不传走旧校验，向后兼容）。
 *
 * acknowledgeIssues（Batch 13）：仅在用户显式勾选「接受风险」时为 true，
 * 缺省不带该字段——旧后端不认识它，请求体保持不变。
 */
export function confirmPlan(
  planId: string,
  contentHash?: string,
  acknowledgeIssues?: boolean,
): Promise<AgentResult> {
  const data: { planId: string; contentHash?: string; acknowledgeIssues?: true } = { planId };
  if (contentHash) data.contentHash = contentHash;
  if (acknowledgeIssues) data.acknowledgeIssues = true;
  return request<AgentResult>({ url: '/agent/confirm', method: 'post', data });
}

export function cancelPlan(planId: string): Promise<AgentResult> {
  return request<AgentResult>({ url: '/agent/cancel', method: 'post', data: { planId } });
}

export function undoTrip(tripId: number): Promise<AgentResult> {
  return request<AgentResult>({ url: '/agent/undo', method: 'post', data: { tripId } });
}

/** 取回待确认方案：刷新页面后凭 planId 恢复确认弹窗 */
export function getPendingPlan(planId: string): Promise<AgentResult> {
  return request<AgentResult>({ url: `/agent/plans/${encodeURIComponent(planId)}` });
}

/** 最近会话摘要（§8.6）：只读，用于「继续对话」；标题由后端取首条用户消息 */
export interface ConversationSummary {
  conversationId: string;
  title: string;
  /** ISO 本地时间；旧后端可能缺省 */
  lastMessageAt: string | null;
  messageCount: number;
  /**
   * 会话作用域（V21）：`assistant` / `planner` / `trip:<id>`。
   * `null` = 存量会话未绑定（V21 之前写入），**不能**回落到某个猜测值。
   */
  scope?: string | null;
}

/** 会话内的一条历史消息（只读） */
export interface ConversationMessageView {
  role: 'user' | 'assistant' | string;
  content: string;
  createdAt: string | null;
}

/** 最近会话列表（按最后活动时间倒序；只返回当前用户、保留期内的会话） */
export function listConversations(limit = 20): Promise<ConversationSummary[]> {
  return request<ConversationSummary[]>({
    url: '/agent/conversations',
    method: 'get',
    params: { limit },
  });
}

/** 「继续对话」回填结果：消息本身 + 这段会话最近一次被使用的作用域 */
export interface ConversationResume {
  conversationId: string;
  /** `assistant` / `planner` / `trip:<id>`；null = 未绑定 */
  scope: string | null;
  /** 时间正序（旧 → 新） */
  messages: ConversationMessageView[];
}

/**
 * 某段会话最近的消息（时间正序），用于「继续对话」回填聊天记录。
 *
 * 返回的是对象而不是裸数组：续聊时必须同时拿到该会话的作用域，才能把行程绑定恢复回来
 * （否则在助手页接着一段行程会话聊，下一条消息就不带 tripId 了）。
 */
export function getConversationMessages(
  conversationId: string,
  limit = 50,
): Promise<ConversationResume> {
  return request<ConversationResume>({
    url: `/agent/conversations/${encodeURIComponent(conversationId)}/messages`,
    method: 'get',
    params: { limit },
  });
}

/** 最近运行列表（V2.2 运行追踪；limit 默认 20） */
export function getRuns(limit = 20): Promise<RunSummary[]> {
  return request<RunSummary[]>({ url: '/agent/runs', method: 'get', params: { limit } });
}

/** 单次运行的完整追踪：任务链 + 工具调用（他人 run 返回 404 业务错误） */
export function getRunDetail(runId: string): Promise<RunDetail> {
  return request<RunDetail>({ url: `/agent/runs/${encodeURIComponent(runId)}` });
}

/** 查询一次确认提交的模块级同步结果（日程/预算/足迹） */
export function getExecution(executionId: string): Promise<ExecutionSummary> {
  return request<ExecutionSummary>({ url: `/agent/executions/${encodeURIComponent(executionId)}` });
}

/** 重试同步：后端只重试 FAILED 模块，成功项不重复创建 */
export function retryExecution(executionId: string): Promise<ExecutionSummary> {
  return request<ExecutionSummary>({
    url: `/agent/executions/${encodeURIComponent(executionId)}/retry`,
    method: 'post',
  });
}

/** 开新会话时清掉服务端该会话的历史（失败可忽略，历史 7 天后也会被定期清理） */
export function clearConversation(conversationId: string): Promise<void> {
  return request<void>({
    url: '/agent/conversation/clear',
    method: 'post',
    data: { conversationId },
  });
}

/** 重命名结果：服务端真正生效的标题（null = 已恢复自动标题） */
export interface ConversationRenameResult {
  conversationId: string;
  title: string | null;
}

/**
 * 重命名一段会话（V23）：只改展示标题，不动消息、不影响续聊。
 *
 * 传空串 = 恢复自动标题（回到「首条用户消息」的推导标题），返回值里的 title 也就是 null。
 */
export function renameConversation(
  conversationId: string,
  title: string,
): Promise<ConversationRenameResult> {
  return request<ConversationRenameResult>({
    url: `/agent/conversations/${encodeURIComponent(conversationId)}`,
    method: 'patch',
    data: { title },
  });
}

/**
 * 删除一段会话（V23）：消息与标题一起删，不可恢复。
 *
 * 幂等：不存在的会话同样成功（保留期清理可能已经删过），不需要前端区分。
 */
export function deleteConversation(conversationId: string): Promise<void> {
  return request<void>({
    url: `/agent/conversations/${encodeURIComponent(conversationId)}`,
    method: 'delete',
  });
}
