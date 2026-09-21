import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { handleResponseError, unwrapApiResponse } from './client';
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
});
