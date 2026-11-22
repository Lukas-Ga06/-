#!/usr/bin/env node
/**
 * 可控的 OpenAI 兼容 LLM 替身（韧性测试专用，不依赖任何第三方包）。
 *
 * 为什么需要它：要验证后端的「慢依赖 / 第三方失败」行为，必须能确定性地制造
 *   1) 读超时（sleep 超过 LLM_READ_TIMEOUT_MS=30000）；
 *   2) HTTP 402 / 500 等错误状态；
 * 而不是靠真实厂商的偶发抖动。替身只替换「模型这一端」的 HTTP 应答，
 * 后端仍然走真实的 OpenAiCompatibleLlmClient → HTTP → 解析 → 降级路径，
 * 因此观察到的超时、降级、日志、线程行为都是产品代码本身的行为。
 *
 * 接口：
 *   POST /v1/chat/completions   OpenAI 兼容，行为由当前 mode 决定
 *   GET  /__stats               统计（含每个请求的到达/应答/客户端是否先断开）
 *   POST /__mode                { "mode": "ok|slow|http402|http500|slowok", "delayMs": 35000 }
 *   POST /__reset               清空统计
 *
 * 环境变量：STUB_PORT（默认 18110）、STUB_LOG（日志文件，默认 stdout）
 */
import { createServer } from 'node:http';
import { appendFileSync } from 'node:fs';

const PORT = Number(process.env.STUB_PORT || 18110);
const LOG_FILE = process.env.STUB_LOG || '';

let cfg = { mode: 'ok', delayMs: 35_000 };
let seq = 0;
const requests = [];

function log(line) {
  const text = `[stub ${new Date().toISOString()}] ${line}\n`;
  if (LOG_FILE) {
    try {
      appendFileSync(LOG_FILE, text, 'utf8');
    } catch {
      /* 日志失败不影响测试 */
    }
  }
  process.stdout.write(text);
}

/** OpenAI 兼容的 chat.completion 应答体；content 故意是 "{}"，让产品落回规则路径 */
function completionBody(id, content) {
  return JSON.stringify({
    id: `stub-${id}`,
    object: 'chat.completion',
    created: Math.floor(Date.now() / 1000),
    model: 'resilience-stub',
    choices: [{ index: 0, message: { role: 'assistant', content }, finish_reason: 'stop' }],
    usage: { prompt_tokens: 0, completion_tokens: 0, total_tokens: 0 },
  });
}

const server = createServer((req, res) => {
  const url = req.url || '/';

  if (req.method === 'GET' && url === '/__stats') {
    res.writeHead(200, { 'Content-Type': 'application/json' });
    res.end(JSON.stringify({ cfg, total: requests.length, requests }, null, 2));
    return;
  }
  if (req.method === 'POST' && url === '/__mode') {
    let raw = '';
    req.on('data', (c) => (raw += c));
    req.on('end', () => {
      try {
        const body = JSON.parse(raw || '{}');
        cfg = {
          mode: body.mode || cfg.mode,
          delayMs: body.delayMs ?? cfg.delayMs,
        };
        log(`mode -> ${cfg.mode} delayMs=${cfg.delayMs}`);
        res.writeHead(200, { 'Content-Type': 'application/json' });
        res.end(JSON.stringify({ ok: true, cfg }));
      } catch (e) {
        res.writeHead(400).end(String(e));
      }
    });
    return;
  }
  if (req.method === 'POST' && url === '/__reset') {
    requests.length = 0;
    seq = 0;
    res.writeHead(200, { 'Content-Type': 'application/json' });
    res.end(JSON.stringify({ ok: true }));
    return;
  }
  if (!(req.method === 'POST' && url.startsWith('/v1/chat/completions'))) {
    res.writeHead(404).end('not found');
    return;
  }

  seq += 1;
  const record = {
    id: seq,
    arrivedAt: new Date().toISOString(),
    mode: cfg.mode,
    delayMs: cfg.mode === 'slow' || cfg.mode === 'slowok' ? cfg.delayMs : 0,
    promptBytes: 0,
    finished: false,
    clientAborted: false,
    answeredStatus: null,
  };
  requests.push(record);

  let raw = '';
  req.on('data', (c) => (raw += c));
  req.on('end', () => {
    record.promptBytes = raw.length;
    log(`#${record.id} arrive mode=${record.mode} delay=${record.delayMs} prompt=${raw.length}B`);

    const finish = () => {
      if (record.finished) return;
      record.finished = true;
      record.finishedAt = new Date().toISOString();
      try {
        if (cfg.mode === 'http402') {
          record.answeredStatus = 402;
          res.writeHead(402, { 'Content-Type': 'application/json' });
          res.end(JSON.stringify({ error: { message: 'Insufficient Balance (stub)', type: 'insufficient_quota', code: 'insufficient_balance' } }));
        } else if (cfg.mode === 'http500') {
          record.answeredStatus = 500;
          res.writeHead(500, { 'Content-Type': 'application/json' });
          res.end(JSON.stringify({ error: { message: 'stub internal error', type: 'server_error' } }));
        } else {
          record.answeredStatus = 200;
          res.writeHead(200, { 'Content-Type': 'application/json' });
          res.end(completionBody(record.id, '{}'));
        }
        log(`#${record.id} answered status=${record.answeredStatus} clientAborted=${record.clientAborted}`);
      } catch (e) {
        log(`#${record.id} write failed: ${e.message}`);
      }
    };

    const delay = record.delayMs;
    const t = setTimeout(finish, delay);
    // 后端读超时后主动断开会触发 res close（未写完）；据此证明超时确实发生在传输层
    res.on('close', () => {
      if (!record.finished) {
        record.clientAborted = true;
        record.clientAbortedAt = new Date().toISOString();
        clearTimeout(t);
        log(`#${record.id} client aborted before answer (server-side timeout evidence)`);
      }
    });
  });
});

server.listen(PORT, '127.0.0.1', () => {
  log(`listening on http://127.0.0.1:${PORT}/v1 (mode=ok)`);
});

process.on('SIGTERM', () => server.close(() => process.exit(0)));
process.on('SIGINT', () => server.close(() => process.exit(0)));