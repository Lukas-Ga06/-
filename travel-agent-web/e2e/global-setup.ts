import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { request, type APIResponse } from '@playwright/test';

/**
 * e2e 全局准备：**只登录一次**，把登录态（httpOnly Cookie）写成 storageState 供所有用例复用。
 *
 * 为什么必须这样做：后端的 /api/auth/login 与 /api/auth/me 共用同一个「按 IP 计数」的 auth
 * 限流桶（默认 10 次 / 300 秒）。旧写法每个 test 各自登录，一轮 e2e 约 8 次 auth 请求，
 * 5 分钟内跑第二轮必然 429，前端把 429 当未登录 → 停在登录页 → 用例必挂。
 *
 * 因此：
 * 1) e2e 专用后端必须关闭限流（RATE_LIMIT_ENABLED=false，CI 见 .github/workflows/ci.yml）；
 * 2) 本文件只在 globalSetup 里登录一次；
 * 3) 登录失败时抛出带 HTTP 状态 + 响应体的错误，让 429 文案直接出现在报错里。
 */
// 项目为 ESM（type: module），没有 __dirname
const SETUP_DIR = path.dirname(fileURLToPath(import.meta.url));
const AUTH_FILE = path.resolve(SETUP_DIR, '.auth/demo.json');
const BASE_URL = process.env.E2E_BASE_URL ?? 'http://localhost:4173';
const DEMO = { username: 'demo', password: 'demo123' };

function describeError(error: unknown): string {
  if (error instanceof Error) return `${error.name}: ${error.message}`;
  return String(error);
}

async function readBody(response: APIResponse): Promise<string> {
  try {
    return (await response.text()).slice(0, 800);
  } catch {
    return '<响应体不可读>';
  }
}

export default async function globalSetup(): Promise<void> {
  const api = await request.newContext({
    baseURL: BASE_URL,
    extraHTTPHeaders: { Accept: 'application/json' },
  });

  try {
    // 1) 探活：/actuator/health 不经过前端代理，这里用需要登录的 /api/system/status ——
    //    返回 401 也说明后端可达；只有连接失败/5xx 才是「后端没起」或「preview 没代理 /api」。
    try {
      const probe = await api.get('/api/system/status', { timeout: 10_000 });
      if (probe.status() >= 500) {
        throw new Error(`探活接口返回 HTTP ${probe.status()}：${await readBody(probe)}`);
      }
    } catch (error) {
      throw new Error(
        [
          `无法连接 e2e 后端：${BASE_URL}/api/system/status（${describeError(error)}）`,
          '排查顺序：① 后端是否已启动；② vite preview 是否把 /api 代理到该后端。',
          '本地示例：',
          '  java -jar travel-agent-server-0.1.0.jar --server.port=18081 --travel.rate-limit.enabled=false',
          '  E2E_API_TARGET=http://localhost:18081 E2E_BASE_URL=http://localhost:4173 npx playwright test',
          'CI 由 .github/workflows/ci.yml 的 e2e job 启动 8080 后端并设置 RATE_LIMIT_ENABLED=false。',
        ].join('\n'),
      );
    }

    // 2) 登录一次：失败时把状态码与响应体原样抛出（429 的「请 N 秒后再试」会直接可见）
    const login = await api.post('/api/auth/login', { data: DEMO, timeout: 15_000 });
    if (!login.ok()) {
      const body = await readBody(login);
      const hint =
        login.status() === 429
          ? '\n提示：登录接口被限流（429）。e2e 后端必须设 RATE_LIMIT_ENABLED=false；本地跑第二轮前不要复用被限流的后端进程。'
          : login.status() === 401
            ? '\n提示：demo 账号不可用（默认由 DataInitializer 创建 demo/demo123）。'
            : '';
      throw new Error(
        `e2e 登录失败：HTTP ${login.status()} ${login.statusText()}\nURL: ${BASE_URL}/api/auth/login\n响应体：${body}${hint}`,
      );
    }

    const state = await api.storageState();
    if (!state.cookies.length) {
      throw new Error(
        'e2e 登录返回 200 但没有下发任何 Cookie：检查 COOKIE_SECURE 是否为 true（http 下浏览器不会保存 Secure Cookie）。',
      );
    }

    fs.mkdirSync(path.dirname(AUTH_FILE), { recursive: true });
    await api.storageState({ path: AUTH_FILE });
    console.log(`[e2e] globalSetup 已登录 demo，storageState 写入 ${AUTH_FILE}（${state.cookies.length} 个 Cookie）`);
  } finally {
    await api.dispose();
  }
}