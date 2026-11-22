import { expect, type APIResponse } from '@playwright/test';

/**
 * 接口断言：失败时把 HTTP 状态码 + 响应体一起打出来。
 * 旧写法 `expect(res.ok()).toBeTruthy()` 只显示 false，429 的
 * {"code":429,"message":"操作过于频繁，请 92 秒后再试"} 完全看不到，排障成本极高。
 */
export async function expectOk(response: APIResponse, label = '接口请求'): Promise<APIResponse> {
  if (!response.ok()) {
    const body = await response.text().catch(() => '<响应体不可读>');
    const retryAfter = response.headers()['retry-after'];
    const hint = response.status() === 429 ? '（被限流：e2e 后端需设置 RATE_LIMIT_ENABLED=false）' : '';
    throw new Error(
      [
        `${label}失败：HTTP ${response.status()} ${response.statusText()}${hint}`,
        `URL: ${response.url()}`,
        retryAfter ? `Retry-After: ${retryAfter}` : '',
        `响应体：${body.slice(0, 800)}`,
      ]
        .filter(Boolean)
        .join('\n'),
    );
  }
  return response;
}

/** 断言状态码，失败时同样带响应体 */
export async function expectStatus(response: APIResponse, expected: number, label = '接口请求'): Promise<APIResponse> {
  if (response.status() !== expected) {
    const body = await response.text().catch(() => '<响应体不可读>');
    throw new Error(
      `${label}期望 HTTP ${expected}，实际 ${response.status()}\nURL: ${response.url()}\n响应体：${body.slice(0, 800)}`,
    );
  }
  return response;
}

export { expect };