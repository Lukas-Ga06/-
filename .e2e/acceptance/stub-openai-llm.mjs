#!/usr/bin/env node
/**
 * 本地 OpenAI 兼容 LLM 替身（仅用于验收环境）。
 *
 * 背景：正式验收机器上的 DeepSeek Key 余额为 0（真实调用返回 402 Insufficient Balance），
 * 无法用真模型验证 FR-03「Agent 自主工具调用循环」。本替身让后端走**真实的
 * OpenAiCompatibleLlmClient → HTTP → 决策解析 → ToolInvoker** 全链路，
 * 只把「模型这一端」换成确定性的协议应答：
 *   - 研究决策 Prompt（含「动作协议」）：第 1 步决定 CALL_TOOL query_weather，拿到观察后 FINAL；
 *   - 其它 Prompt（意图/结构化规划/润色）一律返回 "{}"，让产品落回既有的规则路径。
 *
 * 它不是产品代码，只放在 .e2e 下；真模型复验见 docs 验收报告。
 */
import { createServer } from 'node:http';

const PORT = Number(process.env.STUB_PORT || 18099);
const stats = { total: 0, research: 0, other: 0, toolCalls: 0, finals: 0 };

function contentFor(userText) {
  if (userText.includes('动作协议')) {
    stats.research++;
    const hasObservation = /已收集的观察[（(]时间顺序[）)][：:][\s\S]*?\n\s*\d+\./.test(userText);
    if (!hasObservation) {
      stats.toolCalls++;
      return {
        action: 'CALL_TOOL',
        tool: 'query_weather',
        args: { city: '上海' },
        reason: '先确认目的地当前天气，再决定研究结论',
      };
    }
    stats.finals++;
    return { action: 'FINAL', summary: '已确认上海天气与候选景点，可支撑本次行程规划' };
  }
  stats.other++;
  return {};
}

const server = createServer((req, res) => {
  if (req.method !== 'POST' || !req.url.startsWith('/v1/chat/completions')) {
    res.writeHead(404).end('not found');
    return;
  }
  let raw = '';
  req.on('data', (c) => (raw += c));
  req.on('end', () => {
    let userText = '';
    try {
      const body = JSON.parse(raw);
      const msgs = body.messages || [];
      userText = msgs.map((m) => m.content || '').join('\n');
    } catch {
      /* ignore */
    }
    stats.total++;
    const content = JSON.stringify(contentFor(userText));
    console.log(`[stub] #${stats.total} research=${stats.research} other=${stats.other} toolCalls=${stats.toolCalls} finals=${stats.finals} prompt=${raw.length}B`);
    res.writeHead(200, { 'Content-Type': 'application/json' });
    res.end(
      JSON.stringify({
        id: `stub-${stats.total}`,
        object: 'chat.completion',
        model: 'stub-model',
        choices: [{ index: 0, message: { role: 'assistant', content }, finish_reason: 'stop' }],
        usage: { prompt_tokens: 0, completion_tokens: 0, total_tokens: 0 },
      }),
    );
  });
});

server.listen(PORT, '127.0.0.1', () => console.log(`[stub] OpenAI-compatible stub listening on http://127.0.0.1:${PORT}/v1`));
process.on('SIGTERM', () => server.close(() => process.exit(0)));