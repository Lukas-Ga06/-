import { request, getPage, type PageResult } from './client';
import type { Memo } from '../types/memo';

export function getMemos(): Promise<Memo[]> {
  return request<Memo[]>({ url: '/memos' });
}

/**
 * 备忘列表（分页）：GET /api/memos?limit=20&offset=N
 * 响应 data 仍是数组；pagination 缺失时 pagination 为 null（按单页降级）。
 */
export function getMemosPage(
  page: { limit?: number; offset?: number } = {},
): Promise<PageResult<Memo>> {
  return getPage<Memo>('/memos', { limit: page.limit, offset: page.offset });
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
