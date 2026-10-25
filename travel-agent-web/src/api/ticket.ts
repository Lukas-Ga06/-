import { useEffect, useState } from 'react';
import { request } from './client';
import { useAuthStore } from '../store/authStore';

export interface AccessTicket {
  ticket: string;
  expiresInSeconds: number;
}

/** 提前 30 秒续期，避免踩在过期边界上 */
const REFRESH_MARGIN_MS = 30_000;

let cached: { authKey: string; ticket: string; expiresAt: number } | null = null;
let inflight: Promise<string> | null = null;

export function clearAccessTicketCache(): void {
  cached = null;
  inflight = null;
}

/** 登录态标识：用户 + 内存 token（token 刷新即失，重启浏览器后由 /me 恢复 user） */
function authKey(): string | null {
  const { user, token } = useAuthStore.getState();
  return user ? `${user.id}|${token ?? ''}` : null;
}

/**
 * 取当前登录态可用的短期只读票据（服务端默认 10 分钟）。
 *
 * SSE 与 <img> 无法自定义请求头，只能把凭证放进查询串；
 * 放登录 JWT 会进访问日志与浏览器历史，因此改为短期票据。
 * 同一登录态复用缓存，并合并并发请求 —— 否则照片墙每张图都会签一次票据。
 */
export async function getAccessTicket(): Promise<string> {
  const key = authKey();
  if (!key) {
    throw new Error('未登录，无法获取访问票据');
  }
  if (cached && cached.authKey === key && cached.expiresAt - REFRESH_MARGIN_MS > Date.now()) {
    return cached.ticket;
  }
  if (inflight) {
    return inflight;
  }
  inflight = request<AccessTicket>({ url: '/access-ticket', method: 'post' })
    .then((res) => {
      cached = { authKey: key, ticket: res.ticket, expiresAt: Date.now() + res.expiresInSeconds * 1000 };
      return res.ticket;
    })
    .finally(() => {
      inflight = null;
    });
  return inflight;
}

/** 组件用：随登录态自动获取票据；未就绪时返回 null */
export function useAccessTicket(): string | null {
  const user = useAuthStore((s) => s.user);
  const [ticket, setTicket] = useState<string | null>(null);

  useEffect(() => {
    let alive = true;
    if (!user) {
      setTicket(null);
      return;
    }
    getAccessTicket()
      .then((value) => {
        if (alive) setTicket(value);
      })
      .catch(() => {
        if (alive) setTicket(null);
      });
    return () => {
      alive = false;
    };
  }, [user]);

  return ticket;
}
