import { request } from './client';
import type { Memo } from '../types/memo';

export function getMemos(): Promise<Memo[]> {
  return request<Memo[]>({ url: '/memos' });
}

export function addMemo(content: string): Promise<Memo> {
  return request<Memo>({ url: '/memos', method: 'post', data: { content } });
}

export function toggleMemo(id: number): Promise<Memo> {
  return request<Memo>({ url: `/memos/${id}/toggle`, method: 'post' });
}

export function deleteMemo(id: number): Promise<void> {
  return request<void>({ url: `/memos/${id}`, method: 'delete' });
}

/** 待办数量（侧栏「备忘」角标用）；后端按当前登录用户统计 */
export function getMemoOpenCount(): Promise<number> {
  return request<{ open: number }>({ url: '/memos/open-count' }).then((d) => d.open);
}
