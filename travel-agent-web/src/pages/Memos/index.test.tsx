// @vitest-environment happy-dom
import { afterEach, describe, expect, it, vi } from 'vitest';
import { cleanup, render, screen } from '@testing-library/react';
import { App } from 'antd';
import { MemoryRouter } from 'react-router-dom';
import Memos from './index';
import type { Memo } from '../../types/memo';

const api = vi.hoisted(() => ({
  getMemosPage: vi.fn(),
  addMemo: vi.fn(),
  toggleMemo: vi.fn(),
  deleteMemo: vi.fn(),
}));

vi.mock('../../api/memo', () => ({
  getMemosPage: api.getMemosPage,
  addMemo: api.addMemo,
  toggleMemo: api.toggleMemo,
  deleteMemo: api.deleteMemo,
}));

// 壳层不是本用例的被测对象
vi.mock('../../components/AppShell', () => ({
  default: ({ children }: { children: React.ReactNode }) => <div>{children}</div>,
}));

afterEach(() => {
  cleanup();
  Object.values(api).forEach((fn) => fn.mockReset());
});

/** antd 会在两个汉字之间插入空格（「重 试」），可访问名称匹配要容忍它 */
const RETRY = /重\s*试/;

function memo(patch: Partial<Memo> = {}): Memo {
  return {
    id: 1,
    userId: 1,
    content: '带护照',
    done: false,
    createdAt: '2026-11-27T10:00:00',
    ...patch,
  };
}

function renderPage() {
  return render(
    <MemoryRouter>
      <App>
        <Memos />
      </App>
    </MemoryRouter>,
  );
}

describe('Memos · 状态语义（P1-E）', () => {
  it('加载失败显示错误与重试，不伪装成空备忘', async () => {
    api.getMemosPage.mockRejectedValue(new Error('接口 500'));

    renderPage();

    expect(await screen.findByText('备忘加载失败')).toBeTruthy();
    expect(screen.getByText('接口 500')).toBeTruthy();
    expect(screen.getByRole('button', { name: RETRY })).toBeTruthy();
    expect(screen.queryByText('备忘录是空的')).toBeNull();
  });

  it('真的没有数据时才显示空状态', async () => {
    api.getMemosPage.mockResolvedValue({ items: [], pagination: null });

    renderPage();

    expect(await screen.findByText('备忘录是空的')).toBeTruthy();
    expect(screen.queryByText('备忘加载失败')).toBeNull();
  });

  it('失败后点重试能恢复列表', async () => {
    api.getMemosPage
      .mockRejectedValueOnce(new Error('接口 500'))
      .mockResolvedValueOnce({ items: [memo()], pagination: null });

    renderPage();

    expect(await screen.findByText('备忘加载失败')).toBeTruthy();
    screen.getByRole('button', { name: RETRY }).click();

    expect(await screen.findByText('带护照')).toBeTruthy();
    expect(screen.queryByText('备忘加载失败')).toBeNull();
  });
});
