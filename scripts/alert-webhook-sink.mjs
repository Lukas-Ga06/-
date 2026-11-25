#!/usr/bin/env node
/**
 * alert-webhook-sink.mjs — 本地最小的 Alertmanager webhook 接收端（零依赖，Node 18+/20+）。
 *
 * 用途：在拿到真实 IM/邮件 webhook 之前，先把「规则 firing → Alertmanager 路由 → 送达」这条链路
 * 跑通并留证。它只做一件事：把收到的每个 webhook POST 原样落盘（一行 JSONL 摘要 + 一份原始 payload），
 * 返回 200，让 Alertmanager 认为送达成功。
 *
 * 它不是通知渠道：不推送到任何人的手机/邮箱，也不替代 §4 的人工确认。
 *
 * 用法：
 *   node scripts/alert-webhook-sink.mjs --out .evidence/alert-routing-20261125-190000 --port 19099
 *
 * 参数：
 *   --out   必填，证据目录（不存在则创建）；摘要写 alerts.jsonl，原始 payload 写 deliveries/。
 *   --port  监听端口，默认 19099；固定绑定 127.0.0.1（不对外可达）。
 *   --path  webhook 路径，默认 /alert；其它路径返回 404。
 *
 * 退出码：0 正常退出；2 参数错误。
 */

import { createServer } from 'node:http';
import { appendFileSync, mkdirSync, writeFileSync } from 'node:fs';
import { join, resolve } from 'node:path';

function parseArgs(argv) {
  const opts = { port: 19099, path: '/alert', out: '' };
  for (let i = 0; i < argv.length; i += 1) {
    const arg = argv[i];
    if (arg === '--out') opts.out = argv[++i];
    else if (arg === '--port') opts.port = Number(argv[++i]);
    else if (arg === '--path') opts.path = argv[++i];
    else throw new Error(`未知参数：${arg}`);
  }
  if (!opts.out) throw new Error('--out 必填');
  if (!Number.isInteger(opts.port) || opts.port < 1 || opts.port > 65535) throw new Error('--port 非法');
  return opts;
}

let opts;
try {
  opts = parseArgs(process.argv.slice(2));
} catch (err) {
  console.error(`[FAIL] ${err.message}`);
  process.exit(2);
}

const outDir = resolve(process.cwd(), opts.out);
const deliveriesDir = join(outDir, 'deliveries');
mkdirSync(deliveriesDir, { recursive: true });
const jsonlPath = join(outDir, 'alerts.jsonl');

let seq = 0;

const server = createServer((req, res) => {
  if (req.method === 'GET' && req.url === '/healthz') {
    res.writeHead(200, { 'content-type': 'application/json' });
    res.end(JSON.stringify({ ok: true, deliveries: seq }));
    return;
  }
  if (req.method !== 'POST' || req.url !== opts.path) {
    res.writeHead(404, { 'content-type': 'text/plain' });
    res.end('not found');
    return;
  }

  const chunks = [];
  req.on('data', (c) => chunks.push(c));
  req.on('end', () => {
    seq += 1;
    const receivedAt = new Date().toISOString();
    const body = Buffer.concat(chunks).toString('utf8');
    let payload = null;
    try {
      payload = JSON.parse(body);
    } catch {
      /* 非 JSON 也照样留证，避免"收到了但解析失败"被吞掉 */
    }

    const alerts = Array.isArray(payload?.alerts) ? payload.alerts : [];
    const summary = {
      seq,
      receivedAt,
      remote: req.socket.remoteAddress,
      receiver: payload?.receiver ?? '(missing)',
      status: payload?.status ?? '(missing)',
      groupKey: payload?.groupKey ?? '',
      externalURL: payload?.externalURL ?? '',
      alertCount: alerts.length,
      alerts: alerts.map((a) => ({
        alertname: a?.labels?.alertname ?? '(missing)',
        severity: a?.labels?.severity ?? '',
        instance: a?.labels?.instance ?? '',
        status: a?.status ?? '',
        hasSummary: Boolean(a?.annotations?.summary),
        hasRunbookUrl: Boolean(a?.annotations?.runbook_url),
      })),
      rawFile: '',
    };

    const safeReceiver = String(summary.receiver).replace(/[^A-Za-z0-9_.-]/g, '_');
    const name = `${String(seq).padStart(3, '0')}-${receivedAt.replace(/[:.]/g, '-')}-${safeReceiver}-${summary.status}.json`;
    writeFileSync(join(deliveriesDir, name), body, 'utf8');
    summary.rawFile = `deliveries/${name}`;
    appendFileSync(jsonlPath, `${JSON.stringify(summary)}\n`, 'utf8');

    console.log(
      `[recv] #${seq} receiver=${summary.receiver} status=${summary.status} alerts=${summary.alertCount} ` +
        `[${summary.alerts.map((a) => a.alertname).join(',')}]`,
    );

    res.writeHead(200, { 'content-type': 'application/json' });
    res.end('{"ok":true}');
  });
});

server.listen(opts.port, '127.0.0.1', () => {
  console.log(`[sink] listening http://127.0.0.1:${opts.port}${opts.path} → ${outDir}`);
});

function shutdown() {
  server.close(() => process.exit(0));
  setTimeout(() => process.exit(0), 1500).unref();
}
process.on('SIGINT', shutdown);
process.on('SIGTERM', shutdown);