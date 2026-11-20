import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { ItineraryStatus } from '../types/trip';
import {
  STATUS_QUEUE_KEY,
  createPendingChange,
  dropByItem,
  dropEntry,
  enqueueChange,
  overlayStatuses,
  parseStoredQueue,
  pendingFor,
  pendingIds,
  readQueue,
  serializeQueue,
  writeQueue,
  type PendingStatusChange,
} from './statusQueue';

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
    queuedAt: 1000 + itemId,
    ...patch,
  };
}

/** 一个所有方法都抛错的 Storage，模拟隐私模式 / 配额满 */
function blockedStorage(method: 'getItem' | 'setItem'): Storage {
  const storage: Storage = {
    length: 0,
    clear: () => undefined,
    key: () => null,
    getItem: () => null,
    removeItem: () => undefined,
    setItem: () => undefined,
  };
  storage[method] = () => {
    throw new Error(`storage blocked: ${method}`);
  };
  return storage;
}

beforeEach(() => {
  // 每个用例从干净存储开始：readQueue() 走的是 setup.ts 提供的真实内存 localStorage
  localStorage.clear();
});

afterEach(() => {
  vi.unstubAllGlobals();
});

describe('createPendingChange · 幂等标识与入队时间', () => {
  it('用 crypto.randomUUID 生成 UUID 格式的 requestId，并原样带上来回字段', () => {
    const entry = createPendingChange({
      tripId: 7,
      itemId: 42,
      status: 'COMPLETED',
      previousStatus: 'IN_PROGRESS',
      title: '外滩',
      now: 1700000000000,
    });

    expect(entry.requestId).toMatch(
      /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i,
    );
    expect(entry).toEqual({
      requestId: entry.requestId,
      tripId: 7,
      itemId: 42,
      status: 'COMPLETED',
      previousStatus: 'IN_PROGRESS',
      title: '外滩',
      queuedAt: 1700000000000,
    });
    // 成功前没有失败原因
    expect(entry.lastError).toBeUndefined();
  });

  it('没有 crypto.randomUUID 时退回「时间戳+随机串」，仍是非空字符串且互不相同', () => {
    vi.stubGlobal('crypto', {});

    const first = createPendingChange({
      tripId: 1,
      itemId: 1,
      status: 'PENDING',
      previousStatus: 'PENDING',
      title: '外滩',
    });
    const second = createPendingChange({
      tripId: 1,
      itemId: 2,
      status: 'PENDING',
      previousStatus: 'PENDING',
      title: '豫园',
    });

    expect(first.requestId).toMatch(/^q-\d+-[a-z0-9]+$/);
    expect(second.requestId).toMatch(/^q-\d+-[a-z0-9]+$/);
    expect(first.requestId).not.toBe(second.requestId);
  });

  it('不传 now 时用当前时间作为 queuedAt', () => {
    const before = Date.now();
    const entry = createPendingChange({
      tripId: 1,
      itemId: 1,
      status: 'SKIPPED',
      previousStatus: 'PENDING',
      title: '外滩',
    });

    expect(entry.queuedAt).toBeGreaterThanOrEqual(before);
    expect(entry.queuedAt).toBeLessThanOrEqual(Date.now());
  });
});

describe('enqueueChange · 同 itemId 合并（last write wins）', () => {
  it('空队列入队就是单条', () => {
    const entry = change();

    expect(enqueueChange([], entry)).toEqual([entry]);
  });

  it('同一 itemId 的两条只留后一条，且排到末尾（不占原位置）', () => {
    const first = change({ itemId: 1, status: 'IN_PROGRESS' });
    const second = change({ itemId: 2 });
    const firstAgain = change({ itemId: 1, status: 'COMPLETED' });

    const next = enqueueChange([first, second], firstAgain);

    expect(next).toEqual([second, firstAgain]);
    expect(next).toHaveLength(2);
  });

  it('不修改入参数组', () => {
    const entries = [change({ itemId: 1 })];
    const snapshot = [...entries];

    const next = enqueueChange(entries, change({ itemId: 2 }));

    expect(entries).toEqual(snapshot);
    expect(next).not.toBe(entries);
  });
});

describe('dropEntry / dropByItem / pendingFor / pendingIds', () => {
  const entries = [
    change({ itemId: 1, requestId: 'a' }),
    change({ itemId: 2, requestId: 'b' }),
    change({ itemId: 3, requestId: 'c', tripId: 2 }),
  ];

  it('dropEntry 只移除匹配 requestId 的那条', () => {
    const next = dropEntry(entries, 'b');

    expect(next.map((entry) => entry.requestId)).toEqual(['a', 'c']);
    expect(dropEntry(entries, 'missing')).toEqual(entries);
  });

  it('dropByItem 移除该 itemId 的所有条目（存储被手改出重复时也能清干净）', () => {
    const duplicated = [...entries, change({ itemId: 2, requestId: 'b2' })];

    expect(dropByItem(duplicated, 2).map((entry) => entry.requestId)).toEqual(['a', 'c']);
    expect(dropByItem(entries, 99)).toEqual(entries);
  });

  it('pendingFor 返回该条目；重复时最后一条胜出；没有则 null', () => {
    expect(pendingFor(entries, 2)?.requestId).toBe('b');
    expect(pendingFor(entries, 99)).toBeNull();
    expect(pendingFor([], 1)).toBeNull();

    const duplicated = [
      change({ itemId: 2, requestId: 'old' }),
      change({ itemId: 2, requestId: 'new' }),
    ];
    expect(pendingFor(duplicated, 2)?.requestId).toBe('new');
  });

  it('pendingIds 返回全部待同步 id，传 tripId 时只算该行程', () => {
    expect(pendingIds(entries)).toEqual(new Set([1, 2, 3]));
    expect(pendingIds(entries, 1)).toEqual(new Set([1, 2]));
    expect(pendingIds(entries, 2)).toEqual(new Set([3]));
    expect(pendingIds(entries, 99)).toEqual(new Set());
    expect(pendingIds([], 1)).toEqual(new Set());
  });
});

describe('overlayStatuses · 待同步状态叠加到服务端数据', () => {
  interface Item {
    id: number | null;
    title: string;
    status?: ItineraryStatus | null;
  }

  it('有待同步条目的条目覆盖 status，其余保持原状', () => {
    const items: Item[] = [
      { id: 1, title: '外滩', status: 'PENDING' },
      { id: 2, title: '豫园', status: 'PENDING' },
    ];

    const next = overlayStatuses(items, [change({ itemId: 1, status: 'COMPLETED' })]);

    expect(next).toEqual([
      { id: 1, title: '外滩', status: 'COMPLETED' },
      { id: 2, title: '豫园', status: 'PENDING' },
    ]);
  });

  it('服务端字段缺失（status 为 null / undefined）时也能叠加', () => {
    const items: Item[] = [
      { id: 1, title: '外滩', status: null },
      { id: 2, title: '豫园' },
    ];

    const next = overlayStatuses(items, [change({ itemId: 1, status: 'SKIPPED' })]);

    expect(next[0].status).toBe('SKIPPED');
    expect(next[1].status).toBeUndefined();
  });

  it('没有待同步条目时仍返回新数组（浅拷贝）', () => {
    const items: Item[] = [{ id: 1, title: '外滩' }];

    const next = overlayStatuses(items, []);

    expect(next).toEqual(items);
    expect(next).not.toBe(items);
    expect(overlayStatuses([], [])).toEqual([]);
  });

  it('不修改入参：原数组与原对象都保持服务端状态', () => {
    const items: Item[] = [{ id: 1, title: '外滩', status: 'PENDING' }];
    const snapshot = structuredClone(items);

    const next = overlayStatuses(items, [change({ itemId: 1, status: 'COMPLETED' })]);

    expect(items).toEqual(snapshot);
    expect(next).not.toBe(items);
    expect(next[0]).not.toBe(items[0]);
  });

  it('id 为 null 的本地新增条目原样保留（同一引用，不臆造状态）', () => {
    const local: Item = { id: null, title: '未落库的新增项', status: undefined };

    const next = overlayStatuses([local], [change({ itemId: 1, status: 'COMPLETED' })]);

    expect(next[0]).toBe(local);
    expect(next[0].status).toBeUndefined();
  });

  it('同一 itemId 多条时后一条胜出（与队列 LWW 语义一致）', () => {
    const items: Item[] = [{ id: 1, title: '外滩', status: 'PENDING' }];

    const next = overlayStatuses(items, [
      change({ itemId: 1, requestId: 'old', status: 'IN_PROGRESS' }),
      change({ itemId: 1, requestId: 'new', status: 'COMPLETED' }),
    ]);

    expect(next[0].status).toBe('COMPLETED');
  });

  it('保留条目上的其他业务字段', () => {
    const item = { id: 1, title: '外滩', status: 'PENDING' as ItineraryStatus, cost: 120 };

    const next = overlayStatuses([item], [change({ itemId: 1, status: 'COMPLETED' })]);

    expect(next[0]).toEqual({ id: 1, title: '外滩', status: 'COMPLETED', cost: 120 });
  });
});

describe('parseStoredQueue · 坏数据一律不抛错', () => {
  it('null / 空串 → 空队列', () => {
    expect(parseStoredQueue(null)).toEqual([]);
    expect(parseStoredQueue('')).toEqual([]);
  });

  it('坏 JSON → 空队列', () => {
    expect(parseStoredQueue('{not-json')).toEqual([]);
    expect(parseStoredQueue('undefined')).toEqual([]);
  });

  it('JSON 合法但不是数组 → 空队列', () => {
    expect(parseStoredQueue('{"a":1}')).toEqual([]);
    expect(parseStoredQueue('42')).toEqual([]);
    expect(parseStoredQueue('null')).toEqual([]);
    expect(parseStoredQueue('"[]"')).toEqual([]);
  });

  it('数组里混入非法条目：只丢弃该条，合法条目保留', () => {
    const valid = change({ itemId: 5, requestId: 'ok' });
    const raw = JSON.stringify([
      valid,
      null,
      'string',
      [],
      { ...valid, itemId: '5' },
      { ...valid, tripId: null },
      { ...valid, requestId: '  ' },
      { ...valid, status: 'DONE' },
      { ...valid, previousStatus: undefined },
      { ...valid, title: null },
      { ...valid, queuedAt: '1000' },
      { ...valid, requestId: 'bad-error', lastError: 42 },
    ]);

    expect(parseStoredQueue(raw)).toEqual([valid]);
  });

  it('合法条目（含 lastError 字符串 / null）完整保留', () => {
    const failed = change({ itemId: 2, requestId: 'failed', lastError: '网络不可用' });
    const neverSynced = change({ itemId: 3, requestId: 'clean', lastError: null });

    expect(parseStoredQueue(JSON.stringify([failed, neverSynced]))).toEqual([failed, neverSynced]);
  });
});

describe('serializeQueue · 与 parseStoredQueue 往返', () => {
  it('序列化再解析得到同样的队列', () => {
    const entries = [change({ itemId: 1, requestId: 'a' }), change({ itemId: 2, requestId: 'b' })];

    expect(parseStoredQueue(serializeQueue(entries))).toEqual(entries);
    expect(serializeQueue([])).toBe('[]');
  });
});

describe('readQueue / writeQueue · 真实与不可用 storage', () => {
  it('写入后可读出；写空队列后读出空数组', () => {
    const entries = [change({ itemId: 1, requestId: 'a' })];

    writeQueue(entries);
    expect(readQueue()).toEqual(entries);

    writeQueue([]);
    expect(readQueue()).toEqual([]);
  });

  it('写入用 STATUS_QUEUE_KEY 保存，可被 parseStoredQueue 读取', () => {
    writeQueue([change({ itemId: 1, requestId: 'a' })]);

    expect(parseStoredQueue(localStorage.getItem(STATUS_QUEUE_KEY))).toHaveLength(1);
  });

  it('存储里是坏数据时读出空队列（不抛错）', () => {
    localStorage.setItem(STATUS_QUEUE_KEY, '{broken');

    expect(readQueue()).toEqual([]);
  });

  it('getItem 抛错时 readQueue 返回空队列', () => {
    expect(readQueue(blockedStorage('getItem'))).toEqual([]);
  });

  it('setItem 抛错时 writeQueue 静默失败（不抛错）', () => {
    expect(() => writeQueue([change()], blockedStorage('setItem'))).not.toThrow();
  });

  it('storage 传 null 表示不落盘：读空、写不抛错', () => {
    expect(readQueue(null)).toEqual([]);
    expect(() => writeQueue([change()], null)).not.toThrow();
  });

  it('显式注入 storage 时优先用它，而不是真实 localStorage', () => {
    const fake = new Map<string, string>();
    const storage: Storage = {
      length: 0,
      clear: () => fake.clear(),
      key: () => null,
      getItem: (key: string) => fake.get(key) ?? null,
      removeItem: () => undefined,
      setItem: (key: string, value: string) => {
        fake.set(key, value);
      },
    };

    writeQueue([change({ itemId: 9, requestId: 'fake' })], storage);

    expect(readQueue(storage).map((entry) => entry.requestId)).toEqual(['fake']);
    // 真实 localStorage 没有被写入
    expect(localStorage.getItem(STATUS_QUEUE_KEY)).toBeNull();
  });
});
