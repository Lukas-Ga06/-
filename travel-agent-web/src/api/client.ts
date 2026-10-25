import axios, { AxiosRequestConfig } from 'axios';
import { useAuthStore } from '../store/authStore';

export const http = axios.create({ baseURL: '/api', timeout: 30000 });

http.interceptors.request.use((config) => {
  const token = useAuthStore.getState().token;
  if (token) {
    config.headers.Authorization = `Bearer ${token}`;
  }
  return config;
});

http.interceptors.response.use(
  unwrapApiResponse,
  handleResponseError,
);

/** 统一解包 {code,message,data}：code 非 0 视为业务失败 */
export function unwrapApiResponse(resp: { data: unknown }) {
  const body = resp.data as { code?: number; message?: string; data?: unknown } | null;
  if (body && typeof body === 'object' && 'code' in body) {
    if (body.code === 0) {
      return body.data as any;
    }
    return Promise.reject(new Error(body.message || '请求失败'));
  }
  return body as any;
}

/** 网络/HTTP 层错误：仅在「非登录接口的 401」时登出并跳转 */
export function handleResponseError(error: any): Promise<never> {
  const url: string = error?.config?.url ?? '';
  // 登录/注册/me 接口本身会返回 401（密码错误 / 未登录探活），
  // 此时不能触发登出跳转，否则输错一次密码或在登录页探测就整页刷新。
  const isAuthRequest = url.includes('/auth/login') || url.includes('/auth/register') || url.includes('/auth/me');
  // 已在登录页时也不跳转：避免 401 → 跳 /login → 又一次 401 的循环刷新
  const alreadyOnLogin = typeof window !== 'undefined' && window.location?.pathname === '/login';
  if (error?.response?.status === 401 && !isAuthRequest && !alreadyOnLogin) {
    useAuthStore.getState().logout();
    window.location.href = '/login';
  }
  return Promise.reject(new Error(error?.response?.data?.message || error?.message || '网络错误'));
}

/** 统一请求：返回后端 ApiResponse.data */
export function request<T>(config: AxiosRequestConfig): Promise<T> {
  return http.request(config) as unknown as Promise<T>;
}
