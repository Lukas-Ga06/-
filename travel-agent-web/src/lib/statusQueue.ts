import type { ItineraryStatus } from '../types/trip';

/**
 * 行程项状态的「离线待同步队列」（设计文档 §7.3 第 4 条 / §10.3）。
 *
 * 为什么可以排队重放：
 * 后端 `PUT /api/itinerary/{id}/status` 是**声明式绝对值**语义（写的是「这个条目现在是什么状态」，
 * 不是「推进一格」），重复重放同一值结果一致，因此离线期间攒下的变更在网络恢复后顺序重放是安全的。
 * 每条变更仍带稳定 `requestId`（入队时生成、重试复用），供将来服务端幂等台账按
 * (userId, requestId) 去重。
 *
 * 本文件是纯逻辑：不 import React / zustand，也不直接碰 axios；所有存储访问都可注入、绝不抛错。
 */

export interface PendingStatusChange {
  /** 稳定幂等标识：入队生成、重试复用 */
  requestId: string;
  tripId: number;
  itemId: number;
  /** 目标状态 */
  status: ItineraryStatus;
  /** 入队前的服务端状态（撤销/回滚用） */
  previousStatus: ItineraryStatus;
  /** 条目标题（同步结果里显示「外滩 已完成」） */
  title: string;
  /** 入队时间（epoch ms） */
  queuedAt: number;
  /** 最近一次同步失败原因；成功后清空 */
  lastError?: string | null;
}

/** localStorage 键。带版本号：结构变了就换 v2，旧数据自然被忽略而不是读崩 */
export const STATUS_QUEUE_KEY = 'travel:status-queue:v1';

/** 合法状态值（运行时校验用；类型层面由 ItineraryStatus 保证） */
const ITINERARY_STATUSES: readonly ItineraryStatus[] = [
  'PENDING',
  'IN_PROGRESS',
  'COMPLETED',
  'SKIPPED',
];

function isItineraryStatus(value: unknown): value is ItineraryStatus {
  return typeof value === 'string' && (ITINERARY_STATUSES as readonly string[]).includes(value);
}

/**
 * 生成幂等标识。
 * 优先 `crypto.randomUUID`（真随机、跨设备重复概率可忽略）；运行环境没有时退回
 * 「前缀 + 时间戳 + 随机串」，仍是字符串且不会在同一毫秒内撞车。
 */
function newRequestId(): string {
  if (typeof crypto !== 'undefined' && typeof crypto.randomUUID === 'function') {
    return crypto.randomUUID();
  }
  return `q-${Date.now()}-${Math.random().toString(36).slice(2, 10)}`;
}

/** 生成一条待同步变更（requestId 用 crypto.randomUUID，缺省时退回时间戳+随机） */
export function createPendingChange(input: {
  tripId: number;
  itemId: number;
  status: ItineraryStatus;
  previousStatus: ItineraryStatus;
  title: string;
  now?: number;
}): PendingStatusChange {
  return {
    requestId: newRequestId(),
    tripId: input.tripId,
    itemId: input.itemId,
    status: input.status,
    previousStatus: input.previousStatus,
    title: input.title,
    queuedAt: input.now ?? Date.now(),
  };
}

/**
 * 入队：同一 itemId 只保留最后一条（last write wins）。
 * 旧条目先被移出原位置，新条目排到末尾 —— 队列顺序即「用户最后一次操作这些条目的顺序」，
 * 重放时后发生的操作落在后面，语义与在线时一致。
 */
export function enqueueChange(
  entries: PendingStatusChange[],
  change: PendingStatusChange,
): PendingStatusChange[] {
  return [...entries.filter((entry) => entry.itemId !== change.itemId), change];
}

export function dropEntry(
  entries: PendingStatusChange[],
  requestId: string,
): PendingStatusChange[] {
  return entries.filter((entry) => entry.requestId !== requestId);
}

export function dropByItem(entries: PendingStatusChange[], itemId: number): PendingStatusChange[] {
  return entries.filter((entry) => entry.itemId !== itemId);
}

/** 读某个条目的待同步变更；存储被手改出重复时按「最后一条胜出」 */
export function pendingFor(
  entries: PendingStatusChange[],
  itemId: number,
): PendingStatusChange | null {
  for (let index = entries.length - 1; index >= 0; index -= 1) {
    if (entries[index].itemId === itemId) return entries[index];
  }
  return null;
}

/** 待同步条目的 id 集合（可只算某个行程），用于时间轴打「待同步」标记 */
export function pendingIds(entries: PendingStatusChange[], tripId?: number): Set<number> {
  const ids = new Set<number>();
  for (const entry of entries) {
    if (tripId !== undefined && entry.tripId !== tripId) continue;
    ids.add(entry.itemId);
  }
  return ids;
}

/**
 * 把待同步状态叠加到服务端数据上：
 * - 不修改入参，返回新数组（无待同步条目时也返回新数组，调用方可以无脑 memo 比较长度）；
 * - `id` 为 null 的条目（还没落库的本地新增项）原样保留，同一对象引用，不臆造状态；
 * - 同一 itemId 有多条（理论上不会，队列已按 itemId 合并）时后一条胜出，与队列语义一致。
 */
export function overlayStatuses<T extends { id: number | null; status?: ItineraryStatus | null }>(
  items: T[],
  entries: PendingStatusChange[],
): T[] {
  if (entries.length === 0) return items.slice();
  const byItem = new Map<number, PendingStatusChange>();
  for (const entry of entries) byItem.set(entry.itemId, entry);
  return items.map((item) => {
    if (typeof item.id !== 'number') return item;
    const pending = byItem.get(item.id);
    if (!pending) return item;
    // 展开保留服务端返回的其他字段，只覆盖 status（T 的其余字段由调用方保证）
    return { ...item, status: pending.status } as T;
  });
}

/** 校验一条存储记录；任何字段不合法都返回 null（该条丢弃，不连累其他条目） */
function parseStoredEntry(value: unknown): PendingStatusChange | null {
  if (!value || typeof value !== 'object' || Array.isArray(value)) return null;
  const raw = value as Record<string, unknown>;
  const { requestId, tripId, itemId, title, queuedAt, lastError } = raw;
  if (typeof requestId !== 'string' || requestId.trim() === '') return null;
  if (typeof tripId !== 'number' || !Number.isFinite(tripId)) return null;
  if (typeof itemId !== 'number' || !Number.isFinite(itemId)) return null;
  if (!isItineraryStatus(raw.status) || !isItineraryStatus(raw.previousStatus)) return null;
  if (typeof title !== 'string') return null;
  if (typeof queuedAt !== 'number' || !Number.isFinite(queuedAt)) return null;
  if (lastError !== undefined && lastError !== null && typeof lastError !== 'string') return null;

  const entry: PendingStatusChange = {
    requestId,
    tripId,
    itemId,
    status: raw.status,
    previousStatus: raw.previousStatus,
    title,
    queuedAt,
  };
  if (typeof lastError === 'string') entry.lastError = lastError;
  else if (lastError === null) entry.lastError = null;
  return entry;
}

/**
 * 读 localStorage 字符串：坏 JSON / 非数组 / 某条字段不合法 → 丢弃该条；整体不可用 → []。
 * 绝不抛错：队列是离线兜底，不能因为存储脏数据让页面白屏。
 */
export function parseStoredQueue(raw: string | null): PendingStatusChange[] {
  if (!raw) return [];
  let parsed: unknown;
  try {
    parsed = JSON.parse(raw);
  } catch {
    return [];
  }
  if (!Array.isArray(parsed)) return [];
  const entries: PendingStatusChange[] = [];
  for (const item of parsed) {
    const entry = parseStoredEntry(item);
    if (entry) entries.push(entry);
  }
  return entries;
}

export function serializeQueue(entries: PendingStatusChange[]): string {
  return JSON.stringify(entries);
}

/** 真实 localStorage；隐私模式/禁用时取不到（访问属性本身就可能抛错），返回 null */
function defaultStorage(): Storage | null {
  try {
    const storage = globalThis.localStorage as Storage | undefined;
    return storage ?? null;
  } catch {
    return null;
  }
}

/** storage 显式传 undefined 才走真实 localStorage；传 null 表示「本次不落盘」 */
function resolveStorage(storage: Storage | null | undefined): Storage | null {
  return storage === undefined ? defaultStorage() : storage;
}

/** 读真实 localStorage（不可用时不抛错，退化为空队列） */
export function readQueue(storage?: Storage | null): PendingStatusChange[] {
  const target = resolveStorage(storage);
  if (!target) return [];
  try {
    return parseStoredQueue(target.getItem(STATUS_QUEUE_KEY));
  } catch {
    return [];
  }
}

/**
 * 写真实 localStorage（不可用时不抛错）。
 * 失败只影响「刷新后是否还记得」，内存里的 entries 仍然可用；
 * 写不进去时不倒回来清空队列，避免把用户刚做的操作弄丢。
 */
export function writeQueue(entries: PendingStatusChange[], storage?: Storage | null): void {
  const target = resolveStorage(storage);
  if (!target) return;
  try {
    target.setItem(STATUS_QUEUE_KEY, serializeQueue(entries));
  } catch {
    // 配额满 / 隐私模式 / Safari 无痕：静默降级为「本次会话内存队列」
  }
}
