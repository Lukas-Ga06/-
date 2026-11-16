import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import {
  buildRateLimitNotice,
  compactParams,
  get,
  getErrorRetryAfter,
  getErrorStatus,
  getPage,
  getRetryAfterSeconds,
  handleResponseError,
  http,
  isAuthExpired,
  readPagination,
  shouldRetryMe,
  unwrapApiResponse,
} from './client';
import { useAuthStore } from '../store/authStore';
import type { UserInfo } from '../types/trip';

const DEMO_USER: UserInfo = { id: 1, username: 'demo', nickname: '演示用户' };

describe('api client 响应处理', () => {
  const originalWindow = (globalThis as any).window;

  beforeEach(() => {
    useAuthStore.getState().setAuth('token-123', DEMO_USER);
    (globalThis as any).window = { location: { href: '/current' } };
  });

  afterEach(() => {
    useAuthStore.getState().logout();
    if (originalWindow === undefined) {
      delete (globalThis as any).window;
    } else {
      (globalThis as any).window = originalWindow;
    }
  });

  it('code 为 0 时直接返回 data', () => {
    expect(unwrapApiResponse({ data: { code: 0, message: 'success', data: { id: 7 } } })).toEqual({ id: 7 });
  });

  it('code 非 0 时以 message 作为错误抛出', async () => {
    await expect(unwrapApiResponse({ data: { code: 400, message: '金额必须大于 0' } })).rejects.toThrow('金额必须大于 0');
  });

  it('登录接口返回 401 时不登出、不跳转（否则输错密码就整页刷新）', async () => {
    const logout = vi.spyOn(useAuthStore.getState(), 'logout');

    await expect(
      handleResponseError({
        config: { url: '/auth/login' },
        response: { status: 401, data: { message: '用户名或密码错误' } },
      }),
    ).rejects.toThrow('用户名或密码错误');

    expect(logout).not.toHaveBeenCalled();
    expect((globalThis as any).window.location.href).toBe('/current');
  });

  it('其它接口返回 401 时登出并跳转登录页', async () => {
    await expect(
      handleResponseError({
        config: { url: '/trips' },
        response: { status: 401, data: { message: '未登录或登录状态已过期' } },
      }),
    ).rejects.toThrow('未登录或登录状态已过期');

    expect(useAuthStore.getState().token).toBeNull();
    expect((globalThis as any).window.location.href).toBe('/login');
  });

  // ---------- B8：429 不等于未登录 ----------

  it('isAuthExpired 只认 401：429/5xx/网络错误都不算登录失效', () => {
    expect(isAuthExpired(401)).toBe(true);
    expect(isAuthExpired(429)).toBe(false);
    expect(isAuthExpired(500)).toBe(false);
    expect(isAuthExpired(503)).toBe(false);
    expect(isAuthExpired(undefined)).toBe(false);
    expect(isAuthExpired(null)).toBe(false);
  });

  it('shouldRetryMe 对 429 与 5xx 退避重试，对 401/4xx 不重试', () => {
    expect(shouldRetryMe(429)).toBe(true);
    expect(shouldRetryMe(500)).toBe(true);
    expect(shouldRetryMe(599)).toBe(true);
    expect(shouldRetryMe(401)).toBe(false);
    expect(shouldRetryMe(404)).toBe(false);
    expect(shouldRetryMe(undefined)).toBe(false);
  });

  it('429 接口错误不清登录态、不跳登录页，并保留状态码与文案', async () => {
    // 注意：这里不用 spyOn(state.logout)——zustand 的 set 会用 Object.assign 复制上一份 state，
    // 一旦某个用例把 logout 换成 spy，后面的 state 会一直带着这个 spy 引用（历史用例已踩过）。
    // 直接断言「登录态还在 + 没有跳转」才是真正要保证的行为。
    let caught: any;
    try {
      await handleResponseError({
        config: { url: '/trips' },
        response: { status: 429, data: { code: 429, message: '操作过于频繁，请 92 秒后再试' } },
      });
    } catch (error) {
      caught = error;
    }

    expect(useAuthStore.getState().token).toBe('token-123');
    expect(useAuthStore.getState().user).toEqual(DEMO_USER);
    expect((globalThis as any).window.location.href).toBe('/current');
    expect(caught?.status).toBe(429);
    expect(caught?.retryAfterSeconds).toBe(92);
    expect(caught?.message).toContain('操作过于频繁');
  });

  it('5xx / 网络错误同样不清登录态', async () => {
    await expect(
      handleResponseError({ config: { url: '/trips' }, response: { status: 503, data: { message: '服务暂不可用' } } }),
    ).rejects.toThrow('服务暂不可用');
    expect(useAuthStore.getState().token).toBe('token-123');

    await expect(handleResponseError({ config: { url: '/trips' }, message: 'Network Error' })).rejects.toThrow(
      'Network Error',
    );
    expect(useAuthStore.getState().token).toBe('token-123');
    expect((globalThis as any).window.location.href).toBe('/current');
  });

  it('getErrorStatus / getErrorRetryAfter 能从 axios 风格错误里取到状态与 Retry-After', () => {
    const axiosError = {
      response: { status: 429, headers: { 'retry-after': '17' }, data: { message: '请求太频繁' } },
    };
    expect(getErrorStatus(axiosError)).toBe(429);
    expect(getErrorRetryAfter(axiosError)).toBe(17);

    const apiError = { status: 503, retryAfterSeconds: undefined };
    expect(getErrorStatus(apiError)).toBe(503);
    expect(getErrorStatus(new Error('Network Error'))).toBeUndefined();
  });

  it('getRetryAfterSeconds 支持响应头与后端中文文案两种来源', () => {
    expect(getRetryAfterSeconds({ 'Retry-After': '5' })).toBe(5);
    expect(getRetryAfterSeconds({ 'retry-after': '0' })).toBe(0);
    expect(getRetryAfterSeconds({}, '操作过于频繁，请 99 秒后再试')).toBe(99);
    expect(getRetryAfterSeconds(undefined, '请求过于频繁')).toBeUndefined();
    expect(getRetryAfterSeconds(null, null)).toBeUndefined();
  });

  it('buildRateLimitNotice 有秒数带秒数，没有则给通用文案', () => {
    expect(buildRateLimitNotice(92)).toBe('请求过于频繁，请 92 秒后重试');
    expect(buildRateLimitNotice(0)).toBe('请求过于频繁，请稍后重试');
    expect(buildRateLimitNotice(undefined)).toBe('请求过于频繁，请稍后重试');
  });
});

describe('分页参数与分页响应兼容（B9）', () => {
  const originalAdapter = (http.defaults as any).adapter;

  afterEach(() => {
    (http.defaults as any).adapter = originalAdapter;
  });

  /** 用自定义 adapter 拦住真实网络，返回指定响应体，并回放 axios 收到的 config */
  function stubResponse(body: unknown) {
    let captured: any = null;
    (http.defaults as any).adapter = async (config: any) => {
      captured = config;
      return { data: body, status: 200, statusText: 'OK', headers: {}, config };
    };
    return () => captured;
  }

  it('compactParams 丢弃 undefined，不会拼出 offset=undefined', () => {
    expect(compactParams()).toEqual({});
    expect(compactParams({ limit: 20, offset: undefined })).toEqual({ limit: 20 });
    expect(compactParams({ limit: 20, offset: 0 })).toEqual({ limit: 20, offset: 0 });
  });

  it('get：params 进 query，undefined 的键不出现，仍只返回 data', async () => {
    const captured = stubResponse({ code: 0, message: 'success', data: [{ id: 1 }] });

    const data = await get('/trips/7/expenses', { limit: 20, offset: undefined });

    expect(data).toEqual([{ id: 1 }]);
    expect(captured().url).toBe('/trips/7/expenses');
    expect(captured().method).toBe('get');
    expect(captured().params).toEqual({ limit: 20 });
  });

  it('getPage：读取 pagination 的 total/limit/offset/hasMore', async () => {
    const items = Array.from({ length: 20 }, (_, i) => ({ id: i }));
    stubResponse({
      code: 0,
      message: 'success',
      data: items,
      pagination: { total: 25, limit: 20, offset: 0, hasMore: true },
    });

    const page = await getPage('/memos', { limit: 20, offset: 0 });

    expect(page.items).toHaveLength(20);
    expect(page.pagination).toEqual({ total: 25, limit: 20, offset: 0, hasMore: true });
  });

  it('getPage：后端没有 pagination 时降级为 null（单页、无更多）', async () => {
    stubResponse({ code: 0, message: 'success', data: [{ id: 1 }] });

    const page = await getPage('/memos', { limit: 20, offset: 40 });

    expect(page).toEqual({ items: [{ id: 1 }], pagination: null });
  });

  it('getPage：data 不是数组时给空列表，不返回 undefined', async () => {
    stubResponse({ code: 0, message: 'success', data: null });

    expect(await getPage('/memos')).toEqual({ items: [], pagination: null });
  });

  it('readPagination：缺 hasMore 时按 offset + 本页条数 < total 推断', () => {
    expect(readPagination({ total: 25, limit: 20, offset: 0 }, new Array(20).fill(0))?.hasMore).toBe(true);
    expect(readPagination({ total: 25, limit: 20, offset: 20 }, new Array(5).fill(0))?.hasMore).toBe(false);
    expect(readPagination({ total: 25, limit: 20, offset: 0 }, new Array(20).fill(0))?.total).toBe(25);
  });

  it('readPagination：结构非法即返回 null，不抛错', () => {
    expect(readPagination(undefined)).toBeNull();
    expect(readPagination(null)).toBeNull();
    expect(readPagination({ total: 'many' })).toBeNull();
    expect(readPagination({ total: -1 })).toBeNull();
    expect(readPagination([1, 2, 3])).toBeNull();
  });

  it('unwrapApiResponse：keepEnvelope 时保留顶层 pagination', () => {
    const result = unwrapApiResponse({
      data: { code: 0, data: [{ id: 1 }], pagination: { total: 1, limit: 20, offset: 0, hasMore: false } },
      config: { keepEnvelope: true },
    });

    expect(result).toEqual({
      data: [{ id: 1 }],
      pagination: { total: 1, limit: 20, offset: 0, hasMore: false },
    });
  });

  it('unwrapApiResponse：keepEnvelope 也不吞业务错误', async () => {
    await expect(
      unwrapApiResponse({ data: { code: 500, message: '额度不足' }, config: { keepEnvelope: true } }),
    ).rejects.toThrow('额度不足');
  });
});
