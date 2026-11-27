// @vitest-environment happy-dom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { act, cleanup, render, waitFor } from '@testing-library/react';
import { MemoryRouter, useLocation } from 'react-router-dom';
import Assistant from './index';

/** 页面壳层与真实 store 都不是本用例的被测对象：只验证「q 参数只消费一次」这一条逻辑 */
vi.mock('../../components/AppShell', () => ({
  default: ({ children }: { children: React.ReactNode }) => <div>{children}</div>,
}));

vi.mock('../../components/PlanDiff', () => ({ default: () => null }));

vi.mock('../../components/ChatPanel', () => ({
  default: () => <div data-testid="chat-panel" />,
}));

const agentState = vi.hoisted(() => ({
  send: vi.fn(),
  reset: vi.fn(),
  confirm: vi.fn(),
  cancel: vi.fn(),
  restorePending: vi.fn(),
}));

const api = vi.hoisted(() => ({ getPendingPlan: vi.fn() }));

vi.mock('../../api/agent', () => ({ getPendingPlan: api.getPendingPlan }));

vi.mock('../../store/agentStore', () => {
  const state = {
    pendingConfirm: null,
    streaming: false,
    send: agentState.send,
    reset: agentState.reset,
    confirm: agentState.confirm,
    cancel: agentState.cancel,
    restorePending: agentState.restorePending,
  };
  const useAgentStore = (selector: (s: typeof state) => unknown) => selector(state);
  return { useAgentStore: Object.assign(useAgentStore, { getState: () => state }) };
});

/** 当前 URL：由探针节点承载，避免在组件渲染期写模块级变量（react-hooks/immutability） */
const readSearch = () =>
  document.querySelector<HTMLElement>('[data-location-probe]')?.dataset.search ?? '';

function LocationProbe() {
  const { search } = useLocation();
  return <div data-location-probe data-search={search} />;
}

function renderPage(entry: string) {
  return render(
    <MemoryRouter initialEntries={[entry]}>
      <LocationProbe />
      <Assistant />
    </MemoryRouter>,
  );
}

beforeEach(() => {
  Object.values(agentState).forEach((fn) => fn.mockClear());
  api.getPendingPlan.mockReset();
});

afterEach(() => {
  cleanup();
});

describe('Assistant · q 参数自动发送（P0-01）', () => {
  it('带 q 进入只发送一次，并把 q 从 URL 上消费掉', async () => {
    renderPage(
      '/assistant?q=%E8%AE%B0%E4%B8%80%E4%B8%8B%E6%98%8E%E5%A4%A9%E5%8A%9E%E7%AD%BE%E8%AF%81',
    );

    await waitFor(() =>
      expect(agentState.send).toHaveBeenCalledWith({
        message: '记一下明天办签证',
        tripId: null,
      }),
    );

    // 发送前先消费参数：刷新/前进后退都不会再触发一次写入类命令
    await waitFor(() => expect(readSearch()).not.toContain('q='));
    expect(agentState.send).toHaveBeenCalledTimes(1);
  });

  it('重复渲染（参数已消费）不会再次发送', async () => {
    renderPage('/assistant?q=hello');

    await waitFor(() => expect(agentState.send).toHaveBeenCalledTimes(1));
    // 再等一轮 effect：URL 变了会重新进 effect，但 startedRef 必须拦住第二次发送
    await act(async () => {
      await new Promise((r) => setTimeout(r, 200));
    });

    expect(agentState.send).toHaveBeenCalledTimes(1);
  });

  it('tripId 合法时透传，非法时不传', async () => {
    renderPage('/assistant?q=hi&tripId=7');
    await waitFor(() => expect(agentState.send).toHaveBeenCalledWith({ message: 'hi', tripId: 7 }));

    cleanup();
    agentState.send.mockClear();
    renderPage('/assistant?q=hi&tripId=abc');
    await waitFor(() =>
      expect(agentState.send).toHaveBeenCalledWith({ message: 'hi', tripId: null }),
    );
  });

  it('定时器未触发就离页：不再发出请求', async () => {
    const view = renderPage('/assistant?q=hello');
    view.unmount();

    await act(async () => {
      await new Promise((r) => setTimeout(r, 250));
    });

    expect(agentState.send).not.toHaveBeenCalled();
  });

  it('没有 q 时不自动发送', async () => {
    renderPage('/assistant');
    await act(async () => {
      await new Promise((r) => setTimeout(r, 200));
    });
    expect(agentState.send).not.toHaveBeenCalled();
  });
});
