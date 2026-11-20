// @vitest-environment happy-dom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { act, cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import OfflineBanner from './index';
import { useStatusQueueStore } from '../../store/statusQueueStore';
import type { PendingStatusChange } from '../../lib/statusQueue';

// React 18 的 act 环境标记（happy-dom 下 RTL 不会自动设置）
(globalThis as unknown as { IS_REACT_ACT_ENVIRONMENT: boolean }).IS_REACT_ACT_ENVIRONMENT = true;

// 真实 store 保留（测试要直接摆布队列状态），只关掉自动重放：
// 否则挂载即 flush 会在这批用例里发起真实请求，掩盖「等待用户点重试」的分支
vi.mock('../../store/statusQueueStore', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../../store/statusQueueStore')>();
  return { ...actual, usePendingSync: () => undefined };
});

const api = vi.hoisted(() => ({ updateItemStatus: vi.fn() }));
vi.mock('../../api/trip', () => api);

/** 模拟浏览器在线状态：navigator.onLine 是只读 getter，测试里用 defineProperty 覆盖 */
function setNavigatorOnline(value: boolean) {
  Object.defineProperty(navigator, 'onLine', { configurable: true, get: () => value });
}

function dispatchNetwork(type: 'online' | 'offline') {
  act(() => {
    window.dispatchEvent(new Event(type));
  });
}

function pending(itemId: number, patch: Partial<PendingStatusChange> = {}): PendingStatusChange {
  return {
    requestId: `req-${itemId}`,
    tripId: 1,
    itemId,
    status: 'COMPLETED',
    previousStatus: 'PENDING',
    title: `安排 ${itemId}`,
    queuedAt: Date.now(),
    ...patch,
  };
}

beforeEach(() => {
  setNavigatorOnline(true);
  localStorage.clear();
  api.updateItemStatus.mockReset();
  api.updateItemStatus.mockResolvedValue({});
  useStatusQueueStore.setState({
    entries: [],
    syncing: false,
    lastResults: [],
    hydrated: false,
  });
});

afterEach(() => {
  cleanup();
  // 删掉实例上的覆盖，恢复 happy-dom 的默认实现
  Reflect.deleteProperty(navigator, 'onLine');
});

describe('OfflineBanner 离线状态提示', () => {
  it('在线且没有任何待同步内容时不渲染提示条', () => {
    render(<OfflineBanner />);

    expect(screen.queryByTestId('offline-banner')).toBeNull();
    expect(screen.queryByTestId('sync-banner')).toBeNull();
  });

  it('进入页面时 navigator.onLine=false 直接显示「离线：显示缓存数据」', () => {
    setNavigatorOnline(false);
    render(<OfflineBanner />);

    expect(screen.getByTestId('offline-banner').textContent).toContain('离线：显示缓存数据');
  });

  it('派发 offline 事件后出现，派发 online 事件后自动消失', () => {
    render(<OfflineBanner />);
    expect(screen.queryByTestId('offline-banner')).toBeNull();

    setNavigatorOnline(false);
    dispatchNetwork('offline');
    expect(screen.getByTestId('offline-banner')).toBeTruthy();

    setNavigatorOnline(true);
    dispatchNetwork('online');
    expect(screen.queryByTestId('offline-banner')).toBeNull();
  });

  it('可以手动关闭；恢复在线后再断网会重新提示', () => {
    setNavigatorOnline(false);
    render(<OfflineBanner />);

    fireEvent.click(screen.getByLabelText('关闭离线提示'));
    expect(screen.queryByTestId('offline-banner')).toBeNull();

    setNavigatorOnline(true);
    dispatchNetwork('online');
    setNavigatorOnline(false);
    dispatchNetwork('offline');
    expect(screen.getByTestId('offline-banner')).toBeTruthy();
  });
});

describe('OfflineBanner · 待同步队列（§7.3 第 4 条）', () => {
  it('离线且有待同步条目时，提示条同时给出条数', () => {
    setNavigatorOnline(false);
    useStatusQueueStore.setState({ entries: [pending(1), pending(2)] });

    render(<OfflineBanner />);

    const banner = screen.getByTestId('offline-banner');
    expect(banner.textContent).toContain('离线：显示缓存数据');
    expect(banner.textContent).toContain('2 项待同步');
  });

  it('同步中显示进度文案，不显示失败', () => {
    useStatusQueueStore.setState({ entries: [pending(1)], syncing: true });

    render(<OfflineBanner />);

    const banner = screen.getByTestId('sync-banner');
    expect(banner.textContent).toContain('正在同步 1 项离线记录');
    expect(banner.textContent).not.toContain('失败');
  });

  it('恢复在线但队列还在时给出手动「立即同步」，点击后逐条发送并出队', async () => {
    useStatusQueueStore.setState({ entries: [pending(1)] });

    render(<OfflineBanner />);

    const banner = screen.getByTestId('sync-banner');
    expect(banner.textContent).toContain('1 项待同步');

    fireEvent.click(screen.getByRole('button', { name: '立即同步' }));

    await waitFor(() => expect(api.updateItemStatus).toHaveBeenCalledWith(1, 'COMPLETED'));
    await waitFor(() => expect(useStatusQueueStore.getState().entries).toHaveLength(0));
  });

  it('同步完成后显示一次性回执「N 项离线记录已同步」', async () => {
    useStatusQueueStore.setState({
      lastResults: [
        { requestId: 'req-1', itemId: 1, title: '安排 1', status: 'COMPLETED', ok: true },
        { requestId: 'req-2', itemId: 2, title: '安排 2', status: 'COMPLETED', ok: true },
      ],
    });

    render(<OfflineBanner />);

    expect(screen.getByTestId('sync-banner').textContent).toContain('2 项离线记录已同步');
  });

  it('有失败项时显示失败条数与原因，重试再次同步', async () => {
    useStatusQueueStore.setState({
      entries: [pending(1)],
      lastResults: [
        {
          requestId: 'req-1',
          itemId: 1,
          title: '安排 1',
          status: 'COMPLETED',
          ok: false,
          error: '行程条目不存在',
        },
      ],
    });

    render(<OfflineBanner />);

    const banner = screen.getByTestId('sync-banner');
    expect(banner.textContent).toContain('1 项同步失败');
    expect(banner.textContent).toContain('行程条目不存在');
    expect(banner.getAttribute('role')).toBe('alert');

    fireEvent.click(screen.getByRole('button', { name: '重试' }));
    await waitFor(() => expect(api.updateItemStatus).toHaveBeenCalledTimes(1));
  });
});
