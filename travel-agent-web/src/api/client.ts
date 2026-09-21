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
  (resp) => {
    const body = resp.data;
    if (body && typeof body === 'object' && 'code' in body) {
      if (body.code === 0) {
        return body.data as any;
      }
      return Promise.reject(new Error(body.message || '请求失败'));
    }
    return body as any;
  },
  (error) => {
    if (error.response?.status === 401) {
      useAuthStore.getState().logout();
      window.location.href = '/login';
    }
    return Promise.reject(new Error(error.response?.data?.message || error.message || '网络错误'));
  },
);

/** 统一请求：返回后端 ApiResponse.data */
export function request<T>(config: AxiosRequestConfig): Promise<T> {
  return http.request(config) as unknown as Promise<T>;
}
