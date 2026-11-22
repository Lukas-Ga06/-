// @vitest-environment happy-dom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, renderHook, waitFor } from '@testing-library/react';

const { updateItemStatusMock } = vi.hoisted(() => ({ updateItemStatusMock: vi.fn() }));

// 默认发送器动态 import 的 ../api/trip：mock 掉，保证测试绝不发真实请求
vi.mock('../api/trip', () => ({ updateItemStatus: updateItemStatusMock }));

import { STATUS_QUEUE_KEY, writeQueue, type PendingStatusChange } from '../lib/statusQueue';
import { usePendingSync, useStatusQueueStore } from './statusQueueStore';

/** 造一条待同步变更；requestId 默认按 itemId 拼，方便断言顺序 */
function change(patch: Partial<PendingStatusChange> = {}): PendingStatusChange {
  const itemId = patch.itemId ?? 1;
  return {
    requestId: `req-${itemId}`,
    tripId: 1,
    itemId,
    status: 'COMPLETED',
    previousStatus: 'PENDING',
    title: `外滩-${itemId}`,
    queuedAt: 1000,
    ...patch,
  };
}

/** enqueue 的入参（不带 requestId） */
type EnqueueInput = Parameters<ReturnType<typeof useStatusQueueStore.getState>['enqueue']>[0];

function input(itemId: number, patch: Partial<EnqueueInput> = {}): EnqueueInput {
  return {
    tripId: 1,
    itemId,
    status: 'COMPLETED',
    previousStatus: 'PENDING',
    title: `外滩-${itemId}`,
    ...patch,
  };
}

/** 直接读 localStorage 里的队列（null 表示键不存在） */
function stored(): PendingStatusChange[] | null {
  const raw = localStorage.getItem(STATUS_QUEUE_KEY);
  return raw === null ? null : (JSON.parse(raw) as PendingStatusChange[]);
}

/** 预置 store 状态（模拟「已经 hydrate 过」的运行时） */
function seedStore(entries: PendingStatusChange[]): void {
  useStatusQueueStore.setState({ entries, hydrated: true });
}

/** axios 风格错误：带 code（网络层没拿到响应） */
function networkError(message = '网络错误'): Error & { code: string } {
  return Object.assign(new Error(message), { code: 'ERR_NETWORK' });
}

/** ApiError 风格错误：服务端明确拒绝 */
function serverError(status: number, message = '服务端拒绝'): Error & { status: number } {
  return Object.assign(new Error(message), { status });
}

beforeEach(() => {
  localStorage.clear();
  updateItemStatusMock.mockReset();
  // 逐用例重置 store 状态（动作方法保留）
  useStatusQueueStore.setState({ entries: [], syncing: false, lastResults: [], hydrated: false });
});

afterEach(() => {
  cleanup();
  vi.unstubAllGlobals();
  localStorage.clear();
});

describe('hydrate · 从 localStorage 读入', () => {
  it('读入持久化队列并标记 hydrated', () => {
    writeQueue([change({ itemId: 1, requestId: 'stored-1' })]);

    expect(useStatusQueueStore.getState().hydrated).toBe(false);
    useStatusQueueStore.getState().hydrate();

    expect(useStatusQueueStore.getState().hydrated).toBe(true);
    expect(useStatusQueueStore.getState().entries.map((entry) => entry.requestId)).toEqual([
      'stored-1',
    ]);
  });

  it('幂等：重复调用不会重新读盘，也不覆盖内存里的最新状态', () => {
    writeQueue([change({ itemId: 1, requestId: 'stored-1' })]);
    useStatusQueueStore.getState().hydrate();

    useStatusQueueStore.setState({ entries: [change({ itemId: 2, requestId: 'mem-2' })] });
    writeQueue([change({ itemId: 3, requestId: 'stored-3' })]);

    useStatusQueueStore.getState().hydrate();

    expect(useStatusQueueStore.getState().entries.map((entry) => entry.requestId)).toEqual([
      'mem-2',
    ]);
  });

  it('hydrate 之前已经入队的条目不会被读盘结果抹掉，并写回存储', () => {
    writeQueue([change({ itemId: 1, requestId: 'stored-1' })]);
    // 首屏还没挂载 hook 就产生了变更：内存里有、存储里没有
    useStatusQueueStore.setState({ entries: [change({ itemId: 2, requestId: 'mem-2' })] });

    useStatusQueueStore.getState().hydrate();

    expect(useStatusQueueStore.getState().entries.map((entry) => entry.requestId)).toEqual([
      'stored-1',
      'mem-2',
    ]);
    expect(stored()?.map((entry) => entry.requestId)).toEqual(['stored-1', 'mem-2']);
  });

  it('存储损坏时读出空队列，仍标记 hydrated（不抛错、不卡住后续入队）', () => {
    localStorage.setItem(STATUS_QUEUE_KEY, '{broken');

    useStatusQueueStore.getState().hydrate();

    expect(useStatusQueueStore.getState().entries).toEqual([]);
    expect(useStatusQueueStore.getState().hydrated).toBe(true);
  });
});

describe('enqueue · 入队并立即持久化', () => {
  it('返回生成的变更，同时写进 state 与 localStorage', () => {
    const entry = useStatusQueueStore.getState().enqueue(input(1));

    expect(entry.requestId).toMatch(
      /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i,
    );
    expect(useStatusQueueStore.getState().entries).toEqual([entry]);
    expect(stored()).toEqual([entry]);
  });

  it('同一 itemId 只留最后一条（LWW），存储同步收敛', () => {
    useStatusQueueStore.getState().enqueue(input(1, { status: 'IN_PROGRESS' }));
    const latest = useStatusQueueStore.getState().enqueue(input(1, { status: 'COMPLETED' }));

    expect(useStatusQueueStore.getState().entries).toHaveLength(1);
    expect(useStatusQueueStore.getState().entries[0].status).toBe('COMPLETED');
    expect(stored()).toEqual([latest]);
  });

  it('可注入 requestId（重试复用同一个幂等键）', () => {
    const entry = useStatusQueueStore.getState().enqueue({ ...input(1), requestId: 'stable-id' });

    expect(entry.requestId).toBe('stable-id');
    expect(stored()?.[0].requestId).toBe('stable-id');
  });

  it('入队前未 hydrate 也不会清掉存储里的旧队列', () => {
    writeQueue([change({ itemId: 1, requestId: 'stored-1' })]);

    const added = useStatusQueueStore.getState().enqueue(input(2));

    expect(stored()?.map((entry) => entry.requestId)).toEqual(['stored-1', added.requestId]);
  });
});

describe('drop / dropByItem / clear / clearResults', () => {
  it('drop 按 requestId 撤销单条并持久化', () => {
    const first = useStatusQueueStore.getState().enqueue(input(1));
    const second = useStatusQueueStore.getState().enqueue(input(2));

    useStatusQueueStore.getState().drop(first.requestId);

    expect(useStatusQueueStore.getState().entries).toEqual([second]);
    expect(stored()).toEqual([second]);
  });

  it('dropByItem 撤销某个条目的待同步变更', () => {
    useStatusQueueStore.getState().enqueue(input(1));
    const second = useStatusQueueStore.getState().enqueue(input(2));

    useStatusQueueStore.getState().dropByItem(1);

    expect(useStatusQueueStore.getState().entries).toEqual([second]);
    expect(stored()).toEqual([second]);
  });

  it('clear 清空队列并落盘空数组', () => {
    useStatusQueueStore.getState().enqueue(input(1));
    useStatusQueueStore.getState().enqueue(input(2));

    useStatusQueueStore.getState().clear();

    expect(useStatusQueueStore.getState().entries).toEqual([]);
    expect(stored()).toEqual([]);
  });

  it('clearResults 只清逐项结果，不动队列', () => {
    const entry = useStatusQueueStore.getState().enqueue(input(1));
    useStatusQueueStore.setState({
      lastResults: [{ ...entry, ok: true }],
    });

    useStatusQueueStore.getState().clearResults();

    expect(useStatusQueueStore.getState().lastResults).toEqual([]);
    expect(useStatusQueueStore.getState().entries).toEqual([entry]);
  });
});

describe('flush · 全部成功', () => {
  it('顺序发送、逐项产出结果、成功即出队并持久化', async () => {
    seedStore([change({ itemId: 1, requestId: 'a' }), change({ itemId: 2, requestId: 'b' })]);
    const send = vi.fn().mockResolvedValue(undefined);

    const results = await useStatusQueueStore.getState().flush(send);

    expect(results).toEqual([
      { requestId: 'a', itemId: 1, title: '外滩-1', status: 'COMPLETED', ok: true },
      { requestId: 'b', itemId: 2, title: '外滩-2', status: 'COMPLETED', ok: true },
    ]);
    expect(useStatusQueueStore.getState().entries).toEqual([]);
    expect(useStatusQueueStore.getState().syncing).toBe(false);
    expect(useStatusQueueStore.getState().lastResults).toEqual(results);
    expect(stored()).toEqual([]);
    expect(send).toHaveBeenCalledTimes(2);
  });

  it('严格顺序：前一条结束后才发下一条', async () => {
    seedStore([change({ itemId: 1, requestId: 'a' }), change({ itemId: 2, requestId: 'b' })]);
    const order: string[] = [];
    let releaseFirst: () => void = () => undefined;
    const send = vi.fn((entry: PendingStatusChange) => {
      order.push(`start:${entry.requestId}`);
      if (entry.requestId === 'a') {
        return new Promise<void>((resolve) => {
          releaseFirst = () => {
            order.push('end:a');
            resolve();
          };
        });
      }
      order.push(`end:${entry.requestId}`);
      return Promise.resolve();
    });

    const flushing = useStatusQueueStore.getState().flush(send);
    expect(send).toHaveBeenCalledTimes(1);
    releaseFirst();
    await flushing;

    expect(order).toEqual(['start:a', 'end:a', 'start:b', 'end:b']);
    expect(useStatusQueueStore.getState().entries).toEqual([]);
  });

  it('空队列直接返回 []，并清掉上一次的逐项结果', async () => {
    useStatusQueueStore.setState({
      lastResults: [{ requestId: 'old', itemId: 1, title: '外滩', status: 'COMPLETED', ok: true }],
    });
    const send = vi.fn();

    const results = await useStatusQueueStore.getState().flush(send);

    expect(results).toEqual([]);
    expect(send).not.toHaveBeenCalled();
    expect(useStatusQueueStore.getState().lastResults).toEqual([]);
  });

  it('发送期间被撤销的条目不发送、也不出现在结果里', async () => {
    seedStore([change({ itemId: 1, requestId: 'a' }), change({ itemId: 2, requestId: 'b' })]);
    const resolvers: Array<() => void> = [];
    const send = vi.fn(
      () =>
        new Promise<void>((resolve) => {
          resolvers.push(resolve);
        }),
    );

    const flushing = useStatusQueueStore.getState().flush(send);
    expect(send).toHaveBeenCalledTimes(1);
    // 第二条在第一条发送期间被用户撤销
    useStatusQueueStore.getState().drop('b');
    resolvers[0]();
    const results = await flushing;

    expect(results.map((result) => result.requestId)).toEqual(['a']);
    expect(send).toHaveBeenCalledTimes(1);
    expect(useStatusQueueStore.getState().entries).toEqual([]);
  });
});

describe('flush · 失败处理与错误分类', () => {
  it('409 冲突直接出队（前提已失效，重试无意义），并说明原因', async () => {
    seedStore([change({ itemId: 1, requestId: 'a' }), change({ itemId: 2, requestId: 'b' })]);
    const send = vi
      .fn()
      .mockRejectedValueOnce(serverError(409, '数据已被其他操作修改，请刷新后重试'))
      .mockResolvedValueOnce(undefined);

    const results = await useStatusQueueStore.getState().flush(send);

    expect(results[0]).toMatchObject({ requestId: 'a', ok: false });
    expect(results[0].error).toContain('此行程已在其他位置更新');
    expect(results[1]).toMatchObject({ requestId: 'b', ok: true });
    // 冲突条目不会被留下无限重试；后续条目照常同步
    expect(useStatusQueueStore.getState().entries).toEqual([]);
    expect(stored()).toEqual([]);
  });

  it('业务拒绝（HTTP 状态码合法）保留该条并记 lastError，继续处理后一条', async () => {
    seedStore([change({ itemId: 1, requestId: 'a' }), change({ itemId: 2, requestId: 'b' })]);
    const send = vi
      .fn()
      .mockRejectedValueOnce(serverError(400, '行程项已被删除'))
      .mockResolvedValueOnce(undefined);

    const results = await useStatusQueueStore.getState().flush(send);

    expect(results).toEqual([
      {
        requestId: 'a',
        itemId: 1,
        title: '外滩-1',
        status: 'COMPLETED',
        ok: false,
        error: '行程项已被删除',
      },
      { requestId: 'b', itemId: 2, title: '外滩-2', status: 'COMPLETED', ok: true },
    ]);
    // 失败条目留在队列里（下次 flush 重试），lastError 落盘
    expect(useStatusQueueStore.getState().entries).toEqual([
      { ...change({ itemId: 1, requestId: 'a' }), lastError: '行程项已被删除' },
    ]);
    expect(stored()).toEqual([
      { ...change({ itemId: 1, requestId: 'a' }), lastError: '行程项已被删除' },
    ]);
    expect(send).toHaveBeenCalledTimes(2);
  });

  it('axios 原始错误的 response.status 也算服务端明确回应（继续处理后续条目）', async () => {
    seedStore([change({ itemId: 1, requestId: 'a' }), change({ itemId: 2, requestId: 'b' })]);
    const send = vi
      .fn()
      // 用 422 而不是 409：409 现在是「冲突→出队」的专门分支（见上一条用例）
      .mockRejectedValueOnce(Object.assign(new Error('无法处理'), { response: { status: 422 } }))
      .mockResolvedValueOnce(undefined);

    const results = await useStatusQueueStore.getState().flush(send);

    expect(results.map((result) => result.ok)).toEqual([false, true]);
    expect(useStatusQueueStore.getState().entries.map((entry) => entry.itemId)).toEqual([1]);
  });

  it('错误对象上的 response.status 不是数字（脏错误）按网络类处理', async () => {
    seedStore([change({ itemId: 1, requestId: 'a' }), change({ itemId: 2, requestId: 'b' })]);
    const send = vi
      .fn()
      .mockRejectedValue(Object.assign(new Error('响应异常'), { response: { status: '409' } }));

    const results = await useStatusQueueStore.getState().flush(send);

    expect(results).toHaveLength(1);
    expect(send).toHaveBeenCalledTimes(1);
    expect(useStatusQueueStore.getState().entries).toHaveLength(2);
  });

  it('网络类错误（code=ERR_NETWORK）中止后续：剩余条目原样保留且不产出结果', async () => {
    seedStore([
      change({ itemId: 1, requestId: 'a' }),
      change({ itemId: 2, requestId: 'b' }),
      change({ itemId: 3, requestId: 'c' }),
    ]);
    const send = vi.fn().mockRejectedValue(networkError('网络连接已断开'));

    const results = await useStatusQueueStore.getState().flush(send);

    expect(results).toEqual([
      {
        requestId: 'a',
        itemId: 1,
        title: '外滩-1',
        status: 'COMPLETED',
        ok: false,
        error: '网络连接已断开',
      },
    ]);
    expect(send).toHaveBeenCalledTimes(1);
    const entries = useStatusQueueStore.getState().entries;
    expect(entries.map((entry) => entry.itemId)).toEqual([1, 2, 3]);
    expect(entries[0].lastError).toBe('网络连接已断开');
    // 后面两条没被碰过：不标记失败，等下次在线再同步
    expect(entries[1].lastError).toBeUndefined();
    expect(entries[2].lastError).toBeUndefined();
    expect(useStatusQueueStore.getState().syncing).toBe(false);
  });

  it('没有状态码的普通 Error 按网络不可用处理（中止后续）', async () => {
    seedStore([change({ itemId: 1, requestId: 'a' }), change({ itemId: 2, requestId: 'b' })]);
    const send = vi.fn().mockRejectedValue(new Error('Failed to fetch'));

    const results = await useStatusQueueStore.getState().flush(send);

    expect(results.map((result) => result.error)).toEqual(['Failed to fetch']);
    expect(send).toHaveBeenCalledTimes(1);
    expect(useStatusQueueStore.getState().entries).toHaveLength(2);
  });

  it.each([0, 600, Number.NaN])('状态码不是 1-599 的整数（%s）按网络类错误中止', async (status) => {
    seedStore([change({ itemId: 1, requestId: 'a' }), change({ itemId: 2, requestId: 'b' })]);
    const send = vi.fn().mockRejectedValue(Object.assign(new Error('异常状态码'), { status }));

    const results = await useStatusQueueStore.getState().flush(send);

    expect(results).toHaveLength(1);
    expect(results[0].ok).toBe(false);
    expect(send).toHaveBeenCalledTimes(1);
    expect(useStatusQueueStore.getState().entries).toHaveLength(2);
  });

  it('小写 code 也识别为网络错误', async () => {
    seedStore([change({ itemId: 1, requestId: 'a' }), change({ itemId: 2, requestId: 'b' })]);
    const send = vi
      .fn()
      .mockRejectedValue(Object.assign(new Error('超时'), { code: 'econnaborted' }));

    const results = await useStatusQueueStore.getState().flush(send);

    expect(results).toHaveLength(1);
    expect(send).toHaveBeenCalledTimes(1);
  });

  it('发送器抛出字符串 / undefined 时也能给出可读原因且不抛错', async () => {
    seedStore([change({ itemId: 1, requestId: 'a' })]);
    const send = vi.fn().mockRejectedValueOnce('网络中断').mockRejectedValueOnce(undefined);

    const first = await useStatusQueueStore.getState().flush(send);
    expect(first[0].error).toBe('网络中断');

    const second = await useStatusQueueStore.getState().flush(send);
    expect(second[0].error).toBe('同步失败');
  });
});

describe('flush · 并发保护', () => {
  it('syncing 为 true 时直接返回 []，不发送、不改动队列', async () => {
    seedStore([change({ itemId: 1, requestId: 'a' })]);
    useStatusQueueStore.setState({ syncing: true });
    const send = vi.fn();

    const results = await useStatusQueueStore.getState().flush(send);

    expect(results).toEqual([]);
    expect(send).not.toHaveBeenCalled();
    expect(useStatusQueueStore.getState().entries).toHaveLength(1);
    expect(useStatusQueueStore.getState().syncing).toBe(true);
  });

  it('两次并发 flush：第二次直接被挡回，发送器只被调用一次', async () => {
    seedStore([change({ itemId: 1, requestId: 'a' })]);
    let release: () => void = () => undefined;
    const send = vi.fn(
      () =>
        new Promise<void>((resolve) => {
          release = resolve;
        }),
    );

    const first = useStatusQueueStore.getState().flush(send);
    const second = await useStatusQueueStore.getState().flush(send);
    release();
    await first;

    expect(second).toEqual([]);
    expect(send).toHaveBeenCalledTimes(1);
  });
});

describe('默认发送器（未传 send）', () => {
  it('调用 api/trip 的 updateItemStatus(itemId, 目标状态)', async () => {
    updateItemStatusMock.mockResolvedValue({ id: 7, status: 'IN_PROGRESS' });
    seedStore([change({ itemId: 7, requestId: 'd1', status: 'IN_PROGRESS' })]);

    const results = await useStatusQueueStore.getState().flush();

    expect(updateItemStatusMock).toHaveBeenCalledTimes(1);
    expect(updateItemStatusMock).toHaveBeenCalledWith(7, 'IN_PROGRESS');
    expect(results).toEqual([
      { requestId: 'd1', itemId: 7, title: '外滩-7', status: 'IN_PROGRESS', ok: true },
    ]);
    expect(useStatusQueueStore.getState().entries).toEqual([]);
  });

  it('默认发送器失败时结果带上原因、条目保留', async () => {
    updateItemStatusMock.mockRejectedValue(serverError(404, '行程项不存在'));
    seedStore([change({ itemId: 7, requestId: 'd1' })]);

    const results = await useStatusQueueStore.getState().flush();

    expect(results).toEqual([
      {
        requestId: 'd1',
        itemId: 7,
        title: '外滩-7',
        status: 'COMPLETED',
        ok: false,
        error: '行程项不存在',
      },
    ]);
    expect(useStatusQueueStore.getState().entries[0].lastError).toBe('行程项不存在');
  });

  it('默认发送器抛网络错误时中止（不会继续打接口）', async () => {
    updateItemStatusMock.mockRejectedValue(networkError('请求超时'));
    seedStore([change({ itemId: 1, requestId: 'a' }), change({ itemId: 2, requestId: 'b' })]);

    await useStatusQueueStore.getState().flush();

    expect(updateItemStatusMock).toHaveBeenCalledTimes(1);
    expect(useStatusQueueStore.getState().entries).toHaveLength(2);
  });
});

describe('usePendingSync · 自动同步', () => {
  it('挂载即 hydrate + flush（在线时）', async () => {
    writeQueue([change({ itemId: 1, requestId: 'boot-1' })]);
    const send = vi.fn().mockResolvedValue(undefined);

    const { unmount } = renderHook(() => usePendingSync({ send }));

    await waitFor(() => expect(send).toHaveBeenCalledTimes(1));
    await waitFor(() => expect(useStatusQueueStore.getState().entries).toEqual([]));
    expect(send.mock.calls[0][0]).toMatchObject({ requestId: 'boot-1' });
    expect(useStatusQueueStore.getState().hydrated).toBe(true);
    unmount();
  });

  it('window 的 online 事件触发 flush', async () => {
    const send = vi.fn().mockResolvedValue(undefined);
    const { unmount } = renderHook(() => usePendingSync({ send }));

    await waitFor(() => expect(useStatusQueueStore.getState().hydrated).toBe(true));
    // 挂载时队列为空，不发请求
    expect(send).not.toHaveBeenCalled();

    useStatusQueueStore.getState().enqueue({
      tripId: 1,
      itemId: 4,
      status: 'IN_PROGRESS',
      previousStatus: 'PENDING',
      title: '豫园',
    });
    window.dispatchEvent(new Event('online'));

    await waitFor(() => expect(send).toHaveBeenCalledTimes(1));
    await waitFor(() => expect(useStatusQueueStore.getState().entries).toEqual([]));
    unmount();
  });

  it('auto:false 完全关闭自动行为（不 hydrate、不 flush、不监听）', () => {
    writeQueue([change({ itemId: 1, requestId: 'boot-1' })]);
    const send = vi.fn();

    const { unmount } = renderHook(() => usePendingSync({ send, auto: false }));
    window.dispatchEvent(new Event('online'));

    expect(useStatusQueueStore.getState().hydrated).toBe(false);
    expect(useStatusQueueStore.getState().entries).toEqual([]);
    expect(send).not.toHaveBeenCalled();
    unmount();
  });

  it('卸载后移除 online 监听', async () => {
    const send = vi.fn().mockResolvedValue(undefined);
    const { unmount } = renderHook(() => usePendingSync({ send }));

    await waitFor(() => expect(useStatusQueueStore.getState().hydrated).toBe(true));
    unmount();
    useStatusQueueStore.setState({ entries: [change({ itemId: 1, requestId: 'after-unmount' })] });
    window.dispatchEvent(new Event('online'));
    await Promise.resolve();

    expect(send).not.toHaveBeenCalled();
    expect(useStatusQueueStore.getState().entries).toHaveLength(1);
  });

  it('拿不到 navigator（SSR/受限环境）时按在线处理，挂载仍会补一次同步', async () => {
    writeQueue([change({ itemId: 1, requestId: 'n-1' })]);
    const send = vi.fn().mockResolvedValue(undefined);
    vi.stubGlobal('navigator', undefined);

    const { unmount } = renderHook(() => usePendingSync({ send }));

    await waitFor(() => expect(send).toHaveBeenCalledTimes(1));
    await waitFor(() => expect(useStatusQueueStore.getState().entries).toEqual([]));
    unmount();
  });
});
