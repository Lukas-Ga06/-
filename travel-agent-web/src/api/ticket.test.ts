import { beforeEach, describe, expect, it, vi } from 'vitest';

const requestMock = vi.hoisted(() => vi.fn());
vi.mock('./client', () => ({ request: requestMock }));

import { clearAccessTicketCache, getAccessTicket } from './ticket';
import { useAuthStore } from '../store/authStore';
import type { UserInfo } from '../types/trip';

const USER: UserInfo = { id: 1, username: 'demo', nickname: '演示用户' };

/** 按 URL 里的 purpose 返回不同票据串，便于断言「照片/SSE 没有串用」 */
function byPurpose(ticketFor: Record<string, string>) {
  return ({ url }: { url: string }) => {
    const matched = Object.keys(ticketFor).find((purpose) => url.includes(`purpose=${purpose}`));
    if (!matched) return Promise.reject(new Error(`unexpected ticket url: ${url}`));
    return Promise.resolve({ ticket: ticketFor[matched], expiresInSeconds: 600, purpose: matched });
  };
}

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
    // 不传用途 = 服务端默认的 SSE 用途
    expect(requestMock).toHaveBeenCalledWith({ url: '/access-ticket?purpose=agent-stream', method: 'post' });
  });

  it('并发请求合并为一次', async () => {
    requestMock.mockResolvedValue({ ticket: 't-2', expiresInSeconds: 600 });

    const [a, b] = await Promise.all([getAccessTicket(), getAccessTicket()]);

    expect(a).toBe('t-2');
    expect(b).toBe('t-2');
    expect(requestMock).toHaveBeenCalledTimes(1);
  });

  it('不同用途各自签票，缓存与在途请求都不串用', async () => {
    requestMock.mockImplementation(
      byPurpose({ 'agent-stream': 'stream-ticket', 'media-read': 'media-ticket' }),
    );

    const [media, stream] = await Promise.all([
      getAccessTicket('media-read'),
      getAccessTicket('agent-stream'),
    ]);

    expect(media).toBe('media-ticket');
    expect(stream).toBe('stream-ticket');
    expect(requestMock).toHaveBeenCalledTimes(2);
    expect(requestMock).toHaveBeenCalledWith({ url: '/access-ticket?purpose=media-read', method: 'post' });

    // 再次取用：两种用途都应命中各自缓存，不再发请求
    expect(await getAccessTicket('media-read')).toBe('media-ticket');
    expect(await getAccessTicket('agent-stream')).toBe('stream-ticket');
    expect(requestMock).toHaveBeenCalledTimes(2);
  });

  it('登录态变化后重新签发票据', async () => {
    requestMock.mockResolvedValueOnce({ ticket: 't-a', expiresInSeconds: 600 });
    expect(await getAccessTicket()).toBe('t-a');

    useAuthStore.getState().setAuth('token-2', USER);
    requestMock.mockResolvedValueOnce({ ticket: 't-b', expiresInSeconds: 600 });
    expect(await getAccessTicket()).toBe('t-b');

    expect(requestMock).toHaveBeenCalledTimes(2);
  });

  it('清缓存后重新签票（登出用）', async () => {
    requestMock.mockResolvedValueOnce({ ticket: 't-1', expiresInSeconds: 600 });
    expect(await getAccessTicket()).toBe('t-1');

    clearAccessTicketCache();
    requestMock.mockResolvedValueOnce({ ticket: 't-2', expiresInSeconds: 600 });
    expect(await getAccessTicket()).toBe('t-2');

    expect(requestMock).toHaveBeenCalledTimes(2);
  });

  it('未登录时不发请求', async () => {
    useAuthStore.getState().logout();

    await expect(getAccessTicket()).rejects.toThrow('未登录');
    expect(requestMock).not.toHaveBeenCalled();
  });
});
