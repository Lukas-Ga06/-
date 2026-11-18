/**
 * SSE 事件 → 用户可读文案的纯函数映射。
 * 最初是 agentStore 内部的 STAGE_TEXT 表（ChatPanel 的 .chat-stage 单行渲染），
 * V2.2 抽出为可导出的纯函数，供单行 stage 兜底与 AgentActivityPanel 过程时间线共用。
 * 既有事件的文案一字未改；新增 run 事件刻意不映射单行文案（只进过程时间线），
 * 保持 ChatPanel 单行 stage 行为不变。
 */

export interface ActivityEvent {
  /** SSE 事件名：run / start / intent / planning / tool_call / tool_result / rag_result / plan_update / focus / confirmation / complete / error */
  event: string;
  /** 事件负载（原样保存；渲染时只取白名单字段，工具参数绝不透出原文） */
  data: any;
  /** 收到事件的时间（epoch ms） */
  time: number;
}

const STAGE_TEXT: Record<string, (data: any) => string> = {
  start: () => '已连接 Agent…',
  intent: (d) => `意图识别：${d?.intent ?? '…'}`,
  planning: () => '正在规划行程…',
  tool_call: (d) =>
    d?.tool === 'search_city_online'
      ? '联网查询目的地的景点/酒店/餐厅数据…'
      : `调用工具 ${d?.tool ?? ''}…`,
  tool_result: (d) => `工具 ${d?.tool ?? ''} 执行完成`,
  rag_result: () => '检索旅游知识库…',
  plan_update: () => '生成方案对比…',
  confirmation: () => '等待你的确认…',
};

/**
 * 单行 stage 文案（ChatPanel 兜底渲染）。
 * 未知事件返回 null：调用方据此跳过更新，单行文案保持上一次的值。
 */
export function stageText(event: string, data: any): string | null {
  return STAGE_TEXT[event]?.(data) ?? null;
}

/** 运行标识的短展示：过长时取前 8 位（runId 是 UUID，前 8 位已够区分） */
export function shortRunId(runId: unknown): string {
  const id = typeof runId === 'string' ? runId : '';
  return id.length > 8 ? `${id.slice(0, 8)}…` : id || '…';
}
