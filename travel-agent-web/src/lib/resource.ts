/**
 * 统一资源状态模型（P0-04）。
 *
 * 背景：页面此前用 `null` / `0` / `[]` 同时表示「还没加载」「加载失败」「确实没有」，
 * 于是接口 500 或断网时首页显示 0、列表显示「暂无数据」，用户被误导成「我真没有行程」。
 * 这里把五种终态显式拆开，页面按状态渲染而不是按数据形状猜。
 */

export type ResourceState<T> =
  | { status: 'idle' }
  | { status: 'loading'; previous?: T }
  | { status: 'success'; data: T }
  | { status: 'empty' }
  | { status: 'error'; message: string; retryable: boolean; previous?: T }
  | { status: 'offline-stale'; data: T; cachedAt: string };

/** 加载中：保留上一份数据，页面可以「旧内容 + 加载指示」而不是闪成骨架 */
export function loadingResource<T>(previous?: T): ResourceState<T> {
  return previous === undefined ? { status: 'loading' } : { status: 'loading', previous };
}

/**
 * 载荷 → 成功或空。
 * `isEmpty` 由调用方决定：数组看长度，对象看必要字段，绝不能默认「falsy 就是空」。
 */
export function successOrEmpty<T>(
  data: T,
  isEmpty: (value: T) => boolean = defaultIsEmpty,
): ResourceState<T> {
  return isEmpty(data) ? { status: 'empty' } : { status: 'success', data };
}

/** 失败状态；`retryable` 区分「重试有意义」与「权限/404 之类重试也没用」 */
export function errorResource<T>(
  message: string,
  options: { retryable?: boolean; previous?: T } = {},
): ResourceState<T> {
  const state: ResourceState<T> = {
    status: 'error',
    message: message || '加载失败',
    retryable: options.retryable ?? true,
  };
  if (options.previous !== undefined) {
    state.previous = options.previous;
  }
  return state;
}

/** 离线但有缓存：页面显示缓存内容 + 缓存时间 + 只读标记 */
export function offlineStaleResource<T>(data: T, cachedAt: string | Date): ResourceState<T> {
  return {
    status: 'offline-stale',
    data,
    cachedAt: cachedAt instanceof Date ? cachedAt.toISOString() : cachedAt,
  };
}

/** 状态上实际可渲染的数据（success / offline-stale 才有） */
export function resourceData<T>(state: ResourceState<T>): T | undefined {
  if (state.status === 'success') return state.data;
  if (state.status === 'offline-stale') return state.data;
  if (state.status === 'loading') return state.previous;
  if (state.status === 'error') return state.previous;
  return undefined;
}

/** 是否需要展示「重试」入口 */
export function canRetry<T>(state: ResourceState<T>): boolean {
  return state.status === 'error' && state.retryable;
}

function defaultIsEmpty(value: unknown): boolean {
  if (value === null || value === undefined) return true;
  if (Array.isArray(value)) return value.length === 0;
  return false;
}
