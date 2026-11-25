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

/** SSE 帧 id 的解析结果：`<runId>:<seq>`（seq 是 run 内从 1 单调递增的整数，V28） */
export interface ParsedEventId {
  runId: string;
  seq: number;
}

/**
 * 解析 SSE 帧 id。
 *
 * 后端格式固定为 `<runId>:<seq>`；runId 不含冒号，seq 是正整数。
 * 返回 null 表示「这一帧没有可续传的 id」——旧后端的帧没有 id、runId 尚未产生的早期帧也可能没有，
 * 这两种情况都必须保持改造前的行为（不参与续传，也不去重）。
 */
export function parseEventId(id: string | null | undefined): ParsedEventId | null {
  if (typeof id !== 'string') {
    return null;
  }
  const value = id.trim();
  const sep = value.lastIndexOf(':');
  if (sep <= 0 || sep === value.length - 1) {
    return null;
  }
  const runId = value.slice(0, sep);
  const seqText = value.slice(sep + 1);
  if (!/^\d+$/.test(seqText)) {
    return null;
  }
  const seq = Number(seqText);
  if (!Number.isSafeInteger(seq) || seq < 1) {
    return null;
  }
  return { runId, seq };
}

/**
 * 断线续传地址（V28）：先补发 seq > lastEventId 的持久化事件，再把后续事件接上。
 * EventSource 不能带请求头，票据只能进查询串（与主路径一致，用短期 agent-stream 票据）。
 */
export function buildResumeUrl(runId: string, lastEventId: number, ticket?: string | null): string {
  const qs = new URLSearchParams();
  qs.set('lastEventId', String(lastEventId));
  if (ticket) {
    qs.set('ticket', ticket);
  }
  return `/api/agent/runs/${encodeURIComponent(runId)}/events?${qs.toString()}`;
}

/** 连接中断且没有可续传上下文（旧后端 / 还没收到带 id 的帧）：与改造前文案一致 */
const CONNECTION_LOST_MESSAGE = '与 Agent 的连接已中断，请重试';
/** 已自动续传过一次仍失败：只报告，不再重试（避免成本失控）；EventSource 读不到 4xx 状态，给可操作提示 */
const RESUME_FAILED_MESSAGE = '连接中断，自动续传失败，可稍后查看运行记录或重新发起';
/** 续传接口在运行超过 120s 仍未终态时下发 resume_timeout */
const RESUME_TIMEOUT_MESSAGE = '连接中断且运行超时，可稍后查看运行记录';

/**
 * 单次流的续传游标（GET / POST 两条传输共用，避免续传语义在两处漂移）。
 *
 * - runId / lastSeq 来自帧 id，没有 id 的帧不推进游标；
 * - resumed + resuming 是「最多续传一次」的闸门；
 * - finished 保证 onComplete / onError / onCancelled 全局只回调一次（store 靠它解锁 streaming）。
 */
interface StreamCursor {
  runId: string | null;
  lastSeq: number;
  resumed: boolean;
  resuming: boolean;
  finished: boolean;
  /** 当前活动连接（初始 EventSource / 续传 EventSource），finish 时关闭 */
  source: EventSource | null;
}

function createStreamCursor(): StreamCursor {
  return {
    runId: null,
    lastSeq: 0,
    resumed: false,
    resuming: false,
    finished: false,
    source: null,
  };
}

/** 终止一次流：关闭当前连接并保证终止回调只走一次 */
type FinishStream = (callback?: () => void) => void;

/** SSE data 一律按 JSON 解析；解析失败退回原始字符串（与改造前一致） */
function parseFrameData(raw: unknown): any {
  if (typeof raw !== 'string' || raw.length === 0) {
    return null;
  }
  try {
    return JSON.parse(raw);
  } catch {
    return raw;
  }
}

/**
 * 用帧 id 推进游标；返回 false 表示这一帧已经处理过（续传补发与已有事件重叠），必须丢弃。
 * 没有 id 的帧一律照常分发。
 */
function advanceCursor(cursor: StreamCursor, eventId: string | null | undefined): boolean {
  const parsed = parseEventId(eventId);
  if (!parsed) {
    return true;
  }
  if (parsed.seq <= cursor.lastSeq) {
    return false;
  }
  cursor.lastSeq = parsed.seq;
  cursor.runId = parsed.runId;
  return true;
}

/** 一帧 EventSource 数据：推进游标 → 兜底记录 runId → 统一分发 */
function handleSourceFrame(
  name: string,
  event: MessageEvent,
  cursor: StreamCursor,
  handlers: AgentStreamHandlers,
  finish: FinishStream,
): void {
  const data = parseFrameData(event.data);
  if (!advanceCursor(cursor, event.lastEventId)) {
    return;
  }
  // 旧后端可能不给 run 事件带 id：runId 仍从 data 里兜底记录（但没有 seq 就不会续传）
  if (name === 'run' && data?.runId && !cursor.runId) {
    cursor.runId = String(data.runId);
  }
  dispatchFrame(name, data, handlers, finish);
}

/**
 * 自动续传（整个流最多一次）。
 *
 * 关键：先把原 EventSource 显式 close()。原生 EventSource 在 onerror 之后会按原 URL
 * 自动重连，不关掉的话同一个规划请求会被再打一遍（Agent 重复执行、成本放大）。
 * 然后用新 EventSource 指向 resume 接口，补发事件与后续事件都走同一个 dispatchFrame。
 */
function startStreamResume(
  cursor: StreamCursor,
  handlers: AgentStreamHandlers,
  finish: FinishStream,
): void {
  if (cursor.finished || cursor.resumed || cursor.resuming) {
    return;
  }
  const runId = cursor.runId;
  if (!runId || cursor.lastSeq <= 0) {
    finish(() => handlers.onError?.(CONNECTION_LOST_MESSAGE));
    return;
  }
  cursor.resumed = true;
  cursor.resuming = true;
  cursor.source?.close();
  cursor.source = null;

  // 票据重新取一次：缓存有效则直接复用，过期则换新（resume 接口和首连一样校验票据）
  void getAccessTicket('agent-stream')
    .then((ticket) => {
      cursor.resuming = false;
      if (cursor.finished) {
        return;
      }
      attachEventSource(
        new EventSource(buildResumeUrl(runId, cursor.lastSeq, ticket)),
        cursor,
        handlers,
        finish,
      );
    })
    .catch(() => {
      cursor.resuming = false;
      finish(() => handlers.onError?.(RESUME_FAILED_MESSAGE));
    });
}

/**
 * 连接层中断的统一入口（EventSource onerror 且无业务 data；fetch 读流异常 / 提前 EOF）。
 * 有 runId + lastSeq 就先自动续传一次；续传连接再断则只报一次错，绝不无限重试。
 */
function handleStreamBroken(
  cursor: StreamCursor,
  handlers: AgentStreamHandlers,
  finish: FinishStream,
): void {
  if (cursor.finished || cursor.resuming) {
    return;
  }
  if (cursor.resumed) {
    finish(() => handlers.onError?.(RESUME_FAILED_MESSAGE));
    return;
  }
  if (!cursor.runId || cursor.lastSeq <= 0) {
    finish(() => handlers.onError?.(CONNECTION_LOST_MESSAGE));
    return;
  }
  startStreamResume(cursor, handlers, finish);
}

/** 绑定一个 EventSource（初始连接与续传连接共用同一套监听与分发） */
function attachEventSource(
  es: EventSource,
  cursor: StreamCursor,
  handlers: AgentStreamHandlers,
  finish: FinishStream,
): void {
  cursor.source = es;
  EVENT_NAMES.forEach((name) => {
    es.addEventListener(name, (e: MessageEvent) => {
      // EventSource 的连接层失败同样会派发名为 'error' 的事件（没有 data），
      // 那不是服务端下发的业务错误，交给 onerror 走续传/终止路径，不能当过程警告。
      if (name === 'error' && !e.data) {
        return;
      }
      handleSourceFrame(name, e, cursor, handlers, finish);
    });
  });
  // 续传接口在运行仍未终态时下发 resume_timeout（120s）：视为终止，不能把输入框一直锁在 streaming
  es.addEventListener('resume_timeout', () => {
    finish(() => handlers.onError?.(RESUME_TIMEOUT_MESSAGE));
  });

  es.onerror = (event?: Event) => {
    // 服务端的业务错误也用 `event: error` 下发，浏览器会把这条带 data 的 MessageEvent
    // 同样派发给 onerror。那种帧已经由上面的监听器当过程警告处理，后面还会跟一条 complete；
    // 如果在这里无条件终止，complete 会被丢掉，用户看到「连接已中断」，真实业务文案也随之消失。
    const data = (event as MessageEvent | undefined)?.data;
    if (typeof data === 'string' && data.length > 0) {
      return;
    }
    handleStreamBroken(cursor, handlers, finish);
  };
}

/**
 * SSE 流式对话。EventSource 无法自定义 Header，凭证走查询参数 ——
 * 用的是短期只读票据（api/ticket.ts），不是登录 JWT。
 * 返回关闭函数（票据获取是异步的，因此本函数返回 Promise）。
 *
 * 断线续传（V28）：帧 id 为 `<runId>:<seq>`。连接层中断时若已拿到 runId + lastSeq，
 * 会自动用 resume 接口补一次缺失事件（最多一次），调用方无需感知。
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

  const cursor = createStreamCursor();

  /** 终止一次流：保证 onComplete / onError / onCancelled 只回调一次，并关闭当前连接 */
  const finish = (callback?: () => void) => {
    if (cursor.finished) {
      return;
    }
    cursor.finished = true;
    const source = cursor.source;
    cursor.source = null;
    source?.close();
    callback?.();
  };

  attachEventSource(
    new EventSource(`/api/agent/stream?${qs.toString()}`),
    cursor,
    handlers,
    finish,
  );

  return () => finish();
}

/**
 * POST 版 SSE 流：结构化规划表单（含参考攻略正文）专用。
 * EventSource 只能 GET、消息走 URL，长文本（攻略正文可上万字）放不下；
 * fetch POST 走请求体 + Cookie 认证，用 ReadableStream 手工解析 SSE 帧。
 * 返回关闭函数（语义与 streamAgentChat 一致）。
 *
 * 断线续传（V28）：手工解析 `id:` 行记录 runId / lastSeq；读流异常或提前 EOF 时
 * 改用 GET resume 接口继续同一 run（后续事件名相同，仍走 dispatchFrame）。
 */
export async function streamAgentChatPost(
  params: AgentStreamParams,
  handlers: AgentStreamHandlers,
): Promise<() => void> {
  const controller = new AbortController();
  const cursor = createStreamCursor();
  const finish = (callback?: () => void) => {
    if (cursor.finished) {
      return;
    }
    cursor.finished = true;
    const source = cursor.source;
    cursor.source = null;
    source?.close();
    controller.abort();
    callback?.();
  };

  /** 读流中断且没收到终态：能续传就续传，否则保持改造前的终止语义 */
  const handleBrokenStream = (fallbackMessage?: string) => {
    if (cursor.finished) {
      return;
    }
    if (cursor.runId && cursor.lastSeq > 0 && !cursor.resumed) {
      startStreamResume(cursor, handlers, finish);
      return;
    }
    finish(() => handlers.onError?.(fallbackMessage || CONNECTION_LOST_MESSAGE));
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
        let eventId = '';
        const dataLines: string[] = [];
        for (const line of block.split('\n')) {
          if (line.startsWith('event:')) {
            event = line.slice(6).trim();
          } else if (line.startsWith('id:')) {
            eventId = line.slice(3).trim();
          } else if (line.startsWith('data:')) {
            dataLines.push(line.slice(5).trimStart());
          }
        }
        const raw = dataLines.join('\n');
        if (!raw && event === 'message') {
          return;
        }
        const data = parseFrameData(raw);
        // 续传补发与已有事件重叠时丢弃；没有 id 的帧照常分发
        if (!advanceCursor(cursor, eventId)) {
          return;
        }
        if (event === 'run' && data?.runId && !cursor.runId) {
          cursor.runId = String(data.runId);
        }
        if (event === 'resume_timeout') {
          finish(() => handlers.onError?.(RESUME_TIMEOUT_MESSAGE));
          return;
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
      // 连接关闭却没收到终态：已拿到 runId + lastSeq 时先自动续传一次（POST 后续统一走 GET resume）
      handleBrokenStream();
    } catch (e: any) {
      if (e?.name === 'AbortError') {
        finish();
        return;
      }
      handleBrokenStream(e?.message || '无法建立与 Agent 的连接');
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
