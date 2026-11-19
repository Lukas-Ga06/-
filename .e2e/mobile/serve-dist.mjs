#!/usr/bin/env node
/**
 * 用 Node 原生 http 把 travel-agent-web/dist 起成 4173 静态站，并把 /api/* 反代到真实后端。
 *
 * 目的：Playwright 的 service worker / 安装性检查需要「生产构建 + 同源 /api」，
 * 而 playwright.config.ts 固定 serviceWorkers: 'block'，所以 PWA 验收不塞进 e2e 目录，
 * 这里自带一个最小静态服务器（不依赖 vite preview、不装任何 npm 包）。
 *
 * 用法：
 *   node .e2e/mobile/serve-dist.mjs [--port 4173] [--api http://127.0.0.1:18090] [--quiet] [--harness-dir <dir>]
 *
 * 行为：
 *   - GET/HEAD 命中 dist 里的文件 → 直接返回（带长缓存，hash 文件名可长期缓存）
 *   - /api/* → 转发到后端（保留 method / header / body / set-cookie / 流式响应体）
 *   - 其它无扩展名的 GET → SPA fallback 到 index.html（对应 workbox navigateFallback 的在线形态）
 *   - sw.js / registerSW.js / index.html / manifest.webmanifest 一律 no-store，避免浏览器缓存旧 SW
 *
 * 可选（仅 PWA 验收取证用，默认关闭、不影响上面的默认行为）：
 *   --harness-dir <dir>  在 /__e2e/* 下提供该目录里的同源自测页；
 *                        并开放 POST /__e2e/save?path=<相对仓库根的文件>&kind=png|json
 *                        供自测页把结果/截图落盘（path 只允许 .evidence/ 或 .e2e/ 前缀）。
 */
import http from 'node:http';
import https from 'node:https';
import { createReadStream, existsSync, mkdirSync, statSync, writeFileSync } from 'node:fs';
import { dirname, extname, join, normalize, resolve, sep } from 'node:path';
import { fileURLToPath } from 'node:url';

const HERE = dirname(fileURLToPath(import.meta.url));
const REPO_ROOT = resolve(HERE, '..', '..');
const DEFAULT_DIST = join(REPO_ROOT, 'travel-agent-web', 'dist');

function parseArgs(argv) {
  const opts = { port: 4173, api: 'http://127.0.0.1:18090', dist: DEFAULT_DIST, quiet: false, harnessDir: null };
  for (let i = 0; i < argv.length; i += 1) {
    const arg = argv[i];
    if (arg === '--port') opts.port = Number(argv[++i]);
    else if (arg === '--api') opts.api = String(argv[++i]);
    else if (arg === '--dist') opts.dist = resolve(String(argv[++i]));
    else if (arg === '--harness-dir') opts.harnessDir = resolve(String(argv[++i]));
    else if (arg === '--quiet') opts.quiet = true;
    else if (arg === '--help' || arg === '-h') {
      process.stdout.write(
        'usage: node .e2e/mobile/serve-dist.mjs [--port 4173] [--api http://127.0.0.1:18090] [--dist <dir>] [--quiet]\n',
      );
      process.exit(0);
    } else {
      process.stderr.write(`unknown arg: ${arg}\n`);
      process.exit(2);
    }
  }
  return opts;
}

const opts = parseArgs(process.argv.slice(2));
const API_BASE = new URL(opts.api);
const API_MOD = API_BASE.protocol === 'https:' ? https : http;

if (!existsSync(join(opts.dist, 'index.html'))) {
  process.stderr.write(`[serve-dist] dist not found or missing index.html: ${opts.dist}\n`);
  process.exit(1);
}

const MIME = {
  '.html': 'text/html; charset=utf-8',
  '.js': 'text/javascript; charset=utf-8',
  '.mjs': 'text/javascript; charset=utf-8',
  '.css': 'text/css; charset=utf-8',
  '.json': 'application/json; charset=utf-8',
  '.webmanifest': 'application/manifest+json; charset=utf-8',
  '.svg': 'image/svg+xml',
  '.png': 'image/png',
  '.jpg': 'image/jpeg',
  '.jpeg': 'image/jpeg',
  '.gif': 'image/gif',
  '.webp': 'image/webp',
  '.ico': 'image/x-icon',
  '.woff': 'font/woff',
  '.woff2': 'font/woff2',
  '.ttf': 'font/ttf',
  '.map': 'application/json; charset=utf-8',
  '.txt': 'text/plain; charset=utf-8',
  '.pdf': 'application/pdf',
  '.wasm': 'application/wasm',
};

/** 这些路径不进 HTTP 缓存：SW 更新与 app shell 必须每次协商 */
const NO_STORE_FILES = new Set(['/sw.js', '/registerSW.js', '/index.html', '/manifest.webmanifest']);

function contentTypeFor(filePath) {
  return MIME[extname(filePath).toLowerCase()] ?? 'application/octet-stream';
}

function log(...args) {
  if (!opts.quiet) process.stdout.write(`[serve-dist] ${args.join(' ')}\n`);
}

/** 解析并锁定到 dist 内的真实文件路径，拒绝 ../ 越界 */
function resolveStatic(urlPath) {
  const decoded = decodeURIComponent(urlPath.split('?')[0]);
  const withoutLeading = decoded.replace(/^\/+/, '');
  const candidate = normalize(join(opts.dist, withoutLeading));
  if (!candidate.startsWith(opts.dist + sep) && candidate !== opts.dist) return null;
  if (!existsSync(candidate)) return null;
  const st = statSync(candidate);
  if (st.isDirectory()) {
    const indexFile = join(candidate, 'index.html');
    return existsSync(indexFile) ? { file: indexFile, stat: statSync(indexFile) } : null;
  }
  return { file: candidate, stat: st };
}

function sendFile(req, res, filePath, stat) {
  const urlPath = req.url.split('?')[0];
  const headers = {
    'Content-Type': contentTypeFor(filePath),
    'Content-Length': stat.size,
    'Accept-Ranges': 'bytes',
    'X-Content-Type-Options': 'nosniff',
  };
  if (NO_STORE_FILES.has(urlPath)) {
    headers['Cache-Control'] = 'no-store, must-revalidate';
  } else if (/\.[0-9a-zA-Z_-]{8,}\.(js|css)$/.test(urlPath)) {
    // 构建产物带内容 hash：可以长期缓存
    headers['Cache-Control'] = 'public, max-age=31536000, immutable';
  } else {
    headers['Cache-Control'] = 'no-cache';
  }
  res.writeHead(200, headers);
  if (req.method === 'HEAD') {
    res.end();
    return;
  }
  const stream = createReadStream(filePath);
  stream.on('error', () => {
    res.destroy();
  });
  stream.pipe(res);
}

function proxyApi(req, res) {
  const targetPath = req.url; // 已经以 /api/ 开头，后端同样是 /api/* 前缀
  const headers = { ...req.headers, host: API_BASE.host };
  // 同源反代：告诉后端真实来源，便于排查；不影响鉴权
  headers['x-forwarded-host'] = req.headers.host ?? '';
  headers['x-forwarded-proto'] = 'http';

  const upstream = API_MOD.request(
    {
      protocol: API_BASE.protocol,
      hostname: API_BASE.hostname,
      port: API_BASE.port,
      method: req.method,
      path: targetPath,
      headers,
    },
    (upRes) => {
      const outHeaders = { ...upRes.headers };
      // set-cookie 是数组，必须原样回传给浏览器，否则登录 Cookie 丢失
      if (upRes.headers['set-cookie']) outHeaders['set-cookie'] = upRes.headers['set-cookie'];
      // SSE 不能缓冲：直接透传并关掉中间件可能的压缩
      outHeaders['x-accel-buffering'] = 'no';
      delete outHeaders['content-length']; // 交给 Node 自己 chunked，避免上游长度与实际不一致
      res.writeHead(upRes.statusCode ?? 502, outHeaders);
      upRes.pipe(res);
    },
  );

  upstream.on('error', (err) => {
    log(`proxy error ${req.method} ${targetPath}: ${err.message}`);
    if (!res.headersSent) {
      res.writeHead(502, { 'Content-Type': 'application/json; charset=utf-8', 'Cache-Control': 'no-store' });
    }
    res.end(JSON.stringify({ code: 502, message: `上游不可达: ${err.message}`, data: null }));
  });

  req.pipe(upstream);
}

/** 提供 --harness-dir 下的同源自测页（/__e2e/<file>） */
function serveHarnessFile(req, res, urlPath) {
  const rel = decodeURIComponent(urlPath.slice('/__e2e/'.length));
  const base = opts.harnessDir;
  const candidate = normalize(join(base, rel || 'pwa-harness.html'));
  if (!candidate.startsWith(base + sep) && candidate !== base) {
    res.writeHead(403, { 'Content-Type': 'text/plain; charset=utf-8' });
    res.end('403 Forbidden');
    return;
  }
  if (!existsSync(candidate) || !statSync(candidate).isFile()) {
    res.writeHead(404, { 'Content-Type': 'text/plain; charset=utf-8' });
    res.end('404 Not Found');
    return;
  }
  res.writeHead(200, {
    'Content-Type': contentTypeFor(candidate),
    'Cache-Control': 'no-store',
    'X-Content-Type-Options': 'nosniff',
  });
  if (req.method === 'HEAD') {
    res.end();
    return;
  }
  createReadStream(candidate).pipe(res);
}

/** 自测页把 JSON 结果 / PNG 截图落盘：POST /__e2e/save?path=.evidence/...&kind=json|png */
function saveEvidence(req, res) {
  const MAX_BYTES = 32 * 1024 * 1024;
  const chunks = [];
  let size = 0;
  let aborted = false;
  req.on('data', (chunk) => {
    size += chunk.length;
    if (size > MAX_BYTES) {
      aborted = true;
      res.writeHead(413, { 'Content-Type': 'application/json; charset=utf-8' });
      res.end(JSON.stringify({ ok: false, error: 'payload too large' }));
      req.destroy();
      return;
    }
    chunks.push(chunk);
  });
  req.on('end', () => {
    if (aborted) return;
    const url = new URL(req.url ?? '', 'http://127.0.0.1');
    const rel = (url.searchParams.get('path') ?? '').replace(/\\/g, '/');
    const kind = (url.searchParams.get('kind') ?? 'json').toLowerCase();
    if (!/^(\.evidence|\.e2e)\//.test(rel) || rel.includes('..')) {
      res.writeHead(400, { 'Content-Type': 'application/json; charset=utf-8' });
      res.end(JSON.stringify({ ok: false, error: 'path must be under .evidence/ or .e2e/ and may not contain ..' }));
      return;
    }
    const target = join(REPO_ROOT, rel);
    try {
      mkdirSync(dirname(target), { recursive: true });
      const raw = Buffer.concat(chunks).toString('utf8');
      let bytes = 0;
      if (kind === 'png') {
        const base64 = raw.replace(/^data:image\/png;base64,/, '').trim();
        const buf = Buffer.from(base64, 'base64');
        writeFileSync(target, buf);
        bytes = buf.length;
      } else {
        writeFileSync(target, raw, 'utf8');
        bytes = Buffer.byteLength(raw);
      }
      log(`saved ${kind} -> ${rel} (${bytes} bytes)`);
      res.writeHead(200, { 'Content-Type': 'application/json; charset=utf-8', 'Cache-Control': 'no-store' });
      res.end(JSON.stringify({ ok: true, path: rel, bytes }));
    } catch (err) {
      res.writeHead(500, { 'Content-Type': 'application/json; charset=utf-8' });
      res.end(JSON.stringify({ ok: false, error: String(err?.message ?? err) }));
    }
  });
}

const server = http.createServer((req, res) => {
  const started = Date.now();
  res.on('finish', () => {
    log(`${req.method} ${req.url} -> ${res.statusCode} ${Date.now() - started}ms`);
  });

  const urlPath = (req.url ?? '/').split('?')[0];

  if (urlPath === '/api' || urlPath.startsWith('/api/')) {
    proxyApi(req, res);
    return;
  }

  // ---- 可选：PWA 取证用的同源自测页 + 结果落盘（只有传 --harness-dir 时才存在） ----
  if (opts.harnessDir && (urlPath === '/__e2e' || urlPath.startsWith('/__e2e/'))) {
    if (req.method === 'POST' && urlPath === '/__e2e/save') {
      saveEvidence(req, res);
      return;
    }
    if (req.method === 'GET' || req.method === 'HEAD') {
      serveHarnessFile(req, res, urlPath);
      return;
    }
    res.writeHead(405, { Allow: 'GET, HEAD, POST' });
    res.end('405 Method Not Allowed');
    return;
  }

  if (req.method !== 'GET' && req.method !== 'HEAD') {
    res.writeHead(405, { Allow: 'GET, HEAD', 'Content-Type': 'text/plain; charset=utf-8' });
    res.end('405 Method Not Allowed');
    return;
  }

  const hit = resolveStatic(req.url ?? '/');
  if (hit) {
    sendFile(req, res, hit.file, hit.stat);
    return;
  }

  const looksLikeAsset = extname(urlPath) !== '';
  if (looksLikeAsset) {
    res.writeHead(404, { 'Content-Type': 'text/plain; charset=utf-8', 'Cache-Control': 'no-store' });
    res.end('404 Not Found');
    return;
  }

  // SPA fallback：浏览器直接访问 /trip/12 这类前端路由时回 index.html
  const indexHtml = join(opts.dist, 'index.html');
  sendFile(req, res, indexHtml, statSync(indexHtml));
});

server.on('error', (err) => {
  process.stderr.write(`[serve-dist] listen failed on 127.0.0.1:${opts.port}: ${err.message}\n`);
  process.exit(1);
});

server.listen(opts.port, '127.0.0.1', () => {
  process.stdout.write(
    `[serve-dist] dist=${opts.dist}\n[serve-dist] listening http://127.0.0.1:${opts.port}  (api -> ${opts.api})\n`,
  );
});

for (const signal of ['SIGINT', 'SIGTERM']) {
  process.on(signal, () => {
    log(`received ${signal}, shutting down`);
    server.close(() => process.exit(0));
  });
}