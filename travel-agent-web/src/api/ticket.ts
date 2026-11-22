import { useEffect, useState } from 'react';
import { request } from './client';
import { useAuthStore } from '../store/authStore';

/** 票据用途：服务端按 aud 强校验，用错路径一律 401（票据之间不能互相挪用） */
export type AccessTicketPurpose = 'agent-stream' | 'media-read';

/** 不传时与服务端默认值一致：SSE 流 */
export const DEFAULT_TICKET_PURPOSE: AccessTicketPurpose = 'agent-stream';

export interface AccessTicket {
  ticket: string;
  expiresInSeconds: number;
  /** 服务端回显的用途（旧后端可能没有该字段） */
  purpose?: AccessTicketPurpose;
}

/** 提前 30 秒续期，避免踩在过期边界上 */
const REFRESH_MARGIN_MS = 30_000;

interface CachedTicket {
  ticket: string;
  expiresAt: number;
}

/** 缓存按 `authKey|purpose` 分桶：照片（media-read）与 SSE（agent-stream）各存一份，互不覆盖 */
const cached = new Map<string, CachedTicket>();
/** 在途请求同样按 `authKey|purpose` 分桶合流，避免两个用途互相串用票据 */
const inflight = new Map<string, Promise<string>>();

export function clearAccessTicketCache(): void {
  cached.clear();
  inflight.clear();
}

/** 登录态标识：用户 + 内存 token（token 刷新即失，重启浏览器后由 /me 恢复 user） */
function authKey(): string | null {
  const { user, token } = useAuthStore.getState();
  return user ? `${user.id}|${token ?? ''}` : null;
}

/** 缓存分桶键：同一登录态下按用途隔离 */
function cacheKey(auth: string, purpose: AccessTicketPurpose): string {
  return `${auth}|${purpose}`;
}

/**
 * 取当前登录态可用的短期只读票据（服务端默认 300 秒 = 5 分钟，
 * 可通过 `travel.jwt.ticket-ttl-seconds` 调整）。
 *
 * SSE 与 <img> 无法自定义请求头，只能把凭证放进查询串；
 * 放登录 JWT 会进访问日志与浏览器历史，因此改为短期票据。
 * 票据按用途（agent-stream / media-read）分别签发与缓存，
 * 同一登录态同一用途复用缓存，并合并并发请求 —— 否则照片墙每张图都会签一次票据。
 */
export async function getAccessTicket(
  purpose: AccessTicketPurpose = DEFAULT_TICKET_PURPOSE,
): Promise<string> {
  const auth = authKey();
  if (!auth) {
    throw new Error('未登录，无法获取访问票据');
  }
  const key = cacheKey(auth, purpose);
  const hit = cached.get(key);
  if (hit && hit.expiresAt - REFRESH_MARGIN_MS > Date.now()) {
    return hit.ticket;
  }
  const pending = inflight.get(key);
  if (pending) {
    return pending;
  }
  const issued = request<AccessTicket>({
    url: `/access-ticket?purpose=${encodeURIComponent(purpose)}`,
    method: 'post',
  })
    .then((res) => {
      cached.set(key, { ticket: res.ticket, expiresAt: Date.now() + res.expiresInSeconds * 1000 });
      return res.ticket;
    })
    .finally(() => {
      inflight.delete(key);
    });
  inflight.set(key, issued);
  return issued;
}

/** 组件用：随登录态/用途自动获取票据；未就绪时返回 null */
export function useAccessTicket(
  purpose: AccessTicketPurpose = DEFAULT_TICKET_PURPOSE,
): string | null {
  const user = useAuthStore((s) => s.user);
  const [ticket, setTicket] = useState<string | null>(null);

  useEffect(() => {
    let alive = true;
    if (!user) {
      setTicket(null);
      return;
    }
    getAccessTicket(purpose)
      .then((value) => {
        if (alive) setTicket(value);
      })
      .catch(() => {
        if (alive) setTicket(null);
      });
    return () => {
      alive = false;
    };
  }, [user, purpose]);

  return ticket;
}
