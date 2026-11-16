import { beforeEach, describe, expect, it, vi } from 'vitest';

const getPageMock = vi.hoisted(() => vi.fn());
const requestMock = vi.hoisted(() => vi.fn());

vi.mock('./client', () => ({
  getPage: getPageMock,
  request: requestMock,
  DEFAULT_PAGE_SIZE: 20,
}));

import { getExpenses, getExpensesPage } from './expense';
import { getMemosPage } from './memo';

describe('列表分页 API（batch9 契约）', () => {
  beforeEach(() => {
    getPageMock.mockReset();
    requestMock.mockReset();
  });

  it('账单列表按 limit/offset 请求 /trips/{id}/expenses', async () => {
    getPageMock.mockResolvedValue({ items: [], pagination: null });

    await getExpensesPage(7, { limit: 20, offset: 40 });

    expect(getPageMock).toHaveBeenCalledWith('/trips/7/expenses', { limit: 20, offset: 40 });
  });

  it('备忘列表按 limit/offset 请求 /memos', async () => {
    getPageMock.mockResolvedValue({ items: [], pagination: null });

    await getMemosPage({ limit: 20, offset: 0 });

    expect(getPageMock).toHaveBeenCalledWith('/memos', { limit: 20, offset: 0 });
  });

  it('没传 offset 时保持 undefined，由 client.compactParams 丢弃（不拼 offset=undefined）', async () => {
    getPageMock.mockResolvedValue({ items: [], pagination: null });

    await getExpensesPage(7, { limit: 20 });

    expect(getPageMock).toHaveBeenCalledWith('/trips/7/expenses', { limit: 20, offset: undefined });
  });

  it('后端不返回 pagination 时把 null 原样透出，页面据此降级为单页', async () => {
    getPageMock.mockResolvedValue({ items: [{ id: 1 }], pagination: null });

    const page = await getExpensesPage(7, { limit: 20, offset: 0 });

    expect(page.pagination).toBeNull();
    expect(page.items).toEqual([{ id: 1 }]);
  });

  it('旧的整表读取函数保持兼容（仍直接返回数组）', async () => {
    requestMock.mockResolvedValue([{ id: 1 }]);

    expect(await getExpenses(7)).toEqual([{ id: 1 }]);
    expect(requestMock).toHaveBeenCalledWith({ url: '/trips/7/expenses' });
  });
});
