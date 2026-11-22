import axios, { AxiosRequestConfig } from 'axios';
import { useAuthStore } from '../store/authStore';

export const http = axios.create({ baseURL: '/api', timeout: 30000 });

declare module 'axios' {
  export interface AxiosRequestConfig {
    /**
     * 内部标记：需要保留外层响应体（含 pagination）。
     * 走这个标记的请求由 getPage 自己解包，避免分页元数据在响应拦截器里被丢掉。
     */
    keepEnvelope?: boolean;
  }
}

/** 列表分页查询参数：值为 undefined 的键不拼进 query（后端按默认值处理） */
export type QueryParams = Record<string, string | number | undefined>;

/** 列表页默认条数（后端契约：limit 默认 20、上限 200） */
export const DEFAULT_PAGE_SIZE = 20;
/** 列表页单页上限（后端契约：limit 上限 200） */
export const MAX_PAGE_SIZE = 200;

/**
 * 后端返回的可选分页元数据。未分页接口（或还没升级的旧后端）不返回该键，
 * 前端必须按「单页、无更多」降级，不能因此报错或空白。
 */
export interface PaginationMeta {
  total: number;
  limit: number;
  offset: number;
  hasMore: boolean;
}

/** 分页列表结果：items 恒为数组；pagination 为 null 表示后端未分页 */
export interface PageResult<T> {
  items: T[];
  pagination: PaginationMeta | null;
}

/** query 序列化前剔除 undefined/null，避免拼出 `?offset=undefined` */
export function compactParams(params?: QueryParams): Record<string, string | number> {
  const query: Record<string, string | number> = {};
  if (!params) return query;
  for (const [key, value] of Object.entries(params)) {
    if (value === undefined || value === null) continue;
    query[key] = value;
  }
  return query;
}

/**
 * 读取响应体里的 pagination。
 * 缺失、格式不合法或 total 不是有限数时返回 null（调用方按单页降级）。
 * hasMore 缺失时用 `offset + 本页条数 < total` 推断。
 */
export function readPagination(raw: unknown, items?: unknown): PaginationMeta | null {
  if (!raw || typeof raw !== 'object') return null;
  const value = raw as Record<string, unknown>;
  const total = Number(value.total);
  if (!Number.isFinite(total) || total < 0) return null;
  const limit = Number(value.limit);
  const offset = Number(value.offset);
  const safeLimit = Number.isFinite(limit) && limit > 0 ? Math.trunc(limit) : DEFAULT_PAGE_SIZE;
  const safeOffset = Number.isFinite(offset) && offset >= 0 ? Math.trunc(offset) : 0;
  const count = Array.isArray(items) ? items.length : 0;
  const hasMore =
    typeof value.hasMore === 'boolean' ? value.hasMore : safeOffset + count < Math.trunc(total);
  return { total: Math.trunc(total), limit: safeLimit, offset: safeOffset, hasMore };
}

http.interceptors.request.use((config) => {
  const token = useAuthStore.getState().token;
  if (token) {
    config.headers.Authorization = `Bearer ${token}`;
  }
  return config;
});

http.interceptors.response.use(unwrapApiResponse, handleResponseError);

/**
 * 统一解包 {code,message,data}：code 非 0 视为业务失败。
 * keepEnvelope 的请求返回 { data, pagination }，供分页列表读取分页元数据。
 */
export function unwrapApiResponse(resp: { data: unknown; config?: { keepEnvelope?: boolean } }) {
  const body = resp.data as {
    code?: number;
    message?: string;
    data?: unknown;
    pagination?: unknown;
  } | null;
  const keepEnvelope = resp.config?.keepEnvelope === true;
  if (body && typeof body === 'object' && 'code' in body) {
    if (body.code === 0) {
      if (keepEnvelope) {
        return { data: body.data, pagination: readPagination(body.pagination, body.data) };
      }
      return body.data as any;
    }
    return Promise.reject(new Error(body.message || '请求失败'));
  }
  // 没有 {code,...} 包装的响应（静态桩/非标准接口）原样返回
  if (keepEnvelope) {
    return { data: body, pagination: null };
  }
  return body as any;
}

/**
 * HTTP 层错误：除了 message 之外保留状态码与 Retry-After，
 * 因为「未登录（401）」和「被限流（429）」是两种完全不同的语义，
 * 调用方（登录态恢复、页面数据加载）必须能区分。
 */
export class ApiError extends Error {
  readonly status?: number;
  readonly retryAfterSeconds?: number;

  constructor(message: string, status?: number, retryAfterSeconds?: number) {
    super(message);
    this.name = 'ApiError';
    this.status = status;
    this.retryAfterSeconds = retryAfterSeconds;
  }
}

/** 从 Retry-After 头或后端文案（"请 92 秒后再试"）里取出需要等待的秒数 */
export function getRetryAfterSeconds(
  headers?: Record<string, unknown> | null,
  bodyMessage?: unknown,
): number | undefined {
  const raw = headers?.['retry-after'] ?? headers?.['Retry-After'];
  if (raw !== undefined && raw !== null && raw !== '') {
    const seconds = Number(raw);
    if (Number.isFinite(seconds) && seconds >= 0) return Math.ceil(seconds);
  }
  if (typeof bodyMessage === 'string') {
    const matched = bodyMessage.match(/(\d+)\s*秒/);
    if (matched) return Number(matched[1]);
  }
  return undefined;
}

/** 读取错误的 HTTP 状态码：兼容 axios 原始错误与我校转换后的 ApiError */
export function getErrorStatus(error: unknown): number | undefined {
  const candidate = error as { status?: unknown; response?: { status?: unknown } } | null;
  if (typeof candidate?.status === 'number') return candidate.status;
  const nested = candidate?.response?.status;
  return typeof nested === 'number' ? nested : undefined;
}

/** 读取错误上携带的 Retry-After 秒数（ApiError / axios 原始错误都支持） */
export function getErrorRetryAfter(error: unknown): number | undefined {
  const candidate = error as { retryAfterSeconds?: unknown; response?: unknown } | null;
  if (typeof candidate?.retryAfterSeconds === 'number') return candidate.retryAfterSeconds;
  const response = candidate?.response as
    | { status?: number; headers?: Record<string, unknown>; data?: { message?: unknown } }
    | undefined;
  if (!response) return undefined;
  return getRetryAfterSeconds(response.headers, response.data?.message);
}

/** 只有 401 才算「登录态失效」：429 限流、5xx、网络错误都不许清登录态 */
export function isAuthExpired(status?: number | null): boolean {
  return status === 401;
}

/** /auth/me 恢复登录态时可以退避重试的状态：429 限流与 5xx 临时故障 */
export function shouldRetryMe(status?: number | null): boolean {
  if (status === 429) return true;
  return typeof status === 'number' && status >= 500 && status <= 599;
}

/** 429 的用户提示：能拿到秒数就带上，方便用户知道等多久 */
export function buildRateLimitNotice(retryAfterSeconds?: number | null): string {
  if (typeof retryAfterSeconds === 'number' && retryAfterSeconds > 0) {
    return `请求过于频繁，请 ${retryAfterSeconds} 秒后重试`;
  }
  return '请求过于频繁，请稍后重试';
}

/** 网络/HTTP 层错误：仅「非登录接口的 401」时登出并跳转 */
export function handleResponseError(error: any): Promise<never> {
  const url: string = error?.config?.url ?? '';
  const status: number | undefined = error?.response?.status;
  const body = error?.response?.data;
  const bodyMessage =
    typeof body?.message === 'string' ? body.message : typeof body === 'string' ? body : undefined;
  const message: string = bodyMessage || error?.message || '网络错误';
  const retryAfterSeconds = getRetryAfterSeconds(error?.response?.headers, bodyMessage);
  // 登录/注册/me 接口本身会返回 401（密码错误 / 未登录探活），
  // 此时不能触发登出跳转，否则输错一次密码或在登录页探测就整页刷新。
  const isAuthRequest =
    url.includes('/auth/login') || url.includes('/auth/register') || url.includes('/auth/me');
  // 已在登录页时也不跳转：避免 401 → 跳 /login → 又一次 401 的循环刷新
  const alreadyOnLogin = typeof window !== 'undefined' && window.location?.pathname === '/login';
  if (isAuthExpired(status) && !isAuthRequest && !alreadyOnLogin) {
    useAuthStore.getState().logout();
    window.location.href = '/login';
  }
  return Promise.reject(new ApiError(message, status, retryAfterSeconds));
}

/** 统一请求：返回后端 ApiResponse.data */
export function request<T>(config: AxiosRequestConfig): Promise<T> {
  return http.request(config) as unknown as Promise<T>;
}

/** GET：params 里 undefined 的键不会拼进 query */
export function get<T>(path: string, params?: QueryParams): Promise<T> {
  return request<T>({ url: path, method: 'get', params: compactParams(params) });
}

/**
 * 分页 GET：返回 { items, pagination }。
 * `data` 仍是数组；后端没返回 pagination 时 pagination 为 null，
 * 调用方按「单页、无更多」渲染（旧后端 / 未分页接口都走这条路）。
 */
export function getPage<T>(path: string, params?: QueryParams): Promise<PageResult<T>> {
  return request<{ data: T[] | null; pagination: PaginationMeta | null }>({
    url: path,
    method: 'get',
    params: compactParams(params),
    keepEnvelope: true,
  }).then((envelope) => {
    const items = Array.isArray(envelope?.data) ? envelope.data : [];
    return { items, pagination: envelope?.pagination ?? null };
  });
}
