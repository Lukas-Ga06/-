import { beforeEach, describe, expect, it, vi } from 'vitest';

const requestMock = vi.hoisted(() => vi.fn());
vi.mock('./client', () => ({ request: requestMock }));

import { clearAccessTicketCache, getAccessTicket } from './ticket';
import { useAuthStore } from '../store/authStore';
import type { UserInfo } from '../types/trip';

const USER: UserInfo = { id: 1, username: 'demo', nickname: '演示用户' };

describe('getAccessTicket', () => {
  beforeEach(() => {
    clearAccessTicketCache();
    requestMock.mockReset();
    useAuthStore.getState().setAuth('token-1', USER);
  });

  it('同一登录态下复用票据，避免照片墙逐张签票', async () => {
    requestMock.mockResolvedValue({ ticket: 't-1', expiresInSeconds: 600 });

    expect(await getAccessTicket()).toBe('t-1');
    expect(await getAccessTicket()).toBe('t-1');
    expect(requestMock).toHaveBeenCalledTimes(1);
  });

  it('并发请求合并为一次', async () => {
    requestMock.mockResolvedValue({ ticket: 't-2', expiresInSeconds: 600 });

    const [a, b] = await Promise.all([getAccessTicket(), getAccessTicket()]);

    expect(a).toBe('t-2');
    expect(b).toBe('t-2');
    expect(requestMock).toHaveBeenCalledTimes(1);
  });

  it('登录态变化后重新签发票据', async () => {
    requestMock.mockResolvedValueOnce({ ticket: 't-a', expiresInSeconds: 600 });
    expect(await getAccessTicket()).toBe('t-a');

    useAuthStore.getState().setAuth('token-2', USER);
    requestMock.mockResolvedValueOnce({ ticket: 't-b', expiresInSeconds: 600 });
    expect(await getAccessTicket()).toBe('t-b');

    expect(requestMock).toHaveBeenCalledTimes(2);
  });

  it('未登录时不发请求', async () => {
    useAuthStore.getState().logout();

    await expect(getAccessTicket()).rejects.toThrow('未登录');
    expect(requestMock).not.toHaveBeenCalled();
  });
});
